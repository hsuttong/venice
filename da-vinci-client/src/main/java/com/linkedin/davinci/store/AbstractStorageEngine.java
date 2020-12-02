package com.linkedin.davinci.store;

import com.linkedin.venice.exceptions.PersistenceFailureException;
import com.linkedin.venice.exceptions.StorageInitializationException;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.kafka.protocol.state.PartitionState;
import com.linkedin.venice.kafka.protocol.state.StoreVersionState;
import com.linkedin.venice.meta.PersistenceType;
import com.linkedin.venice.offsets.OffsetRecord;
import com.linkedin.venice.serialization.avro.InternalAvroSpecificSerializer;
import com.linkedin.venice.utils.SparseConcurrentList;

import java.util.function.Supplier;
import org.apache.log4j.Logger;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;


/**
 * A base storage abstract class which is actually responsible for data persistence. This
 * abstract class implies all the usual responsibilities of a Store implementation,
 * <p/>
 * There are several proposals for storage-partition model:
 * <p/>
 * 1. One storage engine for all stores
 * 1.1 One store uses one database, i.e. all partitions of the store will be in one database.
 * 1.2 One store uses multiple databases, i.e. one partition per database.
 * 2. Each store handled by one storage engine
 * 2.1 All partitions of the store will be handled in one database (current Voldemort implementation)
 * 2.2 One partition per database (Sudha suggests)
 * 3. Each partition handled by one storage engine (original proposal before today’s discussion, super high overhead)
 * <p/>
 * The point of having one storage engine(environment) or one database for one partition, is to simplify the complexity of rebalancing/partition migration/host swap.
 * The team agreed to take (2.2) as default storage-partition model for now, and run performance tests to see if it goes well.
 */
public abstract class AbstractStorageEngine<Partition extends AbstractStoragePartition> implements Closeable {
  private static final Logger logger = Logger.getLogger(AbstractStorageEngine.class);

  private static final byte[] VERSION_METADATA_KEY = "VERSION_METADATA".getBytes();
  private static final byte[] METADATA_MIGRATION_KEY = "METADATA_MIGRATION".getBytes();
  private static final String PARTITION_METADATA_PREFIX = "P_";

  // Using a large positive number for metadata partition id instead of -1 can avoid database naming issues.
  public static final int METADATA_PARTITION_ID = 1000_000_000;

  private final String storeName;
  private final List<Partition> partitionList = new SparseConcurrentList<>();
  private Partition metadataPartition = null;
  private final AtomicReference<StoreVersionState> versionStateCache = new AtomicReference<>();
  private final InternalAvroSpecificSerializer<StoreVersionState> storeVersionStateSerializer;
  private final InternalAvroSpecificSerializer<PartitionState> partitionStateSerializer;

  public AbstractStorageEngine(String storeName,
                               InternalAvroSpecificSerializer<StoreVersionState> storeVersionStateSerializer,
                               InternalAvroSpecificSerializer<PartitionState> partitionStateSerializer) {
    this.storeName = storeName;
    this.metadataPartition = null;
    this.storeVersionStateSerializer = storeVersionStateSerializer;
    this.partitionStateSerializer = partitionStateSerializer;
  }

  public String getName() {
    return storeName;
  }

  public abstract PersistenceType getType();
  public abstract long getStoreSizeInBytes();
  protected abstract Set<Integer> getPersistedPartitionIds();
  public abstract Partition createStoragePartition(StoragePartitionConfig partitionConfig);

  /**
   * Load the existing storage partitions.
   * The implementation should decide when to call this function properly to restore partitions.
   */
  protected synchronized void restoreStoragePartitions(boolean restoreMetadataPartition, boolean restoreDataPartitions) {
    Set<Integer> partitionIds = getPersistedPartitionIds();

    /**
     * We remove the special partition IDs from the set because we don't want to store them in the
     * {@link #partitionList}, as that would blow up the array size of the collection, causing memory
     * pressure and potentially OOMing.
     */
    partitionIds.remove(METADATA_PARTITION_ID);

    if (restoreMetadataPartition) {
      logger.info("Metadata partition restore enabled. Restoring metadata partition.");
      this.metadataPartition = createStoragePartition(new StoragePartitionConfig(storeName, METADATA_PARTITION_ID));
    }

    if (restoreDataPartitions) {
      logger.info("Data partitions restore enabled. Restoring data partitions.");
      partitionIds.stream().sorted((o1, o2) -> Integer.compare(o2, o1)) // reverse order, to minimize array resizing in {@link SparseConcurrentList}
          .forEach(this::addStoragePartition);
    }
  }

  protected synchronized void restoreStoragePartitions() {
    restoreStoragePartitions(true, true);
  }

  public boolean isMetadataMigrationCompleted() {
    return isMetadataMigrationCompleted(this.metadataPartition);
  }

  private boolean isMetadataMigrationCompleted(Partition someMetadataPartition) {
    return null != someMetadataPartition.get(METADATA_MIGRATION_KEY);
  }

  public synchronized void preparePartitionForReading(int partitionId) {
    if (!containsPartition(partitionId)) {
      logger.warn("Partition " + storeName + "_" + partitionId + " was removed before reopening.");
      return;
    }
    StoragePartitionConfig partitionConfig = new StoragePartitionConfig(storeName, partitionId);
    partitionConfig.setWriteOnlyConfig(false);

    adjustStoragePartition(partitionConfig);
  }

  public void warmUpStoragePartition(int partitionId) {
    if (!containsPartition(partitionId)) {
      logger.warn("Partition " + storeName + "_" + partitionId + " doesn't exist.");
      return;
    }
    AbstractStoragePartition storagePartition = getPartitionOrThrow(partitionId);
    storagePartition.warmUp();
  }

  /**
   * Adjust the opened storage partition according to the provided storagePartitionConfig.
   * It will throw exception if there is no opened storage partition for the given partition id.
   */
  public synchronized void adjustStoragePartition(StoragePartitionConfig partitionConfig) {
    validateStoreName(partitionConfig);
    int partitionId = partitionConfig.getPartitionId();
    AbstractStoragePartition partition = getPartitionOrThrow(partitionId);
    if (partition.verifyConfig(partitionConfig)) {
      logger.info("No adjustment needed for store name: " + getName() + ", partition id: " + partitionId);
      return;
    }
    // Need to re-open storage partition according to the provided partition config
    logger.info("Reopen database with storage partition config: " + partitionConfig);
    closePartition(partitionId);
    addStoragePartition(partitionConfig);
  }

  public void addStoragePartition(int partitionId) {
    addStoragePartition(new StoragePartitionConfig(storeName, partitionId));
  }

  public synchronized void addStoragePartition(StoragePartitionConfig storagePartitionConfig) {
    validateStoreName(storagePartitionConfig);
    int partitionId = storagePartitionConfig.getPartitionId();

    if (partitionId == METADATA_PARTITION_ID) {
      throw new StorageInitializationException("The metadata partition is not allowed to be set via this function!");
    }

    if (containsPartition(partitionId)) {
      logger.error("Failed to add a storage partition for partitionId: " + partitionId + " Store " + this.getName() +" . This partition already exists!");
      throw new StorageInitializationException("Partition " + partitionId + " of store " + this.getName() + " already exists.");
    }

    Partition partition = createStoragePartition(storagePartitionConfig);
    this.partitionList.set(partitionId, partition);
  }

  public synchronized void closePartition(int partitionId) {
    AbstractStoragePartition partition = this.partitionList.remove(partitionId);
    if (partition == null) {
      logger.error("Failed to close a non existing partition: " + partitionId + " Store " + this.getName() );
      return;
    }
    partition.close();
    if (getNumberOfPartitions() == 0) {
      logger.info("All Partitions closed for " + this.getName() );
    }
  }

  /**
   * Removes and returns a partition from the current store
   *
   * @param partitionId - id of partition to retrieve and remove
   */
  public synchronized void dropPartition(int partitionId) {
    /**
     * The caller of this method should ensure that:
     * 1. The SimpleKafkaConsumerTask associated with this partition is shutdown
     * 2. The partition node assignment repo is cleaned up and then remove this storage partition.
     *    Else there can be situations where the data is consumed from Kafka and not persisted.
     */
    if (!containsPartition(partitionId)) {
      logger.error("Failed to remove a non existing partition: " + partitionId + " Store " + this.getName() );
      return;
    }
    logger.info("Removing Partition: " + partitionId + " Store " + this.getName());

    /**
     * Partition offset should be cleared by StorageEngine drops the corresponding partition. Here we may not be able to
     * guarantee the drop-partition order in bulk deletion, but if metadata partition get removed first, then it needs not
     * to clear partition offset.
     */
    if (metadataPartitionCreated() && partitionId != METADATA_PARTITION_ID) {
      clearPartitionOffset(partitionId);
    }

    AbstractStoragePartition partition = this.partitionList.remove(partitionId);
    partition.drop();

    if (getNumberOfPartitions() == 0) {
      logger.info("All Partitions deleted for Store " + this.getName() );
      /**
       * The reason to invoke {@link #drop} here is that storage engine might need to do some cleanup
       * in the store level.
       */
      drop();
    }
  }

  public synchronized void dropMetadataPartition() {
    if (metadataPartitionCreated()) {
      metadataPartition.drop();
      metadataPartition = null;
      versionStateCache.set(null);
    }
  }

  /**
   * Drop the whole store
   */
  public synchronized void drop() {
    // check if its already dropped.
    if (getNumberOfPartitions() == 0 && !metadataPartitionCreated()) {
      return;
    }

    logger.info("Started dropping store: " + getName());
    // partitionList is implementaion of SparseConcurrentList which sets element to null on `remove`. So its fine
    // to call size() while removing elements from the list.
    for (int partitionId = 0; partitionId < partitionList.size(); partitionId++) {
      if (!containsPartition(partitionId)) {
        continue;
      }
      dropPartition(partitionId);
    }
    dropMetadataPartition();
    logger.info("Finished dropping store: " + getName());
  }

  public synchronized Map<String, String> sync(int partitionId) {
    AbstractStoragePartition partition = partitionList.get(partitionId);
    if (partition == null) {
      logger.warn("Partition " + partitionId + " doesn't exist, no sync operation will be executed");
      return Collections.emptyMap();
    }
    return partition.sync();
  }

  @Override
  public synchronized void close() throws VeniceException {
    partitionList.forEach(Partition::close);
    partitionList.clear();
    if (metadataPartitionCreated()) {
      metadataPartition.close();
      metadataPartition = null;
      versionStateCache.set(null);
    }
  }

  /**
   * A lot of storage engines support efficient methods for performing large
   * number of writes (puts/deletes) against the data source. This method puts
   * the storage engine in this batch write mode
   */
  public synchronized void beginBatchWrite(StoragePartitionConfig storagePartitionConfig,
      Map<String, String> checkpointedInfo, Optional<Supplier<byte[]>> checksumSupplier) {
    logger.info("Begin batch write for storage partition config: " + storagePartitionConfig + " with checkpointed info: " + checkpointedInfo);
    /**
     * We want to adjust the storage partition first since it will possibly re-open the underlying database in
     * different mode.
     */
    adjustStoragePartition(storagePartitionConfig);
    getPartitionOrThrow(storagePartitionConfig.getPartitionId()).beginBatchWrite(checkpointedInfo, checksumSupplier);
  }

  /**
   * @return true if the storage engine successfully returned to normal mode
   */
  public synchronized void endBatchWrite(StoragePartitionConfig storagePartitionConfig) {
    logger.info("End batch write for storage partition config: " + storagePartitionConfig);
    AbstractStoragePartition partition = getPartitionOrThrow(storagePartitionConfig.getPartitionId());
    partition.endBatchWrite();
    /**
     * After end of batch push, we would like to adjust the underlying database for the future ingestion, such as from streaming.
     */
    adjustStoragePartition(storagePartitionConfig);

    if (!partition.validateBatchIngestion()) {
      throw new VeniceException("Storage temp files not fully ingested for store: " + storeName);
    }
  }

  public void put(int partitionId, byte[] key, byte[] value) throws VeniceException {
    AbstractStoragePartition partition = getPartitionOrThrow(partitionId);
    partition.put(key, value);
  }

  public void put(int partitionId, byte[] key, ByteBuffer value) throws VeniceException {
    AbstractStoragePartition partition = getPartitionOrThrow(partitionId);
    partition.put(key, value);
  }

  public byte[] get(int partitionId, byte[] key) throws VeniceException {
    AbstractStoragePartition partition = getPartitionOrThrow(partitionId);
    return partition.get(key);
  }

  public ByteBuffer get(int partitionId, byte[] key, ByteBuffer valueToBePopulated) throws VeniceException {
    AbstractStoragePartition partition = getPartitionOrThrow(partitionId);
    return partition.get(key, valueToBePopulated);
  }

  public byte[] get(int partitionId, ByteBuffer keyBuffer) throws VeniceException {
    AbstractStoragePartition partition = getPartitionOrThrow(partitionId);
    return partition.get(keyBuffer);
  }

  public void delete(int partitionId, byte[] key) throws VeniceException {
    AbstractStoragePartition partition = getPartitionOrThrow(partitionId);
    partition.delete(key);
  }

  /**
   * Put the offset associated with the partitionId into the metadata partition.
   */
  public synchronized void putPartitionOffset(int partitionId, OffsetRecord offsetRecord) {
    if (!metadataPartitionCreated()) {
      throw new StorageInitializationException("Metadata partition not created!");
    }
    if (partitionId == METADATA_PARTITION_ID) {
      throw new IllegalArgumentException("Metadata partition id should not be used as argument in putPartitionOffset.");
    }
    if (partitionId < 0) {
      throw new IllegalArgumentException("Invalid partition id argument in putPartitionOffset");
    }
    metadataPartition.put(getPartitionMetadataKey(partitionId), offsetRecord.toBytes());
  }

  /**
   * Retrieve the offset associated with the partitionId from the metadata partition.
   */
  public synchronized Optional<OffsetRecord> getPartitionOffset(int partitionId) {
    if (!metadataPartitionCreated()) {
      throw new StorageInitializationException("Metadata partition not created!");
    }
    if (partitionId == METADATA_PARTITION_ID) {
      throw new IllegalArgumentException("Metadata partition id should not be used as argument in getPartitionOffset.");
    }
    if (partitionId < 0) {
      throw new IllegalArgumentException("Invalid partition id argument in getPartitionOffset");
    }
    byte[] value = metadataPartition.get(getPartitionMetadataKey(partitionId));
    if (null == value) {
      return Optional.empty();
    }
    return Optional.of(new OffsetRecord(value, partitionStateSerializer));
  }

  /**
   * Clear the offset associated with the partitionId in the metadata partition.
   */
  public synchronized void clearPartitionOffset(int partitionId) {
    if (!metadataPartitionCreated()) {
      throw new StorageInitializationException("Metadata partition not created!");
    }
    if (partitionId == METADATA_PARTITION_ID) {
      throw new IllegalArgumentException("Metadata partition id should not be used as argument in clearPartitionOffset.");
    }
    if (partitionId < 0) {
      throw new IllegalArgumentException("Invalid partition id argument in clearPartitionOffset");
    }
    metadataPartition.delete(getPartitionMetadataKey(partitionId));
  }

  /**
   * Put the store version state into the metadata partition.
   */
  public synchronized void putStoreVersionState(StoreVersionState versionState) {
    if (!metadataPartitionCreated()) {
      throw new StorageInitializationException("Metadata partition not created!");
    }
    versionStateCache.set(versionState);
    metadataPartition.put(VERSION_METADATA_KEY, storeVersionStateSerializer.serialize(getName(), versionState));
  }

  /**
   * Retrieve the store version state from the metadata partition.
   */
  public synchronized Optional<StoreVersionState> getStoreVersionState() {
    StoreVersionState versionState = versionStateCache.get();
    if (versionState != null) {
      return Optional.of(versionState);
    }
    byte[] value = metadataPartition.get(VERSION_METADATA_KEY);
    if (null == value) {
      return Optional.empty();
    }
    versionState = storeVersionStateSerializer.deserialize(storeName, value);
    versionStateCache.set(versionState);
    return Optional.of(versionState);
  }

  /**
   * Clear the store version state in the metadata partition.
   */
  public synchronized void clearStoreVersionState() {
    versionStateCache.set(null);
    metadataPartition.delete(VERSION_METADATA_KEY);
  }

  /**
   * Return true or false based on whether a given partition exists within this storage engine
   *
   * @param partitionId The partition to look for
   * @return True/False, does the partition exist on this node
   */
  public synchronized boolean containsPartition(int partitionId) {
    return null != this.partitionList.get(partitionId);
  }

  /**
   * A function which behaves like {@link Map#size()}, in the sense that it ignores empty
   * (null) slots in the list.
   *
   * @return the number of non-null partitions in {@link #partitionList}
   */
  protected synchronized long getNumberOfPartitions() {
    return this.partitionList.stream().filter(Objects::nonNull).count();
  }

  /**
   * Get all Partition Ids which are assigned to the current Node.
   *
   * @return partition Ids that are hosted in the current Storage Engine.
   */
  public synchronized Set<Integer> getPartitionIds() {
    return this.partitionList.stream()
               .filter(Objects::nonNull)
               .map(Partition::getPartitionId)
               .collect(Collectors.toSet());
  }

  public synchronized AbstractStoragePartition getPartitionOrThrow(int partitionId) {
    AbstractStoragePartition partition = partitionList.get(partitionId);
    if (partition == null) {
      VeniceException e = new PersistenceFailureException("Partition: " + partitionId + " of store: " + getName() + " does't exist");
      logger.error(e.getMessage(), e.getCause());
      throw e;
    }
    return partition;
  }

  public synchronized long getPartitionSizeInBytes(int partitionId) {
    AbstractStoragePartition partition = partitionList.get(partitionId);
    return partition != null ? partition.getPartitionSizeInBytes() : 0;
  }

  private static byte[] getPartitionMetadataKey(int partitionId) {
    return (PARTITION_METADATA_PREFIX + partitionId).getBytes();
  }

  private boolean metadataPartitionCreated() {
    return null != metadataPartition;
  }

  private void validateStoreName(StoragePartitionConfig storagePartitionConfig) {
    if (!storagePartitionConfig.getStoreName().equals(getName())) {
      throw new VeniceException("Store name in partition config: " + storagePartitionConfig.getStoreName() + " doesn't match current store engine: " + getName());
    }
  }
}
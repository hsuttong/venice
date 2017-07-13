package com.linkedin.venice.integration.utils;

import com.linkedin.venice.controller.Admin;
import com.linkedin.venice.controller.VeniceController;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.meta.Version;

import com.linkedin.venice.utils.PropertyBuilder;
import com.linkedin.venice.utils.VeniceProperties;
import java.io.File;

import static com.linkedin.venice.ConfigKeys.*;


/**
 * A wrapper for the {@link VeniceControllerWrapper}.
 * <p>
 * Calling close() will clean up the controller's data directory.
 */
public class VeniceControllerWrapper extends ProcessWrapper {

  public static final String SERVICE_NAME = "VeniceController";
  private final VeniceProperties config;
  private VeniceController service;
  private final int port;

  VeniceControllerWrapper(String serviceName, File dataDirectory, VeniceController service, int port, VeniceProperties config) {
    super(serviceName, dataDirectory);
    this.service = service;
    this.port = port;
    this.config = config;
  }

  static StatefulServiceProvider<VeniceControllerWrapper> generateService(String clusterName, String zkAddress,
      KafkaBrokerWrapper kafkaBrokerWrapper, boolean isParent, int replicaFactor, int partitionSize,
      long delayToReblanceMS, int minActiveReplica, VeniceControllerWrapper childController, VeniceProperties extraProps) {
    // TODO: Once the ZK address used by Controller and Kafka are decoupled, change this

    return (serviceName, port, dataDirectory) -> {
      VeniceProperties clusterProps =
          IntegrationTestUtils.getClusterProps(clusterName, dataDirectory, zkAddress, kafkaBrokerWrapper);

      int adminPort = IntegrationTestUtils.getFreePort();

      // TODO: Validate that these configs are all still used.
      // TODO: Centralize default config values in a single place
      PropertyBuilder builder = new PropertyBuilder()
          .put(clusterProps.toProperties())
          .put(extraProps.toProperties())
          .put(KAFKA_REPLICA_FACTOR, 1)
          .put(KAFKA_ZK_ADDRESS, kafkaBrokerWrapper.getZkAddress())
          .put(CONTROLLER_NAME, "venice-controller") // Why is this configurable?
          .put(DEFAULT_REPLICA_FACTOR, replicaFactor)
          .put(DEFAULT_NUMBER_OF_PARTITION, 1)
          .put(ADMIN_PORT, adminPort)
          .put(DEFAULT_MAX_NUMBER_OF_PARTITIONS, 10)
          .put(DEFAULT_PARTITION_SIZE, partitionSize)
          .put(TOPIC_MONITOR_POLL_INTERVAL_MS, 100)
          .put(CONTROLLER_PARENT_MODE, isParent)
          .put(DELAY_TO_REBALANCE_MS, delayToReblanceMS)
          .put(MIN_ACTIVE_REPLICA, minActiveReplica)
          .put(TOPIC_CREATION_THROTTLING_TIME_WINDOW_MS, 100);
      if (!extraProps.containsKey(ENABLE_TOPIC_REPLICATOR)){
          builder.put(ENABLE_TOPIC_REPLICATOR, false);
      }
      if (isParent) {
        // Parent controller needs config to route per-cluster requests such as job status
        // This dummy parent controller wont support such requests until we make this config configurable.
        builder.put(CHILD_CLUSTER_WHITELIST, "cluster1");
        builder.put(CHILD_CLUSTER_URL_PREFIX + ".cluster1", childController.getControllerUrl());
      }

      VeniceProperties props = builder.build();

      VeniceController veniceController = new VeniceController(props);
      return new VeniceControllerWrapper(serviceName, dataDirectory, veniceController, adminPort, props);
    };
  }

  @Override
  public String getHost() {
    return DEFAULT_HOST_NAME;
  }

  @Override
  public int getPort() {
    return port;
  }

  public String getControllerUrl() {
    return "http://" + getHost() + ":" + getPort();
  }

  @Override
  protected void internalStart()
      throws Exception {
    service.start();
  }

  @Override
  protected void internalStop()
      throws Exception {
    service.stop();
  }

  @Override
  protected void newProcess()
      throws Exception {
    service = new VeniceController(config);
  }

  /***
   * Sets a version to be active for a given store and version
   *
   * @param storeName
   * @param version
   */
  public void setActiveVersion(String clusterName, String storeName, int version) {
    ControllerClient.overrideSetActiveVersion(getControllerUrl(), clusterName, storeName, version);
  }

  /***
   * Set a version to be active, parsing store name and version number from a kafka topic name
   *
   * @param kafkaTopic
   */
  public void setActiveVersion(String clusterName, String kafkaTopic) {
    String storeName = Version.parseStoreFromKafkaTopicName(kafkaTopic);
    int version = Version.parseVersionFromKafkaTopicName(kafkaTopic);
    setActiveVersion(clusterName, storeName, version);
  }

  public boolean isMasterController(String clusterName) {
    Admin admin = service.getVeniceControllerService().getVeniceHelixAdmin();
    return admin.isMasterController(clusterName);
  }

  public Admin getVeniceAdmin() {
    return service.getVeniceControllerService().getVeniceHelixAdmin();
  }
}

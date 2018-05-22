package com.linkedin.venice.serialization;

import com.linkedin.venice.serialization.avro.ChunkedKeySuffixSerializer;
import com.linkedin.venice.storage.protocol.ChunkedKeySuffix;
import java.nio.ByteBuffer;


public class KeyWithChunkingSuffixSerializer {
  private static final String IGNORED_TOPIC_NAME = "ignored";
  private final ChunkedKeySuffixSerializer chunkedKeySuffixSerializer = new ChunkedKeySuffixSerializer();
  private final byte[] serializedNonChunkKeySuffix;

  public KeyWithChunkingSuffixSerializer() {
    ChunkedKeySuffix nonChunkKeySuffix = new ChunkedKeySuffix();
    nonChunkKeySuffix.isChunk = false;
    nonChunkKeySuffix.chunkId = null;
    this.serializedNonChunkKeySuffix = chunkedKeySuffixSerializer.serialize(IGNORED_TOPIC_NAME, nonChunkKeySuffix);
  }

  public byte[] serializeChunkedKey(byte[] key, ChunkedKeySuffix chunkedKeySuffix) {
    byte[] encodedChunkedKeySuffix = chunkedKeySuffixSerializer.serialize(IGNORED_TOPIC_NAME, chunkedKeySuffix);
    return serialize(key, encodedChunkedKeySuffix);
  }

  public byte[] serializeNonChunkedKey(byte[] key) {
    return serialize(key, serializedNonChunkKeySuffix);
  }

  private byte[] serialize(byte[] key, byte[] encodedChunkedKeySuffix) {
    ByteBuffer target = ByteBuffer.allocate(key.length + encodedChunkedKeySuffix.length);
    target.put(key);
    target.put(encodedChunkedKeySuffix);
    return target.array();
  }

  public byte[] serializeChunkedKey(ByteBuffer key, ChunkedKeySuffix chunkedKeySuffix) {
    byte[] encodedChunkedKeySuffix = chunkedKeySuffixSerializer.serialize(IGNORED_TOPIC_NAME, chunkedKeySuffix);
    return serialize(key, encodedChunkedKeySuffix);
  }

  public byte[] serializeNonChunkedKey(ByteBuffer key) {
    return serialize(key, serializedNonChunkKeySuffix);
  }

  private byte[] serialize(ByteBuffer key, byte[] encodedChunkedKeySuffix) {
    /**
     * Here will always allocate a new {@link ByteBuffer} to accommodate} the combination of key and chunked
     * key suffix since we don't know whether reusing the original {@link ByteBuffer} will cause any side effect
     * or not.
     *
     * Also this function won't change the position of the passed key {@link ByteBuffer}.
     */
    key.mark();
    ByteBuffer target = ByteBuffer.allocate(key.remaining() + encodedChunkedKeySuffix.length);
    target.put(key);
    key.reset();
    target.put(encodedChunkedKeySuffix);
    return target.array();
  }
}

package com.linkedin.davinci.kafka.consumer;

import com.linkedin.venice.exceptions.VeniceException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;
import org.testng.Assert;
import org.testng.annotations.Test;


public class KeyLevelLocksManagerTest {
  @Test
  public void testSameLockReturnedForSameKeyBytes() {
    KeyLevelLocksManager keyLevelLocksManager = new KeyLevelLocksManager("testStoreVersion", 4);
    byte[] rawKeyBytes = {'a', 'b', 'c'};
    ReentrantLock lock1 = keyLevelLocksManager.acquireLockByKey(ByteBuffer.wrap(rawKeyBytes));
    byte[] sameRawKeyBytes = "abc".getBytes(StandardCharsets.UTF_8);
    ReentrantLock lock2 = keyLevelLocksManager.acquireLockByKey(ByteBuffer.wrap(sameRawKeyBytes));
    Assert.assertEquals(lock1, lock2);
  }

  @Test
  public void testDifferentLockReturnedForDifferentKeyBytes() {
    KeyLevelLocksManager keyLevelLocksManager = new KeyLevelLocksManager("testStoreVersion", 4);
    byte[] rawKeyBytes = {'a', 'b', 'c'};
    ReentrantLock lock1 = keyLevelLocksManager.acquireLockByKey(ByteBuffer.wrap(rawKeyBytes));
    byte[] sameRawKeyBytes = {'x', 'y', 'z'};
    ReentrantLock lock2 = keyLevelLocksManager.acquireLockByKey(ByteBuffer.wrap(sameRawKeyBytes));
    Assert.assertNotEquals(lock1, lock2);
  }

  @Test
  public void testFreeLocksPoolAndKeyToLockMapAreMaintainedCorrectly() {
    int poolSize = 4;
    KeyLevelLocksManager keyLevelLocksManager = new KeyLevelLocksManager("testStoreVersion", poolSize);

    /**
     * If different users acquire locks for the same key at the same time, same lock should be returned
     */
    byte[] rawKeyBytes = {'a', 'b', 'c'};
    ByteBuffer key1 = ByteBuffer.wrap(rawKeyBytes);
    keyLevelLocksManager.acquireLockByKey(key1);
    Assert.assertEquals(keyLevelLocksManager.getLocksPool().size(), poolSize - 1);

    // Same lock will be returned, shouldn't poll new lock from the free locks pool
    keyLevelLocksManager.acquireLockByKey(key1);
    Assert.assertEquals(keyLevelLocksManager.getLocksPool().size(), poolSize - 1);

    // There is another user for this lock, so cannot return the lock back to the free locks pool yet
    keyLevelLocksManager.releaseLock(key1);
    Assert.assertEquals(keyLevelLocksManager.getLocksPool().size(), poolSize - 1);

    // All users have released the lock, the lock should be returned back to the pool
    keyLevelLocksManager.releaseLock(key1);
    Assert.assertEquals(keyLevelLocksManager.getLocksPool().size(), poolSize);

    /**
     * If different users acquire locks for different keys at the same time, different locks should be returned
     */
    byte[] rawKeyBytes2 = {'x', 'y', 'z'};
    ByteBuffer key2 = ByteBuffer.wrap(rawKeyBytes2);
    keyLevelLocksManager.acquireLockByKey(key1);
    Assert.assertEquals(keyLevelLocksManager.getLocksPool().size(), poolSize - 1);

    // Different lock will be returned for key2
    keyLevelLocksManager.acquireLockByKey(key2);
    Assert.assertEquals(keyLevelLocksManager.getLocksPool().size(), poolSize - 2);

    // No more user for the first lock, return it back to the free locks pool
    keyLevelLocksManager.releaseLock(key1);
    Assert.assertEquals(keyLevelLocksManager.getLocksPool().size(), poolSize - 1);

    // No more user for the second lock, return it back to the free locks pool
    keyLevelLocksManager.releaseLock(key2);
    Assert.assertEquals(keyLevelLocksManager.getLocksPool().size(), poolSize);

    try {
      keyLevelLocksManager.releaseLock(key2);
      Assert.fail("The lock reference count is not maintained correctly.");
    } catch (VeniceException e) {
      // expected; if a lock is only acquired by X number of users, only X number of releases should happen
    }
  }
}

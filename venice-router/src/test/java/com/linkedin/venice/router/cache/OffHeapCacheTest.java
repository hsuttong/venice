package com.linkedin.venice.router.cache;

import java.nio.ByteBuffer;
import java.util.Optional;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;


public class OffHeapCacheTest {

  @DataProvider(name = "cacheEvictionProvider")
  public Object[][] getCacheEvictionProvider() {
    return new Object[][] {
        new Object[] {CacheEviction.LRU},
        new Object[] {CacheEviction.W_TINY_LFU}
    };
  }

  @Test(dataProvider = "cacheEvictionProvider")
  public void testCache(CacheEviction cacheEviction) {
    OffHeapCache<RouterCache.CacheKey, RouterCache.CacheValue> cache = new OffHeapCache<>(1024 * 1024, 4, 256,
        new RouterCache.CacheKeySerializer(), new RouterCache.CacheValueSerializer(), cacheEviction);
    RouterCache.CacheKey cacheKey1 = new RouterCache.CacheKey(1, 1, ByteBuffer.wrap("key1".getBytes()));
    RouterCache.CacheValue cacheValue1 = new RouterCache.CacheValue("value1".getBytes(), 1);
    RouterCache.CacheKey cacheKey2 = new RouterCache.CacheKey(2, 1, ByteBuffer.wrap("key".getBytes()));

    cache.put(cacheKey1, Optional.of(cacheValue1));
    cache.put(cacheKey2, Optional.empty());

    Assert.assertEquals(cacheValue1, cache.get(cacheKey1).get());
    Assert.assertFalse(cache.get(cacheKey2).isPresent());
  }
}

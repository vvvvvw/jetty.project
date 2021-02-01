//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntFunction;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A ByteBuffer pool where ByteBuffers are held in queues that are held in array elements.</p>
 * <p>Given a capacity {@code factor} of 1024, the first array element holds a queue of ByteBuffers
 * each of capacity 1024, the second array element holds a queue of ByteBuffers each of capacity
 * 2048, and so on.</p>
 */
//ByteBufferPool 的实现类
@ManagedObject
public class ArrayByteBufferPool extends AbstractByteBufferPool
{
    private static final Logger LOG = LoggerFactory.getLogger(MappedByteBufferPool.class);

    //最小size的Buffer长度
    private final int _minCapacity;
    //用不同的Bucket来持有不同size的ByteBuffer对象,同一个Bucket中的ByteBuffer的size是一样的（数组长度=maxCapacity/factor）
    private final ByteBufferPool.Bucket[] _direct;
    private final ByteBufferPool.Bucket[] _indirect;

    /**
     * Creates a new ArrayByteBufferPool with a default configuration.
     */
    public ArrayByteBufferPool()
    {
        this(-1, -1, -1);
    }

    /**
     * Creates a new ArrayByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     */
    public ArrayByteBufferPool(int minCapacity, int factor, int maxCapacity)
    {
        this(minCapacity, factor, maxCapacity, -1, -1, -1);
    }

    /**
     * Creates a new ArrayByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     * @param maxQueueLength the maximum ByteBuffer queue length
     */
    public ArrayByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxQueueLength)
    {
        this(minCapacity, factor, maxCapacity, maxQueueLength, -1, -1);
    }

    /**
     * Creates a new ArrayByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     * @param maxQueueLength the maximum ByteBuffer queue length
     * @param maxHeapMemory the max heap memory in bytes
     * @param maxDirectMemory the max direct memory in bytes
     */
    public ArrayByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxQueueLength, long maxHeapMemory, long maxDirectMemory)
    {
        super(factor, maxQueueLength, maxHeapMemory, maxDirectMemory);

        factor = getCapacityFactor();
        if (minCapacity <= 0) //ByteBuffer的最小长度
            minCapacity = 0;
        if (maxCapacity <= 0)
            maxCapacity = 64 * 1024;
        if ((maxCapacity % factor) != 0 || factor >= maxCapacity)
            throw new IllegalArgumentException("The capacity factor must be a divisor of maxCapacity");
        _minCapacity = minCapacity;

        int length = maxCapacity / factor;
        //创建maxCapacity / factor个桶,包含直接内存的与heap的
        _direct = new ByteBufferPool.Bucket[length];
        _indirect = new ByteBufferPool.Bucket[length];
    }

    //分配Buffer：找到对应的bucket，没有的话创建一个bucket，并调用bucket的方法分配一个ByteBuffer（从缓存的链表头部中获取，如果没有的话创建一个，在释放的时候会还到缓存链表中）
    @Override
    public ByteBuffer acquire(int size, boolean direct)
    {
        int capacity = size < _minCapacity ? size : (bucketFor(size) + 1) * getCapacityFactor();
        ByteBufferPool.Bucket bucket = bucketFor(size, direct, null);
        if (bucket == null)
            return newByteBuffer(capacity, direct);
        //这里其实调用了Deque的poll方法
        ByteBuffer buffer = bucket.acquire();
        if (buffer == null)
            return newByteBuffer(capacity, direct);
        decrementMemory(buffer);
        return buffer;
    }

    //释放Buffer（释放到链表的头部）
    @Override
    public void release(ByteBuffer buffer)
    {
        if (buffer == null)
            return;

        int capacity = buffer.capacity();
        // Validate that this buffer is from this pool.
        if ((capacity % getCapacityFactor()) != 0)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ByteBuffer {} does not belong to this pool, discarding it", BufferUtil.toDetailString(buffer));
            return;
        }

        boolean direct = buffer.isDirect();
        //找到对应的桶
        ByteBufferPool.Bucket bucket = bucketFor(capacity, direct, this::newBucket);
        if (bucket != null)
        {
            //这里调用了Deque的offerFirst方法
            bucket.release(buffer);
            incrementMemory(buffer);
            releaseExcessMemory(direct, this::clearOldestBucket);
        }
    }

    private Bucket newBucket(int key)
    {
        return new Bucket(key * getCapacityFactor(), getMaxQueueLength());
    }

    @Override
    public void clear()
    {
        super.clear();
        for (int i = 0; i < _direct.length; ++i)
        {
            Bucket bucket = _direct[i];
            if (bucket != null)
                bucket.clear();
            _direct[i] = null;
            bucket = _indirect[i];
            if (bucket != null)
                bucket.clear();
            _indirect[i] = null;
        }
    }

    private void clearOldestBucket(boolean direct)
    {
        long oldest = Long.MAX_VALUE;
        int index = -1;
        Bucket[] buckets = bucketsFor(direct);
        for (int i = 0; i < buckets.length; ++i)
        {
            Bucket bucket = buckets[i];
            if (bucket == null)
                continue;
            long lastUpdate = bucket.getLastUpdate();
            if (lastUpdate < oldest)
            {
                oldest = lastUpdate;
                index = i;
            }
        }
        if (index >= 0)
        {
            Bucket bucket = buckets[index];
            buckets[index] = null;
            // The same bucket may be concurrently
            // removed, so we need this null guard.
            if (bucket != null)
                bucket.clear(this::decrementMemory);
        }
    }

    private int bucketFor(int capacity)
    {
        return (capacity - 1) / getCapacityFactor();
    }

    private ByteBufferPool.Bucket bucketFor(int capacity, boolean direct, IntFunction<Bucket> newBucket)
    {
        if (capacity < _minCapacity)
            return null;
        int b = bucketFor(capacity);
        if (b >= _direct.length)
            return null;
        Bucket[] buckets = bucketsFor(direct);
        Bucket bucket = buckets[b];
        if (bucket == null && newBucket != null)
            buckets[b] = bucket = newBucket.apply(b + 1);
        return bucket;
    }

    @ManagedAttribute("The number of pooled direct ByteBuffers")
    public long getDirectByteBufferCount()
    {
        return getByteBufferCount(true);
    }

    @ManagedAttribute("The number of pooled heap ByteBuffers")
    public long getHeapByteBufferCount()
    {
        return getByteBufferCount(false);
    }

    private long getByteBufferCount(boolean direct)
    {
        return Arrays.stream(bucketsFor(direct))
            .filter(Objects::nonNull)
            .mapToLong(Bucket::size)
            .sum();
    }

    // Package local for testing
    ByteBufferPool.Bucket[] bucketsFor(boolean direct)
    {
        return direct ? _direct : _indirect;
    }
}

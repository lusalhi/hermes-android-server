package com.hermes.node.engine

/**
 * Thread-safe fixed-capacity circular buffer with O(1) append, snapshot retrieval,
 * and FIFO eviction when capacity is reached.
 */
class RingBuffer<T>(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "Capacity must be greater than 0, but was $capacity" }
    }

    private val lock = Any()
    private val buffer = arrayOfNulls<Any?>(capacity)
    private var head = 0
    private var count = 0

    val size: Int
        get() = synchronized(lock) { count }

    val isFull: Boolean
        get() = synchronized(lock) { count == capacity }

    val isEmpty: Boolean
        get() = synchronized(lock) { count == 0 }

    /**
     * Appends an item to the buffer in O(1) time.
     * If the buffer is full, the oldest item is evicted (overwritten).
     */
    fun add(item: T) = synchronized(lock) {
        if (count < capacity) {
            val tail = (head + count) % capacity
            buffer[tail] = item
            count++
        } else {
            // Buffer is full: overwrite oldest element at head and advance head
            buffer[head] = item
            head = (head + 1) % capacity
        }
    }

    /**
     * Returns a thread-safe snapshot of all elements in FIFO order (oldest to newest).
     */
    @Suppress("UNCHECKED_CAST")
    fun toList(): List<T> = synchronized(lock) {
        val list = ArrayList<T>(count)
        for (i in 0 until count) {
            list.add(buffer[(head + i) % capacity] as T)
        }
        list
    }

    /**
     * Clears all elements from the buffer and releases object references to prevent memory leaks.
     */
    fun clear() = synchronized(lock) {
        buffer.fill(null)
        head = 0
        count = 0
    }

    /**
     * Returns element at index in FIFO order (0 = oldest, size - 1 = newest).
     */
    @Suppress("UNCHECKED_CAST")
    operator fun get(index: Int): T = synchronized(lock) {
        if (index < 0 || index >= count) {
            throw IndexOutOfBoundsException("Index $index out of bounds for size $count")
        }
        buffer[(head + index) % capacity] as T
    }

    companion object {
        const val DEFAULT_CAPACITY = 2000
    }
}

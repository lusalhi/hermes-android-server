package com.hermes.node.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RingBufferTest {

    @Test
    fun initialState_emptyAndDefaultCapacity() {
        val buffer = RingBuffer<String>()
        assertEquals(RingBuffer.DEFAULT_CAPACITY, buffer.capacity)
        assertEquals(2000, buffer.capacity)
        assertEquals(0, buffer.size)
        assertTrue(buffer.isEmpty)
        assertFalse(buffer.isFull)
        assertTrue(buffer.toList().isEmpty())
    }

    @Test
    fun customCapacity_initialState() {
        val buffer = RingBuffer<Int>(capacity = 10)
        assertEquals(10, buffer.capacity)
        assertEquals(0, buffer.size)
        assertTrue(buffer.isEmpty)
        assertFalse(buffer.isFull)
    }

    @Test
    fun invalidCapacity_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            RingBuffer<String>(capacity = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RingBuffer<String>(capacity = -5)
        }
    }

    @Test
    fun add_singleElement_andMultipleElements() {
        val buffer = RingBuffer<String>(capacity = 5)

        buffer.add("first")
        assertEquals(1, buffer.size)
        assertFalse(buffer.isEmpty)
        assertFalse(buffer.isFull)
        assertEquals(listOf("first"), buffer.toList())
        assertEquals("first", buffer[0])

        buffer.add("second")
        buffer.add("third")
        assertEquals(3, buffer.size)
        assertEquals(listOf("first", "second", "third"), buffer.toList())
        assertEquals("first", buffer[0])
        assertEquals("second", buffer[1])
        assertEquals("third", buffer[2])
    }

    @Test
    fun add_fillingToExactCapacity() {
        val buffer = RingBuffer<Int>(capacity = 3)
        buffer.add(1)
        buffer.add(2)
        buffer.add(3)

        assertEquals(3, buffer.size)
        assertTrue(buffer.isFull)
        assertFalse(buffer.isEmpty)
        assertEquals(listOf(1, 2, 3), buffer.toList())
    }

    @Test
    fun add_whenFull_overwritesOldestElementsInFIFOOrder() {
        val buffer = RingBuffer<Int>(capacity = 3)
        buffer.add(1)
        buffer.add(2)
        buffer.add(3)
        assertEquals(listOf(1, 2, 3), buffer.toList())

        // 4th add: evicts 1, retains 2, 3, 4
        buffer.add(4)
        assertEquals(3, buffer.size)
        assertTrue(buffer.isFull)
        assertEquals(listOf(2, 3, 4), buffer.toList())
        assertEquals(2, buffer[0])
        assertEquals(3, buffer[1])
        assertEquals(4, buffer[2])

        // 5th add: evicts 2, retains 3, 4, 5
        buffer.add(5)
        assertEquals(3, buffer.size)
        assertTrue(buffer.isFull)
        assertEquals(listOf(3, 4, 5), buffer.toList())

        // 6th add: evicts 3, retains 4, 5, 6
        buffer.add(6)
        assertEquals(3, buffer.size)
        assertTrue(buffer.isFull)
        assertEquals(listOf(4, 5, 6), buffer.toList())
    }

    @Test
    fun overflowCapacity_2500LinesOnDefaultCapacity() {
        val buffer = RingBuffer<String>(capacity = 2000)

        for (i in 0 until 2500) {
            buffer.add("Line $i")
        }

        assertEquals(2000, buffer.size)
        assertTrue(buffer.isFull)

        val list = buffer.toList()
        assertEquals(2000, list.size)
        assertEquals("Line 500", list.first())
        assertEquals("Line 2499", list.last())
        assertEquals("Line 500", buffer[0])
        assertEquals("Line 2499", buffer[1999])
    }

    @Test
    fun get_operator_boundsChecks() {
        val buffer = RingBuffer<String>(capacity = 3)
        buffer.add("A")
        buffer.add("B")

        assertEquals("A", buffer[0])
        assertEquals("B", buffer[1])

        assertThrows(IndexOutOfBoundsException::class.java) {
            buffer[-1]
        }
        assertThrows(IndexOutOfBoundsException::class.java) {
            buffer[2]
        }
    }

    @Test
    fun clear_resetsBufferAndAllowsReinsertion() {
        val buffer = RingBuffer<String>(capacity = 3)
        buffer.add("A")
        buffer.add("B")
        buffer.add("C")
        buffer.add("D") // evicted A, contains [B, C, D]

        assertEquals(3, buffer.size)
        assertTrue(buffer.isFull)

        buffer.clear()

        assertEquals(0, buffer.size)
        assertTrue(buffer.isEmpty)
        assertFalse(buffer.isFull)
        assertTrue(buffer.toList().isEmpty())

        // Add new elements after clear
        buffer.add("X")
        buffer.add("Y")

        assertEquals(2, buffer.size)
        assertEquals(listOf("X", "Y"), buffer.toList())
        assertEquals("X", buffer[0])
        assertEquals("Y", buffer[1])
    }

    @Test
    fun concurrentAddAndToList_isThreadSafe() {
        val capacity = 500
        val buffer = RingBuffer<Int>(capacity = capacity)
        val threadCount = 8
        val itemsPerThread = 1000
        val executor = Executors.newFixedThreadPool(threadCount + 4)
        val latch = CountDownLatch(threadCount)
        val exceptionCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            val threadId = t
            executor.submit {
                try {
                    for (i in 0 until itemsPerThread) {
                        buffer.add(threadId * itemsPerThread + i)
                    }
                } catch (e: Exception) {
                    exceptionCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        // Concurrent readers
        for (r in 0 until 4) {
            executor.submit {
                while (latch.count > 0) {
                    try {
                        val snapshot = buffer.toList()
                        assertTrue(snapshot.size <= capacity)
                        Thread.sleep(1)
                    } catch (e: Exception) {
                        exceptionCount.incrementAndGet()
                    }
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals(0, exceptionCount.get())
        assertEquals(capacity, buffer.size)
        assertTrue(buffer.isFull)
        assertEquals(capacity, buffer.toList().size)
    }
}

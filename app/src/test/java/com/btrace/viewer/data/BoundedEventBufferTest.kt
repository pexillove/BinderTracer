package com.btrace.viewer.data

import com.btrace.viewer.model.BinderEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedEventBufferTest {

    private fun mockEvent(id: Long): BinderEvent =
        BinderEvent.createMock(
            interfaceName = "test.IFoo",
            methodName = "m",
            callerPackage = "test",
            uid = 100
        ).copy(id = id)

    @Test
    fun `add up to capacity keeps all`() {
        val buf = BoundedEventBuffer(capacity = 3)
        buf.add(mockEvent(1))
        buf.add(mockEvent(2))
        buf.add(mockEvent(3))
        assertEquals(listOf(1L, 2L, 3L), buf.snapshot().map { it.id })
        assertEquals(3, buf.size())
    }

    @Test
    fun `add beyond capacity evicts oldest fifo`() {
        val buf = BoundedEventBuffer(capacity = 3)
        listOf(1L, 2L, 3L, 4L, 5L).forEach { buf.add(mockEvent(it)) }
        assertEquals(listOf(3L, 4L, 5L), buf.snapshot().map { it.id })
        assertEquals(3, buf.size())
    }

    @Test
    fun `setCapacity smaller trims oldest`() {
        val buf = BoundedEventBuffer(capacity = 5)
        listOf(1L, 2L, 3L, 4L, 5L).forEach { buf.add(mockEvent(it)) }

        buf.setCapacity(3)

        assertEquals(listOf(3L, 4L, 5L), buf.snapshot().map { it.id })
        assertEquals(3, buf.size())
    }

    @Test
    fun `setCapacity larger keeps existing and allows growth`() {
        val buf = BoundedEventBuffer(capacity = 3)
        listOf(1L, 2L, 3L).forEach { buf.add(mockEvent(it)) }

        buf.setCapacity(5)
        listOf(4L, 5L, 6L).forEach { buf.add(mockEvent(it)) }

        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), buf.snapshot().map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setCapacity rejects non-positive`() {
        BoundedEventBuffer(capacity = 5).setCapacity(0)
    }

    @Test
    fun `findById returns event when present and null when evicted`() {
        val buf = BoundedEventBuffer(capacity = 2)
        buf.add(mockEvent(10))
        buf.add(mockEvent(20))
        buf.add(mockEvent(30))   // evicts id=10
        assertEquals(20L, buf.findById(20)?.id)
        assertEquals(30L, buf.findById(30)?.id)
        assertEquals(null, buf.findById(10))
    }

    @Test
    fun `clear empties everything`() {
        val buf = BoundedEventBuffer(capacity = 3)
        buf.add(mockEvent(1))
        buf.add(mockEvent(2))
        buf.clear()
        assertEquals(0, buf.size())
        assertEquals(emptyList<BinderEvent>(), buf.snapshot())
        assertEquals(null, buf.findById(1))
    }
}

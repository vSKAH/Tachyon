package tech.skworks.tachyon.core.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentRingBufferTest {

    @Test
    @DisplayName("Verify power-of-two capacity rounding and boundary validation")
    void testCapacityRoundingAndValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentRingBuffer<String>(0));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentRingBuffer<String>(-10));
        assertThrows(IllegalArgumentException.class, () -> new ConcurrentRingBuffer<String>((1 << 30) + 1));

        final var buf1 = new ConcurrentRingBuffer<String>(1);
        assertEquals(1, buf1.capacity());

        final var buf15 = new ConcurrentRingBuffer<String>(15);
        assertEquals(16, buf15.capacity());

        final var buf16 = new ConcurrentRingBuffer<String>(16);
        assertEquals(16, buf16.capacity());

        final var buf1000 = new ConcurrentRingBuffer<String>(1000);
        assertEquals(1024, buf1000.capacity());
    }

    @Test
    @DisplayName("Verify basic offer, drain, drainTo, and emptiness")
    void testBasicOfferAndDrain() {
        final var buffer = new ConcurrentRingBuffer<String>(8);
        assertTrue(buffer.isEmpty());
        assertEquals(0, buffer.size());
        assertEquals(0L, buffer.droppedCount());

        assertThrows(NullPointerException.class, () -> buffer.offer(null));

        assertTrue(buffer.offer("alpha"));
        assertTrue(buffer.offer("beta"));
        assertTrue(buffer.offer("gamma"));

        assertFalse(buffer.isEmpty());
        assertEquals(3, buffer.size());

        final List<String> batch1 = buffer.drain(2);
        assertEquals(2, batch1.size());
        assertEquals("alpha", batch1.get(0));
        assertEquals("beta", batch1.get(1));
        assertEquals(1, buffer.size());

        final List<String> batch2 = new ArrayList<>();
        final int drained = buffer.drainTo(batch2::add, 5);
        assertEquals(1, drained);
        assertEquals(1, batch2.size());
        assertEquals("gamma", batch2.getFirst());

        assertTrue(buffer.isEmpty());
        assertEquals(0, buffer.size());

        assertEquals(0, buffer.drainTo(batch2::add, 5));
        assertTrue(buffer.drain(5).isEmpty());
    }

    @Test
    @DisplayName("Verify saturation policy and droppedCount tracking")
    void testSaturationAndDropPolicy() {
        final var buffer = new ConcurrentRingBuffer<Integer>(4);
        assertEquals(4, buffer.capacity());

        assertTrue(buffer.offer(1));
        assertTrue(buffer.offer(2));
        assertTrue(buffer.offer(3));
        assertTrue(buffer.offer(4));
        assertEquals(4, buffer.size());

        // Buffer is full: further offers must be cleanly dropped
        assertFalse(buffer.offer(5));
        assertFalse(buffer.offer(6));
        assertEquals(2L, buffer.droppedCount());
        assertEquals(4, buffer.size());

        // Drain 2 elements
        final List<Integer> drained = buffer.drain(2);
        assertEquals(List.of(1, 2), drained);
        assertEquals(2, buffer.size());

        // Now slots are freed, offer should succeed
        assertTrue(buffer.offer(7));
        assertTrue(buffer.offer(8));
        assertEquals(4, buffer.size());
        assertFalse(buffer.offer(9));
        assertEquals(3L, buffer.droppedCount());

        final List<Integer> remaining = buffer.drainAll();
        assertEquals(List.of(3, 4, 7, 8), remaining);
        assertTrue(buffer.isEmpty());
    }

    @Test
    @DisplayName("Verify clear discards all pending elements")
    void testClear() {
        final var buffer = new ConcurrentRingBuffer<String>(8);
        buffer.offer("a");
        buffer.offer("b");
        buffer.offer("c");
        assertEquals(3, buffer.size());

        buffer.clear();
        assertTrue(buffer.isEmpty());
        assertEquals(0, buffer.size());
    }

    @Test
    @DisplayName("Verify drainTo corner cases with zero or negative limits")
    void testDrainToCornerCases() {
        final var buffer = new ConcurrentRingBuffer<String>(8);
        buffer.offer("x");

        assertEquals(0, buffer.drainTo(_ -> {}, 0));
        assertEquals(0, buffer.drainTo(_ -> {}, -10));
        assertTrue(buffer.drain(0).isEmpty());
        assertTrue(buffer.drain(-5).isEmpty());

        assertEquals(1, buffer.size());
    }

    private record ProducerMsg(int threadId, int seq) {}

    @Test
    @DisplayName("Verify high-concurrency MPSC stress without drops: 8 producers, 1 consumer, strict sequential FIFO")
    void testConcurrentMpscStressNoDrops() throws Exception {
        final int producerCount = 8;
        final int messagesPerProducer = 5_000;
        final int totalMessages = producerCount * messagesPerProducer;
        // Capacity 65536 is strictly greater than 40,000 total messages
        final var buffer = new ConcurrentRingBuffer<ProducerMsg>(65_536);

        final ExecutorService producers = Executors.newFixedThreadPool(producerCount);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch producersDone = new CountDownLatch(producerCount);

        for (int t = 0; t < producerCount; t++) {
            final int threadId = t;
            producers.submit(() -> {
                try {
                    startLatch.await();
                    for (int s = 0; s < messagesPerProducer; s++) {
                        final var msg = new ProducerMsg(threadId, s);
                        final boolean offered = buffer.offer(msg);
                        assertTrue(offered, "Buffer should have sufficient capacity");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersDone.countDown();
                }
            });
        }

        final int[] lastSeenSeq = new int[producerCount];
        for (int i = 0; i < producerCount; i++) {
            lastSeenSeq[i] = -1;
        }

        final AtomicInteger totalConsumed = new AtomicInteger(0);
        final AtomicBoolean consumerRunning = new AtomicBoolean(true);

        final CompletableFuture<Void> consumerFuture = CompletableFuture.runAsync(() -> {
            while (consumerRunning.get() || !buffer.isEmpty()) {
                final int drained = buffer.drainTo(msg -> {
                    final int expected = lastSeenSeq[msg.threadId()] + 1;
                    assertEquals(expected, msg.seq(), "Out of order message from producer " + msg.threadId());
                    lastSeenSeq[msg.threadId()] = msg.seq();
                    totalConsumed.incrementAndGet();
                }, 256);

                if (drained == 0) {
                    Thread.onSpinWait();
                }
            }
        });

        // Release producers
        startLatch.countDown();

        assertTrue(producersDone.await(15, TimeUnit.SECONDS), "Producers timed out");
        producers.shutdown();
        assertTrue(producers.awaitTermination(5, TimeUnit.SECONDS));

        // Signal consumer that producers are done and wait for buffer to drain
        consumerRunning.set(false);
        consumerFuture.get(10, TimeUnit.SECONDS);

        assertEquals(totalMessages, totalConsumed.get());
        assertTrue(buffer.isEmpty());
        assertEquals(0, buffer.size());
        assertEquals(0L, buffer.droppedCount());

        for (int t = 0; t < producerCount; t++) {
            assertEquals(messagesPerProducer - 1, lastSeenSeq[t]);
        }
    }

    @Test
    @DisplayName("Verify concurrent MPSC with saturation: drop tracking and strictly increasing order")
    void testConcurrentMpscWithSaturationDrops() throws Exception {
        final int producerCount = 4;
        final int messagesPerProducer = 2_000;
        final int totalAttempted = producerCount * messagesPerProducer;
        // Small buffer to guarantee high saturation and drop events
        final var buffer = new ConcurrentRingBuffer<ProducerMsg>(256);

        final ExecutorService producers = Executors.newFixedThreadPool(producerCount);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch producersDone = new CountDownLatch(producerCount);

        final LongAdder acceptedOffers = new LongAdder();

        for (int t = 0; t < producerCount; t++) {
            final int threadId = t;
            producers.submit(() -> {
                try {
                    startLatch.await();
                    for (int s = 0; s < messagesPerProducer; s++) {
                        final var msg = new ProducerMsg(threadId, s);
                        if (buffer.offer(msg)) {
                            acceptedOffers.increment();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersDone.countDown();
                }
            });
        }

        final int[] lastSeenSeq = new int[producerCount];
        for (int i = 0; i < producerCount; i++) {
            lastSeenSeq[i] = -1;
        }

        final AtomicInteger totalConsumed = new AtomicInteger(0);
        final AtomicBoolean consumerRunning = new AtomicBoolean(true);

        final CompletableFuture<Void> consumerFuture = CompletableFuture.runAsync(() -> {
            while (consumerRunning.get() || !buffer.isEmpty()) {
                final int drained = buffer.drainTo(msg -> {
                    // Because drops occurred, sequence must be strictly increasing
                    assertTrue(msg.seq() > lastSeenSeq[msg.threadId()],
                            "Out of order or duplicate message from producer " + msg.threadId());
                    lastSeenSeq[msg.threadId()] = msg.seq();
                    totalConsumed.incrementAndGet();
                }, 64);

                if (drained == 0) {
                    Thread.onSpinWait();
                }
            }
        });

        startLatch.countDown();
        assertTrue(producersDone.await(15, TimeUnit.SECONDS), "Producers timed out");
        producers.shutdown();
        assertTrue(producers.awaitTermination(5, TimeUnit.SECONDS));

        consumerRunning.set(false);
        consumerFuture.get(10, TimeUnit.SECONDS);

        // Crucial invariant: totalAttempted == acceptedOffers + droppedCount
        assertEquals(totalAttempted, acceptedOffers.sum() + buffer.droppedCount());
        // All accepted offers must have been consumed
        assertEquals(acceptedOffers.sum(), totalConsumed.get());
        assertTrue(buffer.droppedCount() > 0, "Expected saturation drops to have occurred");
        assertTrue(buffer.isEmpty());
    }
}

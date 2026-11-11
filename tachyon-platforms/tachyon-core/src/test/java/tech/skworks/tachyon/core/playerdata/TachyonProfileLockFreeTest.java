package tech.skworks.tachyon.core.playerdata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.skworks.tachyon.api.component.ComponentKey;
import tech.skworks.tachyon.api.component.ComponentNamespace;
import tech.skworks.tachyon.core.component.ComponentRegistryImpl;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TachyonProfileLockFreeTest {

    private record TestCoins(long amount) {}
    private record TestStats(int kills, int deaths) {}

    private ComponentRegistryImpl registry;
    private ComponentKey<TestCoins> coinsKey;
    private ComponentKey<TestStats> statsKey;
    private TachyonProfileImpl profile;

    @BeforeEach
    void setUp() {
        registry = new ComponentRegistryImpl();
        coinsKey = registry.registerKey(ComponentNamespace.of("test", "coins"), TestCoins.class, () -> new TestCoins(0L));
        statsKey = registry.registerKey(ComponentNamespace.of("test", "stats"), TestStats.class, () -> new TestStats(0, 0));
        profile = new TachyonProfileImpl(UUID.randomUUID(), registry);
    }

    @Test
    @DisplayName("Verify basic getComponent, setComponent and dirty tracking")
    void testBasicOperations() {
        assertFalse(profile.hasPendingChanges());

        profile.setComponent(coinsKey, new TestCoins(100L));
        assertTrue(profile.hasPendingChanges());

        final TestCoins coins = profile.getComponent(coinsKey);
        assertNotNull(coins);
        assertEquals(100L, coins.amount());
        assertEquals(1L, profile.getVersion(coinsKey));
    }

    @Test
    @DisplayName("Verify atomic computeComponent under heavy multi-threaded contention")
    void testConcurrentCompute() throws InterruptedException {
        final int threads = 8;
        final int incrementsPerThread = 1_000;
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(threads);

        profile.setComponent(coinsKey, new TestCoins(0L));

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < incrementsPerThread; j++) {
                        profile.computeComponent(coinsKey, current -> new TestCoins((current != null ? current.amount() : 0L) + 1L));
                    }
                } catch (Exception e) {
                    fail(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        final TestCoins finalCoins = profile.getComponent(coinsKey);
        assertNotNull(finalCoins);
        assertEquals(threads * incrementsPerThread, finalCoins.amount());
        // 1 initial set + 8000 increments = version 8001
        assertEquals((threads * incrementsPerThread) + 1L, profile.getVersion(coinsKey));
    }

    @Test
    @DisplayName("Verify tombstone deletion records monotonic version and delivers removal in snapshot")
    void testTombstoneDeletion() {
        profile.setComponent(coinsKey, new TestCoins(50L));
        assertEquals(1L, profile.getVersion(coinsKey));

        profile.removeComponent(coinsKey);
        assertTrue(profile.hasPendingChanges());
        assertEquals(2L, profile.getVersion(coinsKey));
        assertNull(profile.getComponent(coinsKey));

        final ProfileFlushBatch snapshot = profile.captureFlushBatch();
        assertEquals(1, snapshot.slots().size());
        final ComponentSlot slot = snapshot.slots().getFirst();
        assertTrue(slot.isDeleted());
        assertNull(slot.data());
        assertEquals(2L, slot.version());
    }

    @Test
    @DisplayName("Verify Lossless Guarantee: concurrent mutation during save retains dirty bit")
    void testLosslessHighWaterMark() {
        // T0: Initial set (Version 1)
        profile.setComponent(coinsKey, new TestCoins(100L));
        assertTrue(profile.hasPendingChanges());

        // T1: Capture snapshot (Version 1)
        final ProfileFlushBatch snapshot = profile.captureFlushBatch();
        assertEquals(1, snapshot.slots().size());
        assertEquals(1L, snapshot.slots().getFirst().version());

        // T2: Concurrent gameplay mutation while save is in-flight (Version 2)
        profile.setComponent(coinsKey, new TestCoins(200L));
        assertEquals(2L, profile.getVersion(coinsKey));

        // T3: Remote ACK arrives for snapshot (Version 1)
        profile.confirmFlush(snapshot);

        // Verification: Dirty bit MUST remain set because Version 2 was not saved!
        assertTrue(profile.hasPendingChanges(), "Dirty bit must remain set when concurrent mutation occurs!");

        // T4: Next capture captures Version 2
        final ProfileFlushBatch nextSnapshot = profile.captureFlushBatch();
        assertEquals(1, nextSnapshot.slots().size());
        assertEquals(2L, nextSnapshot.slots().getFirst().version());

        // T5: Remote ACK arrives for Version 2
        profile.confirmFlush(nextSnapshot);
        assertFalse(profile.hasPendingChanges(), "Profile must be clean after all versions confirmed!");
    }
}

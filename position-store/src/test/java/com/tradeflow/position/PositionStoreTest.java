package com.tradeflow.position;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the DD-06 Lua guarantees, directly against a real Redis:
 * T-POS-01 (no lost updates under concurrency), T-IDEM-01 (redelivery applied once), and breach eval.
 */
class PositionStoreTest {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    private static final LettuceConnectionFactory CONNECTION_FACTORY;
    private static final PositionStore store;

    static {
        REDIS.start();
        CONNECTION_FACTORY = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        CONNECTION_FACTORY.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(CONNECTION_FACTORY);
        template.afterPropertiesSet();
        store = new PositionStore(template);
    }

    @AfterAll
    static void shutdown() {
        CONNECTION_FACTORY.destroy();
    }

    @Test
    void concurrentBuysNeverLoseAnUpdate() throws InterruptedException {   // T-POS-01 / CT-01
        UUID tenantId = UUID.randomUUID();
        String symbol = "CRUDE-OIL";
        int threads = 100;

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();   // release all at once → maximum contention
                        store.applyAndEvaluate(tenantId, symbol, UUID.randomUUID(), 10, 1_000_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startGate.countDown();
            finished.await();
        }

        assertThat(store.position(tenantId, symbol)).isEqualTo(1000L);   // exactly 100 * 10
    }

    @Test
    void redeliveredTradeIsAppliedOnce() {   // T-IDEM-01 / CT-02
        UUID tenantId = UUID.randomUUID();
        UUID tradeId = UUID.randomUUID();

        PositionUpdate first = store.applyAndEvaluate(tenantId, "NAT-GAS", tradeId, 50, 1_000_000);
        PositionUpdate replay = store.applyAndEvaluate(tenantId, "NAT-GAS", tradeId, 50, 1_000_000);

        assertThat(first.newPosition()).isEqualTo(50L);
        assertThat(first.alreadyApplied()).isFalse();
        assertThat(replay.newPosition()).isEqualTo(50L);   // NOT 100 — the redelivery is a no-op
        assertThat(replay.alreadyApplied()).isTrue();
    }

    @Test
    void bulkReadReturnsPositionPerSymbol() {
        UUID tenantId = UUID.randomUUID();
        store.applyAndEvaluate(tenantId, "A", UUID.randomUUID(), 30, 1_000_000);
        store.applyAndEvaluate(tenantId, "B", UUID.randomUUID(), -20, 1_000_000);

        var positions = store.positions(tenantId, java.util.List.of("A", "B", "C"));

        assertThat(positions.get("A")).isEqualTo(30L);
        assertThat(positions.get("B")).isEqualTo(-20L);
        assertThat(positions.get("C")).isEqualTo(0L);   // no trades yet
        assertThat(store.positions(tenantId, java.util.List.of())).isEmpty();
    }

    @Test
    void flagsBreachWhenLimitExceeded() {
        PositionUpdate result =
                store.applyAndEvaluate(UUID.randomUUID(), "OIL", UUID.randomUUID(), 11_000, 10_000);

        assertThat(result.breached()).isTrue();
        assertThat(result.newPosition()).isEqualTo(11_000L);
    }
}

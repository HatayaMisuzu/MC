package com.mccompanion.runtime.websocket;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeWebSocketServerCapacityTest {
    @Test
    void playerPlanningQueueHasFiniteBackpressure() throws Exception {
        var executor = RuntimeWebSocketServer.boundedPlanningExecutor();
        var bothStarted = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        Runnable blocker = () -> {
            bothStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            executor.execute(blocker);
            executor.execute(blocker);
            assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 64; index++) executor.execute(() -> { });
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}

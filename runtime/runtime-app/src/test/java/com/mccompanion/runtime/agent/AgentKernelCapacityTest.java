package com.mccompanion.runtime.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentKernelCapacityTest {
    @Test
    void replanningQueueIsBoundedAndClosesCleanly() throws Exception {
        var executor = AgentKernel.boundedReplanner();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 64; index++) executor.execute(() -> { });
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}

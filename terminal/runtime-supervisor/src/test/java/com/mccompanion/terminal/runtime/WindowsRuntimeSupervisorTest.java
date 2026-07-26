package com.mccompanion.terminal.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsRuntimeSupervisorTest {
    @TempDir Path temporary;

    @Test
    void sameProfileOperationsAreMutuallyExclusiveAndRecoverAfterRelease() throws Exception {
        RuntimeProfile profile = new RuntimeProfile(
                "profile-a", temporary.resolve("profile-a"), temporary.resolve("runtime.cmd"), 43100);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
            try {
                return WindowsRuntimeSupervisor.withProfileOperation(
                        profile, "start", Duration.ofSeconds(2), () -> {
                            entered.countDown();
                            try {
                                if (!release.await(2, TimeUnit.SECONDS)) {
                                    throw new IOException("test release was not signalled");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("test operation interrupted", interrupted);
                            }
                            return "first";
                        });
            } catch (IOException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Path owner = profile.profileDirectory().resolve(".runtime-operation.owner.json");
        assertTrue(Files.isRegularFile(owner));

        IOException busy = assertThrows(IOException.class,
                () -> WindowsRuntimeSupervisor.withProfileOperation(
                        profile, "stop", Duration.ofMillis(100), () -> "unexpected"));
        assertTrue(busy.getMessage().contains("Runtime profile operation is busy"));

        release.countDown();
        assertEquals("first", first.get(2, TimeUnit.SECONDS));
        assertFalse(Files.exists(owner));
        assertEquals("recovered", WindowsRuntimeSupervisor.withProfileOperation(
                profile, "repair", Duration.ofSeconds(1), () -> "recovered"));
    }

    @Test
    void differentProfilesDoNotShareTheSameOperationLock() throws Exception {
        RuntimeProfile first = new RuntimeProfile(
                "profile-a", temporary.resolve("profile-a"), temporary.resolve("runtime.cmd"), 43100);
        RuntimeProfile second = new RuntimeProfile(
                "profile-b", temporary.resolve("profile-b"), temporary.resolve("runtime.cmd"), 43200);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holding = CompletableFuture.runAsync(() -> {
            try {
                WindowsRuntimeSupervisor.withProfileOperation(
                        first, "upgrade", Duration.ofSeconds(2), () -> {
                            entered.countDown();
                            try {
                                release.await(2, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("test operation interrupted", interrupted);
                            }
                            return null;
                        });
            } catch (IOException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            assertEquals("parallel", WindowsRuntimeSupervisor.withProfileOperation(
                    second, "start", Duration.ofMillis(250), () -> "parallel"));
        } finally {
            release.countDown();
        }
        holding.get(2, TimeUnit.SECONDS);
    }
}

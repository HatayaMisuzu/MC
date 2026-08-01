package com.mccompanion.terminal;

import com.mccompanion.terminal.launcher.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipFile;
import com.mccompanion.terminal.runtime.RuntimeProfile;
import com.mccompanion.terminal.diagnostics.DiagnosticResult;

import static org.junit.jupiter.api.Assertions.*;

class SupportBundleServiceTest {
    @TempDir Path temp;

    @Test void removesSecretsAddressesPathsAndIdentifiers() throws Exception {
        Path game=temp.resolve("game");Files.createDirectories(game.resolve("logs"));Files.createDirectories(game.resolve("mods"));
        String sensitive="[ERROR] token=abc123 Authorization: Bearer hidden API_KEY=qwerty C:\\Users\\Alice\\game "
                +"127.0.0.1:25565 2001:db8::1 123e4567-e89b-42d3-a456-426614174000 server.example.com "
                +"{\"apiKey\":\"json-secret\",\"password\":\"password-secret\",\"instanceId\":\"private-instance\"} "
                +"https://server.example.com/query?token=query-secret alice@example.com /home/alice/.mcac/profile "
                +"eyJabcdefghijk.abcdefghijkl.abcdefghijkl username=PrivatePlayer";
        String privateChat="\n[Server thread/INFO]: <Alice> this private conversation must not be collected";
        Files.writeString(game.resolve("logs/latest.log"),sensitive+privateChat,StandardCharsets.UTF_8);
        Files.writeString(game.resolve("mods/minecraft-ai-companion-1.21.1-0.3.0.jar"), "fixture");
        MinecraftInstance instance=new MinecraftInstance("id","launcher","Test",temp,temp,game,game.resolve("mods"),
                game.resolve("config"),game.resolve("logs"),"1.21.1",LoaderType.FABRIC,"1",21, Optional.empty(),
                InstanceIsolation.EXPLICIT,DetectionConfidence.HIGH);
        Path profileDir=temp.resolve("control/profiles/id");Files.createDirectories(profileDir);
        Files.writeString(profileDir.resolve("companion.db"), "memory value that must never enter support archive");
        RuntimeProfile profile=new RuntimeProfile("id",profileDir,temp.resolve("runtime.cmd"),8766,18766);
        Files.writeString(profile.logFile(),sensitive+privateChat,StandardCharsets.UTF_8);
        var doctor=java.util.List.of(new DiagnosticResult(DiagnosticResult.Severity.WARNING,"brain.provider",
                "credential rejected",java.util.Map.of("token","doctor-secret"),java.util.List.of("review provider")));
        Path output=temp.resolve("support.zip");new SupportBundleService().collect(instance,profile,doctor,output);
        try(ZipFile zip=new ZipFile(output.toFile())){
            String log=new String(zip.getInputStream(zip.getEntry("minecraft-errors.log")).readAllBytes(),StandardCharsets.UTF_8);
            assertFalse(log.contains("abc123"));assertFalse(log.contains("hidden"));assertFalse(log.contains("qwerty"));
            assertFalse(log.contains("Alice"));assertFalse(log.contains("127.0.0.1"));assertFalse(log.contains("2001:db8"));
            assertFalse(log.contains("123e4567"));assertFalse(log.contains("server.example.com"));
            assertFalse(log.contains("json-secret"));assertFalse(log.contains("password-secret"));
            assertFalse(log.contains("private-instance"));assertFalse(log.contains("query-secret"));
            assertFalse(log.contains("alice@example.com"));assertFalse(log.contains("/home/alice"));
            assertFalse(log.contains("eyJabcdefghijk"));assertFalse(log.contains("PrivatePlayer"));
            assertFalse(log.contains("private conversation"));
            String mods=new String(zip.getInputStream(zip.getEntry("mods.txt")).readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(mods.contains("minecraft-ai-companion-1.21.1-0.3.0.jar"));
            String runtimeLog=new String(zip.getInputStream(zip.getEntry("runtime-errors.log")).readAllBytes(),StandardCharsets.UTF_8);
            assertFalse(runtimeLog.contains("json-secret"));assertFalse(runtimeLog.contains("PrivatePlayer"));
            String doctorText=new String(zip.getInputStream(zip.getEntry("doctor.txt")).readAllBytes(),StandardCharsets.UTF_8);
            assertFalse(doctorText.contains("doctor-secret"));assertTrue(doctorText.contains("brain.provider"));
            assertEquals(java.util.Set.of("summary.txt","minecraft-errors.log","runtime-summary.txt",
                            "safe-config-summary.txt","runtime-errors.log","doctor.txt","reproduction-steps.txt","mods.txt",
                            "bundle-manifest.json"),
                    zip.stream().map(java.util.zip.ZipEntry::getName).collect(java.util.stream.Collectors.toSet()));
            for (var entry : java.util.Collections.list(zip.entries())) {
                String contents = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(contents.contains("memory value that must never enter support archive"));
            }
        }
    }

    @Test void decodesNonUtf8LogsAndRecordsEncodingEvidence() throws Exception {
        Path game=temp.resolve("gb-game");Files.createDirectories(game.resolve("logs"));Files.createDirectories(game.resolve("mods"));
        Files.write(game.resolve("logs/latest.log"), "[ERROR] 中文错误\n".getBytes(java.nio.charset.Charset.forName("GB18030")));
        MinecraftInstance instance=new MinecraftInstance("id","launcher","Test",temp,temp,game,game.resolve("mods"),
                game.resolve("config"),game.resolve("logs"),"1.20.1",LoaderType.FORGE,"1",17, Optional.empty(),
                InstanceIsolation.EXPLICIT,DetectionConfidence.HIGH);
        Path output=temp.resolve("gb-support.zip");new SupportBundleService().collect(instance,output);
        try(ZipFile zip=new ZipFile(output.toFile())){
            String log=new String(zip.getInputStream(zip.getEntry("minecraft-errors.log")).readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(log.contains("中文错误"));
            String manifest=new String(zip.getInputStream(zip.getEntry("bundle-manifest.json")).readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(manifest.contains("GB18030"));
            assertTrue(manifest.contains("minecraft-latest-log"));
        }
    }

    @Test void failedVerificationPreservesExistingArchiveAndRemovesPartFile() throws Exception {
        Path game=temp.resolve("fault-game");Files.createDirectories(game.resolve("logs"));Files.createDirectories(game.resolve("mods"));
        MinecraftInstance instance=new MinecraftInstance("id","launcher","Test",temp,temp,game,game.resolve("mods"),
                game.resolve("config"),game.resolve("logs"),"1.21.1",LoaderType.FABRIC,"1",21, Optional.empty(),
                InstanceIsolation.EXPLICIT,DetectionConfidence.HIGH);
        Path output=temp.resolve("existing.zip");Files.writeString(output,"preserve-me",StandardCharsets.UTF_8);
        SupportBundleService service=new SupportBundleService(part -> { throw new java.io.IOException("injected"); });
        assertThrows(java.io.IOException.class, () -> service.collect(instance,output));
        assertEquals("preserve-me",Files.readString(output,StandardCharsets.UTF_8));
        try(var files=Files.list(temp)){
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    @Test void concurrentLogGrowthAndRotationStillPublishesOneVerifiedArchive() throws Exception {
        Path game=temp.resolve("rolling-game");Files.createDirectories(game.resolve("logs"));Files.createDirectories(game.resolve("mods"));
        Path latest=game.resolve("logs/latest.log");
        byte[] initial=("[ERROR] initial\n"+"x".repeat(512*1024)+"\n").getBytes(StandardCharsets.UTF_8);
        initial[initial.length-2]=(byte)0x81;
        Files.write(latest,initial);
        MinecraftInstance instance=new MinecraftInstance("rolling","launcher","Test",temp,temp,game,game.resolve("mods"),
                game.resolve("config"),game.resolve("logs"),"1.20.1",LoaderType.FORGE,"1",17, Optional.empty(),
                InstanceIsolation.EXPLICIT,DetectionConfidence.HIGH);
        java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> writerFailure=new java.util.concurrent.atomic.AtomicReference<>();
        Thread writer=new Thread(() -> {
            try {
                started.countDown();
                for(int i=0;i<100;i++){
                    Files.writeString(latest,"[ERROR] appended-"+i+"\n",StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.APPEND);
                    if(i==40) Files.copy(latest,game.resolve("logs/latest.1.log"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch(Throwable failure){writerFailure.set(failure);}
        },"support-bundle-log-writer");
        writer.start();started.await();
        Path output=temp.resolve("rolling-support.zip");
        new SupportBundleService().collect(instance,output);
        writer.join();
        assertNull(writerFailure.get());
        assertTrue(Files.isRegularFile(output));
        try(var files=Files.list(temp)){
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
        try(ZipFile zip=new ZipFile(output.toFile())){
            assertNotNull(zip.getEntry("bundle-manifest.json"));
            for(var entry:java.util.Collections.list(zip.entries())){
                assertDoesNotThrow(() -> zip.getInputStream(entry).readAllBytes());
            }
        }
    }
}

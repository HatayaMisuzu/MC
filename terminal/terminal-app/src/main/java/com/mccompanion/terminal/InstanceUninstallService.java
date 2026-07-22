package com.mccompanion.terminal;

import com.mccompanion.terminal.install.InstallTransaction;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import com.mccompanion.terminal.runtime.RuntimeProfile;
import com.mccompanion.terminal.runtime.WindowsRuntimeSupervisor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Coordinates Runtime shutdown with the two explicit instance uninstall data policies. */
final class InstanceUninstallService {
    enum DataPolicy { PRESERVE, DELETE }

    private final RuntimeStopper runtimeStopper;
    private final InstallTransaction transaction;

    InstanceUninstallService() {
        this(profile -> new WindowsRuntimeSupervisor().stop(profile), new InstallTransaction());
    }

    InstanceUninstallService(RuntimeStopper runtimeStopper, InstallTransaction transaction) {
        this.runtimeStopper = runtimeStopper;
        this.transaction = transaction;
    }

    void uninstall(MinecraftInstance instance, RuntimeProfile profile, Path controlHome,
                   DataPolicy policy) throws IOException {
        if (profile != null) runtimeStopper.stop(profile);
        transaction.uninstall(instance.gameDirectory(), policy == DataPolicy.DELETE
                ? InstallTransaction.UninstallMode.DELETE_INSTANCE_USER_DATA
                : InstallTransaction.UninstallMode.PRESERVE_USER_DATA);
        if (policy == DataPolicy.DELETE && profile != null) {
            deleteProfile(profile.profileDirectory(), controlHome.resolve("profiles"));
        }
    }

    private static void deleteProfile(Path profile, Path profilesRoot) throws IOException {
        Path root = profilesRoot.toAbsolutePath().normalize();
        Path target = profile.toAbsolutePath().normalize();
        if (target.equals(root) || !target.startsWith(root)) {
            throw new IOException("Runtime profile is outside the managed profiles directory");
        }
        if (!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(target)) throw new IOException("Refusing to delete linked Runtime profile");
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @FunctionalInterface
    interface RuntimeStopper { void stop(RuntimeProfile profile) throws IOException; }
}

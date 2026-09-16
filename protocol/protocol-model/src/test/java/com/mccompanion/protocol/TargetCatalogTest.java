package com.mccompanion.protocol;

import com.mccompanion.protocol.target.TargetCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TargetCatalogTest {
    @Test void bundledTargetModesAreIndependentFromSourceSharing() {
        var catalog = TargetCatalog.bundled();
        assertTrue(catalog.find("1.21.1", "FABRIC").orElseThrow().fullBridge());
        assertTrue(catalog.find("1.20.1", "forge").orElseThrow().fullBridge());
        var local = catalog.find("1.21.1", "neoforge").orElseThrow();
        assertFalse(local.fullBridge()); assertNull(local.protocol()); assertTrue(local.expectedCapabilities().isEmpty());
        assertTrue(catalog.find("1.21.10", "fabric").isEmpty());
        assertTrue(catalog.find("1.21.1", "forge").isEmpty());
        assertTrue(catalog.targets().stream().allMatch(target -> target.supportsLoader(target.loaderVersion())));
    }
}

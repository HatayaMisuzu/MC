package com.mccompanion.terminal;

import com.mccompanion.terminal.runtime.RuntimeProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchConfigurationServiceTest {
    @TempDir Path temporary;

    @Test
    void persistsPolicyWithoutCredentialAndRejectsUnsafeConfiguration() throws Exception {
        RuntimeProfile profile = new RuntimeProfile("search", temporary, temporary.resolve("start.cmd"), 8766);
        SearchConfigurationService service = new SearchConfigurationService();

        service.configure(profile, "https://search.example/v1/query", "MCAC_SEARCH_TOKEN", 12,
                List.of("Docs.Example", "wiki.example"), List.of("blocked.example"));

        var status = service.status(profile);
        assertEquals("http", status.path("mode").asText());
        assertEquals("docs.example", status.path("allowedDomains").path(0).asText());
        assertEquals("MCAC_SEARCH_TOKEN", status.path("tokenEnv").asText());
        assertTrue(!status.toString().contains("Bearer"));
        assertThrows(java.io.IOException.class, () -> service.configure(profile,
                "http://public.example/query", "MCAC_SEARCH_TOKEN", 12, List.of(), List.of()));
        assertThrows(java.io.IOException.class, () -> service.configure(profile,
                "https://search.example/query?api_key=must-not-be-stored", "MCAC_SEARCH_TOKEN", 12,
                List.of(), List.of()));
        assertThrows(java.io.IOException.class, () -> service.configure(profile,
                "https://search.example/query", "MCAC_SEARCH_TOKEN", 12,
                List.of("same.example"), List.of("same.example")));

        Files.writeString(service.file(profile), """
                {"mode":"http","endpoint":"https://search.example/query","tokenEnv":"MCAC_SEARCH_TOKEN",
                 "token":"must-not-leak","allowedDomains":[],"deniedDomains":[]}
                """);
        assertTrue(!service.status(profile).has("token"));
    }
}

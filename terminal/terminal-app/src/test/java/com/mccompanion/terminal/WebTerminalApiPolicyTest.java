package com.mccompanion.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebTerminalApiPolicyTest {
    @Test
    void doctorOnlyAdvertisesRoutesThatTheExecutorCanPerform() {
        assertEquals(
                new WebTerminalApi.DoctorRepairRoute("runtime", "restart"),
                WebTerminalApi.doctorRepairRoute("runtime.health"));
        assertEquals(
                new WebTerminalApi.DoctorRepairRoute("runtime", "rotate-token"),
                WebTerminalApi.doctorRepairRoute("runtime.token_match"));
        assertEquals(
                new WebTerminalApi.DoctorRepairRoute("install", "repair"),
                WebTerminalApi.doctorRepairRoute("install.hash"));

        assertNull(WebTerminalApi.doctorRepairRoute("brain.protocol"));
        assertNull(WebTerminalApi.doctorRepairRoute("search.configuration"));
        assertNull(WebTerminalApi.doctorRepairRoute("mcp.protocol"));
        assertNull(WebTerminalApi.doctorRepairRoute("hook.state"));
    }
}

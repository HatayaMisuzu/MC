package com.mccompanion.terminal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WebTerminalOptionsTest {
  @Test
  void directNoArgumentLaunchOpensBrowserButExplicitWebRemainsHeadless() {
    assertTrue(WebTerminalOptions.parse(new String[] {}, Map.<String, String>of()::get).openBrowser());
    assertFalse(
        WebTerminalOptions.parse(new String[] {"web"}, Map.<String, String>of()::get)
            .openBrowser());
    assertTrue(
        WebTerminalOptions.parse(
                new String[] {"web", "--open-browser"}, Map.<String, String>of()::get)
            .openBrowser());
  }

  @Test
  void environmentCanOptInButNoBrowserIsAnUnconditionalSafetyVeto() {
    assertTrue(
        WebTerminalOptions.parse(
                new String[] {"web"}, Map.of("MCAC_OPEN_BROWSER", "true")::get)
            .openBrowser());
    assertFalse(
        WebTerminalOptions.parse(
                new String[] {"web", "--open-browser"},
                Map.of("MCAC_OPEN_BROWSER", "true", "MCAC_NO_BROWSER", "true")::get)
            .openBrowser());
  }

  @Test
  void commandLineCanDisableAnEnvironmentOptIn() {
    assertFalse(
        WebTerminalOptions.parse(
                new String[] {"web", "--no-browser"},
                Map.of("MCAC_OPEN_BROWSER", "true")::get)
            .openBrowser());
  }
}

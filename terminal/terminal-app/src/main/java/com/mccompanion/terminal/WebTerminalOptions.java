package com.mccompanion.terminal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

record WebTerminalOptions(
    Path webRoot, int port, boolean openBrowser, Path stateFile, List<Path> scanRoots) {
  static WebTerminalOptions parse(String[] arguments) {
    Path webRoot = pathEnvironment("MCAC_WEB_ROOT");
    int port = integerEnvironment("MCAC_WEB_PORT", 0);
    boolean openBrowser = !"true".equalsIgnoreCase(System.getenv("MCAC_NO_BROWSER"));
    Path stateFile = pathEnvironment("MCAC_WEB_STATE_FILE");
    List<Path> roots = new ArrayList<>();
    for (int index = 0; index < arguments.length; index++) {
      String argument = arguments[index];
      switch (argument) {
        case "web" -> {}
        case "--no-browser" -> openBrowser = false;
        case "--web-root" -> webRoot = Path.of(value(arguments, ++index, argument));
        case "--port" -> port = Integer.parseInt(value(arguments, ++index, argument));
        case "--state-file" -> stateFile = Path.of(value(arguments, ++index, argument));
        case "--root" -> roots.add(Path.of(value(arguments, ++index, argument)));
        default -> throw new IllegalArgumentException("未知 HTML 终端参数: " + argument);
      }
    }
    if (port < 0 || port > 65_535) throw new IllegalArgumentException("端口必须是 0..65535");
    return new WebTerminalOptions(webRoot, port, openBrowser, stateFile, List.copyOf(roots));
  }

  private static String value(String[] arguments, int index, String option) {
    if (index >= arguments.length) throw new IllegalArgumentException(option + " 缺少参数");
    return arguments[index];
  }

  private static Path pathEnvironment(String name) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? null : Path.of(value);
  }

  private static int integerEnvironment(String name, int fallback) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) return fallback;
    return Integer.parseInt(value);
  }
}

package com.mccompanion.terminal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

record WebTerminalOptions(
    Path webRoot, int port, boolean openBrowser, Path stateFile, List<Path> scanRoots) {
  static WebTerminalOptions parse(String[] arguments) {
    return parse(arguments, System::getenv);
  }

  static WebTerminalOptions parse(String[] arguments, Function<String, String> environment) {
    Path webRoot = pathEnvironment(environment, "MCAC_WEB_ROOT");
    int port = integerEnvironment(environment, "MCAC_WEB_PORT", 0);
    boolean openBrowser =
        arguments.length == 0 || booleanEnvironment(environment, "MCAC_OPEN_BROWSER");
    Path stateFile = pathEnvironment(environment, "MCAC_WEB_STATE_FILE");
    List<Path> roots = new ArrayList<>();
    for (int index = 0; index < arguments.length; index++) {
      String argument = arguments[index];
      switch (argument) {
        case "web" -> {}
        case "--open-browser" -> openBrowser = true;
        case "--no-browser" -> openBrowser = false;
        case "--web-root" -> webRoot = Path.of(value(arguments, ++index, argument));
        case "--port" -> port = Integer.parseInt(value(arguments, ++index, argument));
        case "--state-file" -> stateFile = Path.of(value(arguments, ++index, argument));
        case "--root" -> roots.add(Path.of(value(arguments, ++index, argument)));
        default -> throw new IllegalArgumentException("未知 HTML 终端参数: " + argument);
      }
    }
    if (booleanEnvironment(environment, "MCAC_NO_BROWSER")) openBrowser = false;
    if (port < 0 || port > 65_535) throw new IllegalArgumentException("端口必须是 0..65535");
    return new WebTerminalOptions(webRoot, port, openBrowser, stateFile, List.copyOf(roots));
  }

  private static String value(String[] arguments, int index, String option) {
    if (index >= arguments.length) throw new IllegalArgumentException(option + " 缺少参数");
    return arguments[index];
  }

  private static Path pathEnvironment(Function<String, String> environment, String name) {
    String value = environment.apply(name);
    return value == null || value.isBlank() ? null : Path.of(value);
  }

  private static int integerEnvironment(
      Function<String, String> environment, String name, int fallback) {
    String value = environment.apply(name);
    if (value == null || value.isBlank()) return fallback;
    return Integer.parseInt(value);
  }

  private static boolean booleanEnvironment(
      Function<String, String> environment, String name) {
    return "true".equalsIgnoreCase(environment.apply(name));
  }
}

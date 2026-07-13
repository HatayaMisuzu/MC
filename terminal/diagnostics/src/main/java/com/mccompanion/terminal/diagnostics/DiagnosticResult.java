package com.mccompanion.terminal.diagnostics;

import java.util.Map;

public record DiagnosticResult(Severity severity, String code, String summary, Map<String, String> evidence) {
    public DiagnosticResult { evidence = Map.copyOf(evidence); }
    public enum Severity { PASS, WARNING, BLOCKED, UNKNOWN }
}

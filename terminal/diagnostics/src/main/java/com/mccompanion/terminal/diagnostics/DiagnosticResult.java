package com.mccompanion.terminal.diagnostics;

import java.util.Map;

public record DiagnosticResult(Severity severity, String code, String summary, Map<String, String> evidence, java.util.List<String> repairs) {
    public DiagnosticResult { evidence = Map.copyOf(evidence); repairs=java.util.List.copyOf(repairs); }
    public DiagnosticResult(Severity severity,String code,String summary,Map<String,String> evidence){this(severity,code,summary,evidence,java.util.List.of());}
    public enum Severity { PASS, WARNING, BLOCKED, UNKNOWN }
}

package com.mccompanion.runtime.provider;

import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.intent.RuleIntentParser;
import com.mccompanion.runtime.logging.RuntimeLog;

import java.util.Optional;

public final class ProviderRouter {
    private final RuleIntentParser rules;
    private final IntentProvider provider;
    private final RuntimeLog log;

    public ProviderRouter(RuleIntentParser rules, IntentProvider provider, RuntimeLog log) {
        this.rules = rules;
        this.provider = provider;
        this.log = log;
    }

    public Resolution resolve(String text) {
        Optional<Intent> rule = rules.parse(text);
        if (rule.isPresent()) {
            return new Resolution(rule, "rules", false, null, null);
        }
        if (provider == null) {
            return new Resolution(Optional.empty(), "rules", false, "UNKNOWN_COMMAND",
                    "无法识别命令。输入 help 查看可用命令。");
        }
        try {
            return new Resolution(Optional.of(provider.parse(text)), "provider", false, null, null);
        } catch (ProviderException failure) {
            log.warn("Provider intent resolution failed; falling back to rules: code=" + failure.code());
            Optional<Intent> fallback = rules.parse(text);
            return new Resolution(fallback, "rules", true, failure.code(),
                    fallback.isPresent() ? null : "模型暂时不可用，且规则模式无法识别该命令。");
        }
    }

    public record Resolution(Optional<Intent> intent, String source, boolean fallbackUsed,
                             String errorCode, String userMessage) {
    }
}

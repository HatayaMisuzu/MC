package com.mccompanion.runtime.conversation;

import com.mccompanion.runtime.intent.RuleIntentParser;
import com.mccompanion.runtime.task.TaskType;

import java.util.Locale;

/** Conservatively classifies text while a plan is waiting for its owner. */
public final class IncomingMessageClassifier {
    private final RuleIntentParser controls = new RuleIntentParser();

    public IncomingMessageResolution classify(String text, WaitingQuestion waiting) {
        String value = text == null ? "" : text.strip();
        var intent = controls.parse(value);
        if (intent.isPresent() && intent.get().type() == TaskType.STOP) {
            return new IncomingMessageResolution(IncomingMessageKind.CONTROL, null, value);
        }
        String compact = value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (compact.contains("改成") || compact.contains("不要了") || compact.contains("算了")
                || compact.contains("回来陪我") || compact.contains("换成")) {
            return new IncomingMessageResolution(IncomingMessageKind.GOAL_MODIFICATION, null, value);
        }
        if (waiting != null) {
            for (ConversationOption option : waiting.options()) {
                if (compact.equals(option.id().toLowerCase(Locale.ROOT))
                        || compact.contains(option.label().replaceAll("\\s+", "").toLowerCase(Locale.ROOT))) {
                    return new IncomingMessageResolution(IncomingMessageKind.WAITING_ANSWER, option.id(), value);
                }
            }
            if (compact.contains("先把") && (compact.contains("拿来") || compact.contains("给我"))) {
                return answer(waiting, "deliver_partial", value);
            }
            if (compact.contains("其他箱") || compact.contains("别的箱") || compact.contains("其他来源")) {
                return answer(waiting, "search_other", value);
            }
            if (compact.contains("补齐") || compact.contains("去挖") || compact.contains("矿洞")) {
                return answer(waiting, "collect_missing", value);
            }
            if (waiting.freeTextAllowed()) {
                return new IncomingMessageResolution(IncomingMessageKind.WAITING_ANSWER, null, value);
            }
        }
        return new IncomingMessageResolution(IncomingMessageKind.NEW_REQUEST_OR_CONVERSATION, null, value);
    }

    private static IncomingMessageResolution answer(WaitingQuestion waiting, String id, String text) {
        boolean offered = waiting.options().stream().anyMatch(option -> option.id().equals(id));
        return new IncomingMessageResolution(IncomingMessageKind.WAITING_ANSWER, offered ? id : null, text);
    }
}

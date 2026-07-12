package com.mccompanion.runtime.intent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.task.TaskType;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuleIntentParser {
    private static final int MAX_HORIZONTAL_COORDINATE = 30_000_000;
    private static final int MIN_VERTICAL_COORDINATE = -2_048;
    private static final int MAX_VERTICAL_COORDINATE = 2_048;
    private static final Pattern TRAVEL = Pattern.compile(
            "^(?:去|goto)\\s+([+-]?\\d{1,10})(?:\\s*[,，]\\s*|\\s+)([+-]?\\d{1,10})"
                    + "(?:\\s*[,，]\\s*|\\s+)([+-]?\\d{1,10})$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public Optional<Intent> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String text = input.strip().replaceAll("\\s+", " ");
        if (text.isEmpty() || text.length() > 4096) {
            return Optional.empty();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "跟着我", "跟随我", "follow", "follow me" -> intent(TaskType.FOLLOW, text);
            case "停止", "停下", "stop" -> intentWithAction(TaskType.STOP, "cancel", text);
            case "pause", "暂停" -> intentWithAction(TaskType.STOP, "pause", text);
            case "继续", "resume" -> intentWithAction(TaskType.STOP, "resume", text);
            case "到我这里来", "过来", "come here", "return" -> intent(TaskType.RETURN, text);
            case "你在哪里", "当前状态", "状态", "status" -> intent(TaskType.STATUS, text);
            case "取消任务", "取消", "cancel", "cancel task" -> intentWithAction(TaskType.STOP, "cancel", text);
            default -> parseTravel(text);
        };
    }

    private Optional<Intent> parseTravel(String text) {
        Matcher matcher = TRAVEL.matcher(text);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            int x = Integer.parseInt(matcher.group(1));
            int y = Integer.parseInt(matcher.group(2));
            int z = Integer.parseInt(matcher.group(3));
            if (Math.abs((long) x) > MAX_HORIZONTAL_COORDINATE
                    || Math.abs((long) z) > MAX_HORIZONTAL_COORDINATE
                    || y < MIN_VERTICAL_COORDINATE || y > MAX_VERTICAL_COORDINATE) {
                return Optional.empty();
            }
            ObjectNode target = Json.object().put("dimension", "minecraft:overworld")
                    .put("x", x).put("y", y).put("z", z);
            ObjectNode arguments = Json.object();
            arguments.set("target", target);
            return Optional.of(new Intent(TaskType.TRAVEL, arguments, text));
        } catch (NumberFormatException outOfRange) {
            return Optional.empty();
        }
    }

    private static Optional<Intent> intent(TaskType type, String text) {
        return Optional.of(new Intent(type, Json.object(), text));
    }

    private static Optional<Intent> intentWithAction(TaskType type, String action, String text) {
        return Optional.of(new Intent(type, Json.object().put("action", action), text));
    }
}

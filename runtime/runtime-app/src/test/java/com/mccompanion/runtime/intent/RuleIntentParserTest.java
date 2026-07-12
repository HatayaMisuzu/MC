package com.mccompanion.runtime.intent;

import com.mccompanion.runtime.task.TaskType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuleIntentParserTest {
    private final RuleIntentParser parser = new RuleIntentParser();

    @ParameterizedTest
    @CsvSource({
            "跟着我,FOLLOW,",
            "跟随我,FOLLOW,",
            "停止,STOP,cancel",
            "暂停,STOP,pause",
            "继续,STOP,resume",
            "到我这里来,RETURN,",
            "你在哪里,STATUS,",
            "取消任务,STOP,cancel"
    })
    void parsesFrozenChineseRuleCommands(String text, TaskType expected, String action) {
        Intent intent = parser.parse(text).orElseThrow();
        assertEquals(expected, intent.type());
        if (action != null) assertEquals(action, intent.arguments().path("action").asText());
    }

    @Test
    void parsesAndBoundsTravelCoordinates() {
        Intent intent = parser.parse("去 12 64 -8").orElseThrow();
        assertEquals(TaskType.TRAVEL, intent.type());
        assertEquals(12, intent.arguments().path("target").path("x").asInt());
        assertEquals(-8, intent.arguments().path("target").path("z").asInt());
        assertTrue(parser.parse("去 30000001 64 0").isEmpty());
        assertTrue(parser.parse("去 1 9999 3").isEmpty());
        assertTrue(parser.parse("挖钻石").isEmpty());
    }
}


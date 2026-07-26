package com.mccompanion.runtime.conversation;

import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IncomingMessageClassifierTest {
    private final IncomingMessageClassifier classifier = new IncomingMessageClassifier();
    private final WaitingQuestion waiting = new WaitingQuestion("q", "p", "c", "怎么做？", "shortage",
            List.of(new ConversationOption("deliver_partial", "先拿现有的", ""),
                    new ConversationOption("search_other", "看其他箱子", ""),
                    new ConversationOption("collect_missing", "去补齐", "")), true, "WAITING",
            Json.object(), null, Instant.now(), Instant.now(), null);

    @Test void linksNaturalAnswerToStableOption() {
        var answer = classifier.classify("先把 6 个拿来", waiting);
        assertEquals(IncomingMessageKind.WAITING_ANSWER, answer.kind());
        assertEquals("deliver_partial", answer.optionId());
    }

    @Test void goalModificationAndControlAreNotConsumedAsAnswers() {
        assertEquals(IncomingMessageKind.GOAL_MODIFICATION,
                classifier.classify("算了，不要铁了，回来陪我", waiting).kind());
        assertEquals(IncomingMessageKind.CONTROL, classifier.classify("暂停", waiting).kind());
    }

    @Test void explicitImmediateInstructionIsDistinctButHypotheticalSpeechIsOnlyConversation() {
        assertEquals(IncomingMessageKind.IMMEDIATE_INSTRUCTION,
                classifier.classify("先跟我走", null).kind());
        assertEquals(IncomingMessageKind.NEW_REQUEST_OR_CONVERSATION,
                classifier.classify("假如我说算了，你会怎么办？", null).kind());
        assertEquals(IncomingMessageKind.NEW_REQUEST_OR_CONVERSATION,
                classifier.classify("刚才那个笑话挺好", null).kind());
    }
}

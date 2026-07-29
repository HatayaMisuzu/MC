package com.mccompanion.minecraft.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationDeliveryWindowTest {
    @Test
    void deduplicatesReconnectResendsAndStaysBounded() {
        ConversationDeliveryWindow window = new ConversationDeliveryWindow(2);

        assertTrue(window.firstDelivery("event-1"));
        assertFalse(window.firstDelivery("event-1"));
        assertTrue(window.firstDelivery("event-2"));
        assertTrue(window.firstDelivery("event-3"));
        assertEquals(2, window.size());
        assertTrue(window.firstDelivery("event-1"), "oldest event should be eligible after eviction");
        assertThrows(IllegalArgumentException.class, () -> window.firstDelivery(""));
    }
}

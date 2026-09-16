package com.mccompanion.core.body;

import com.mccompanion.minecraft.bridge.PlayerRequestChannel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerRequestChannelTest {
    @Test void repliesAreCorrelatedOnceAndExpireWithoutRetainingOwners() {
        var channel = new PlayerRequestChannel();
        UUID owner = UUID.randomUUID();
        List<String> requests = new ArrayList<>();
        assertEquals(PlayerRequestChannel.Result.ACCEPTED, channel.submit(owner, "body", "hello", 0,
                (type, payload) -> requests.add((String) payload.get("requestId"))));
        assertEquals(owner, channel.replyOwner(requests.get(0), 100));
        assertNull(channel.replyOwner(requests.get(0), 100));
        channel.submit(owner, "body", "hello", 2000, (type, payload) -> requests.add((String) payload.get("requestId")));
        assertNull(channel.replyOwner(requests.get(1), 32_001));
    }

    @Test void limitsRateAndFailedSendsDoNotClaimAcceptance() {
        var channel = new PlayerRequestChannel();
        UUID owner = UUID.randomUUID();
        assertEquals(PlayerRequestChannel.Result.DISCONNECTED, channel.submit(owner, "body", "hello", 0, (t, p) -> false));
        assertEquals(PlayerRequestChannel.Result.RATE_LIMIT, channel.submit(owner, "body", "hello", 1, (t, p) -> true));
        for (int i = 0; i < 128; i++) assertEquals(PlayerRequestChannel.Result.ACCEPTED,
                channel.submit(UUID.randomUUID(), "body", "hello", 2000, (t, p) -> true));
        assertEquals(PlayerRequestChannel.Result.LIMIT, channel.submit(UUID.randomUUID(), "body", "hello", 2000, (t, p) -> true));
        channel.clear();
        assertEquals(PlayerRequestChannel.Result.ACCEPTED, channel.submit(owner, "body", "hello", 2000, (t, p) -> true));
    }

    @Test void disconnectDiscardsOldReplyAndActivityWindow() {
        var channel = new PlayerRequestChannel();
        List<String> requests = new ArrayList<>();
        channel.submit(UUID.randomUUID(), "body", "hello", 0, (t, p) -> requests.add((String) p.get("requestId")));
        assertTrue(channel.publishActivity("block", 0));
        assertFalse(channel.publishActivity("block", 100));
        channel.clear();
        assertNull(channel.replyOwner(requests.get(0), 100));
        assertTrue(channel.publishActivity("block", 100));
    }
}

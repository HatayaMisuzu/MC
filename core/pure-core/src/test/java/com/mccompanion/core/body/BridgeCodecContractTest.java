package com.mccompanion.core.body;

import com.mccompanion.minecraft.bridge.BridgeCodec;
import com.mccompanion.minecraft.bridge.BridgeValues;
import com.mccompanion.minecraft.fabric.JacksonBridgeCodec;
import com.mccompanion.minecraft.forge.GsonBridgeCodec;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BridgeCodecContractTest {
    private final List<BridgeCodec> codecs = List.of(new JacksonBridgeCodec(), new GsonBridgeCodec());
    @Test void actualCodecsAgreeOnHelloCommandNullAndNumericValues() throws Exception {
        var canonical = new JacksonBridgeCodec();
        for (String text : List.of(
                "{\"protocol\":\"mc-companion/2\",\"type\":\"hello\",\"payload\":{\"capabilities\":{\"MenuAction\":{\"availability\":\"available\",\"version\":\"1.0\",\"attributes\":{}}}}}",
                "{\"type\":\"command\",\"sessionId\":\"session\",\"sequence\":9223372036854775806,\"payload\":{\"slot\":0,\"button\":1,\"value\":0.125,\"target\":null,\"items\":[\"未知物品\",true]}}")) {
            var expected = canonical.decode(text);
            for (var codec : codecs) assertEquals(expected, canonical.decode(codec.encode(codec.decode(text))));
        }
    }
    @Test void bothRejectDuplicateKeysMalformedDocumentsAndTrailingValues() {
        for (var codec : codecs) for (String text : List.of("{\"a\":1,\"a\":2}", "{} {}", "[]", "{\"x\":NaN}", "{a:1}")) {
            assertThrows(Exception.class, () -> codec.decode(text), text);
        }
    }
    @Test void immutableHandoffRejectsNativeObjectsAndCopiesMutableCollections() {
        var mutable = new java.util.ArrayList<>(List.of("before"));
        var snapshot = BridgeValues.freeze(java.util.Map.of("facts", mutable));
        mutable.set(0, "after");
        assertEquals(List.of("before"), snapshot.get("facts"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("late", true));
        assertThrows(IllegalArgumentException.class, () -> BridgeValues.freeze(java.util.Map.of("native", new Object())));
    }
}

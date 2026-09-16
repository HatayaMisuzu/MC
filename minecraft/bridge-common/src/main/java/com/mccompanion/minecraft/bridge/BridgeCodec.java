package com.mccompanion.minecraft.bridge;

import java.io.IOException;
import java.util.Map;

/** JSON libraries belong exclusively to the target codec. Values are immutable bounded Java data. */
public interface BridgeCodec {
    Map<String, Object> decode(String text) throws IOException;
    String encode(Map<String, Object> value) throws IOException;
}

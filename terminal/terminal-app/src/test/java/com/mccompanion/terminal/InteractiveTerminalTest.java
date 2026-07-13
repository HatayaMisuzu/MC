package com.mccompanion.terminal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveTerminalTest {
    @Test void noArgumentsShowsCompleteMenuAndAllowsSafeExit() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new InteractiveTerminal(new ControlTerminalMain(),
                new ByteArrayInputStream("0\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(bytes, true, StandardCharsets.UTF_8)).run();
        String output = bytes.toString(StandardCharsets.UTF_8);
        for (int item = 0; item <= 8; item++) assertTrue(output.contains("[" + item + "]"));
        assertTrue(output.contains("Minecraft AI Companion Control Terminal"));
    }

    @Test void invalidChoiceReturnsToMenuWithoutAnsiDependency() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new InteractiveTerminal(new ControlTerminalMain(),
                new ByteArrayInputStream("x\n0\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(bytes, true, StandardCharsets.UTF_8)).run();
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("无效选择"));
        assertTrue(output.indexOf("Minecraft AI Companion Control Terminal")
                != output.lastIndexOf("Minecraft AI Companion Control Terminal"));
    }
}

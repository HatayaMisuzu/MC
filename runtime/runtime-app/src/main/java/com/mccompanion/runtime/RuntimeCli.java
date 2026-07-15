package com.mccompanion.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.command.CommandReply;
import com.mccompanion.runtime.command.CommandService;
import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.capability.CapabilityVisibility;
import com.mccompanion.runtime.intent.Intent;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.provider.ProviderRouter;
import com.mccompanion.runtime.session.CompanionRecord;
import com.mccompanion.runtime.session.CompanionRepository;
import com.mccompanion.runtime.session.SessionRegistry;
import com.mccompanion.runtime.task.TaskType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small local control surface. It never receives or prints API keys or pairing tokens. */
public final class RuntimeCli implements AutoCloseable {
    private final CompanionRepository companions;
    private final SessionRegistry sessions;
    private final CommandService commands;
    private final ProviderRouter providers;
    private final CapabilityVisibility capabilityVisibility;
    private final Runnable shutdown;
    private final BufferedReader input;
    private final PrintStream output;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    RuntimeCli(CompanionRepository companions, SessionRegistry sessions, CommandService commands,
               ProviderRouter providers, CapabilityVisibility capabilityVisibility,
               Runnable shutdown, InputStream input, PrintStream output) {
        this.companions = Objects.requireNonNull(companions, "companions");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.capabilityVisibility = Objects.requireNonNull(capabilityVisibility, "capabilityVisibility");
        this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
        this.input = new BufferedReader(new InputStreamReader(Objects.requireNonNull(input, "input"),
                StandardCharsets.UTF_8));
        this.output = Objects.requireNonNull(output, "output");
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::runLoop, "mc-companion-runtime-cli");
        thread.setDaemon(true);
        thread.start();
    }

    private void runLoop() {
        output.println("Minecraft AI Companion Runtime CLI ready. Type 'help'.");
        try {
            String line;
            while (running.get() && (line = input.readLine()) != null) {
                if (!handle(line.strip())) {
                    break;
                }
            }
        } catch (IOException failure) {
            if (running.get()) {
                output.println("CLI input failed: " + failure.getClass().getSimpleName());
            }
        } finally {
            running.set(false);
        }
    }

    boolean handle(String line) {
        if (line == null || line.isBlank()) {
            return true;
        }
        String[] words = line.strip().split("\\s+", 3);
        String operation = words[0].toLowerCase(java.util.Locale.ROOT);
        try {
            return switch (operation) {
                case "help" -> showHelp();
                case "list" -> listCompanions();
                case "status" -> executeSimple(words, TaskType.STATUS, Json.object(), line);
                case "follow" -> executeSimple(words, TaskType.FOLLOW, Json.object(), line);
                case "return", "come" -> executeSimple(words, TaskType.RETURN, Json.object(), line);
                case "stop", "cancel" -> executeSimple(words, TaskType.STOP,
                        Json.object().put("action", "cancel"), line);
                case "pause" -> executeSimple(words, TaskType.STOP,
                        Json.object().put("action", "pause"), line);
                case "resume" -> executeSimple(words, TaskType.STOP,
                        Json.object().put("action", "resume"), line);
                case "goto" -> executeGoto(line);
                case "ask" -> executeNatural(words);
                case "quit", "exit" -> requestShutdown();
                default -> {
                    output.println("Unknown CLI command. Type 'help'.");
                    yield true;
                }
            };
        } catch (IllegalArgumentException | SQLException failure) {
            output.println("CLI_ERROR: " + safeMessage(failure));
            return true;
        }
    }

    private boolean showHelp() {
        output.println("help | list | status <companion> | follow <companion> | return <companion>");
        output.println("goto <companion> <x> <y> <z> | stop/pause/resume <companion>");
        output.println("ask <companion> <中文或自然语言命令> | quit");
        return true;
    }

    private boolean listCompanions() throws SQLException {
        List<CompanionRecord> values = companions.list();
        if (values.isEmpty()) {
            output.println("No companions have registered with this Runtime.");
        } else {
            values.forEach(value -> output.println(value.companionId() + "  " + value.displayName()
                    + "  world=" + value.worldId() + "  online=" + (value.sessionId() != null)));
        }
        return true;
    }

    private boolean executeSimple(String[] words, TaskType type, ObjectNode arguments, String original) {
        if (words.length < 2) {
            throw new IllegalArgumentException(type.name().toLowerCase() + " requires a companion id");
        }
        printReply(commands.execute(commandId(), resolveCompanionId(words[1]), new Intent(type, arguments, original)));
        return true;
    }

    private boolean executeGoto(String line) {
        String[] values = line.split("\\s+");
        if (values.length != 5) {
            throw new IllegalArgumentException("goto requires: goto <companion> <x> <y> <z>");
        }
        int x = Integer.parseInt(values[2]);
        int y = Integer.parseInt(values[3]);
        int z = Integer.parseInt(values[4]);
        ObjectNode target = Json.object().put("dimension", "minecraft:overworld")
                .put("x", x).put("y", y).put("z", z);
        ObjectNode arguments = Json.object();
        arguments.set("target", target);
        printReply(commands.execute(commandId(), resolveCompanionId(values[1]), new Intent(TaskType.TRAVEL, arguments, line)));
        return true;
    }

    private boolean executeNatural(String[] words) {
        if (words.length < 3 || words[2].isBlank()) {
            throw new IllegalArgumentException("ask requires: ask <companion> <request>");
        }
        String companionId = resolveCompanionId(words[1]);
        AgentContext context = agentContext(companionId);
        var planning = providers.plan(words[2], context);
        if (!planning.accepted()) {
            output.println(planning.errorCode() + ": " + planning.userMessage());
            return true;
        }
        if (planning.executableIntent().isPresent()) {
            printReply(commands.execute(commandId(), companionId, planning.executableIntent().get()));
        } else {
            output.println(Json.write(Json.MAPPER.valueToTree(planning.decision())));
        }
        return true;
    }

    private AgentContext agentContext(String companionId) {
        try {
            CompanionRecord companion = companions.get(companionId)
                    .orElseThrow(() -> new IllegalArgumentException("Companion is not registered"));
            var active = commands.activeTaskFor(companionId);
            var visible = capabilityVisibility.resolve(
                    sessions.forCompanion(companionId).map(value -> value.handshake()).orElse(null),
                    companion.status());
            return new AgentContext(companionId, companion.status(), List.of(),
                    active.<com.fasterxml.jackson.databind.JsonNode>map(Json.MAPPER::valueToTree).orElseGet(Json::object),
                    List.of(), visible.availableNames(), 5);
        } catch (SQLException failure) {
            throw new IllegalArgumentException("Unable to build verified companion context", failure);
        }
    }

    private String resolveCompanionId(String value) {
        if (!value.equalsIgnoreCase("first") && !value.equals("@first")) {
            return value;
        }
        try {
            return companions.list().stream()
                    .filter(record -> record.sessionId() != null)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No online companion is available"))
                    .companionId();
        } catch (SQLException failure) {
            throw new IllegalArgumentException("Unable to resolve the first online companion", failure);
        }
    }

    private boolean requestShutdown() {
        output.println("Runtime shutdown requested.");
        running.set(false);
        shutdown.run();
        return false;
    }

    private void printReply(CommandReply reply) {
        output.println(Json.write(reply.toJson()));
    }

    private static String commandId() {
        return "cli-" + UUID.randomUUID();
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
    }
}

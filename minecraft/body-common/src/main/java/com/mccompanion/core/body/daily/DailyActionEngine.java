package com.mccompanion.core.body.daily;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic, loader-independent state machine for the eleven ordinary-player actions.
 * Adapters provide facts and execute one typed vanilla command; they do not select phases.
 */
public final class DailyActionEngine {
    public enum Status { RUNNING, PAUSED, COMPLETE, BLOCKED, UNCERTAIN, CANCELLED }

    public interface Adapter {
        DailyActionCommand.CommandResult execute(DailyActionCommand command);

        /** Maintains an accepted continuous command on every body tick. */
        default DailyActionCommand.CommandResult maintain(DailyActionCommand command) {
            return DailyActionCommand.CommandResult.success();
        }

        /** Releases transient controls or menu state owned by this action. */
        default void cleanup(Session session, String reason) { }
    }

    public record Observation(Status status, DailyActionKind kind, DailyActionPhase phase,
                               String code, Map<String, String> details) {
        public Observation {
            code = code == null ? "" : code;
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    public record Session(String sessionId, DailyActionRequest request, Status status,
                          DailyActionPhase phase, long startedTick, long lastTick,
                          int attempts, int flowIndex, int completedCycles,
                          Map<String, Integer> baseline, Map<String, String> phaseBaseline,
                          boolean dispatched, boolean initialized, boolean cleaned,
                          long pausedTicks, long pausedSince,
                          String selectedItemId, int selectedSourceSlot, String selectedStackDigest,
                          String selectedDestination,
                          String selectedTargetId, String secondaryTargetId,
                          DailyActionRequest.Position selectedTarget, String fishingHookId,
                          Observation observation) {
        public Session {
            baseline = baseline == null ? Map.of() : Map.copyOf(baseline);
            phaseBaseline = phaseBaseline == null ? Map.of() : Map.copyOf(phaseBaseline);
            selectedItemId = selectedItemId == null ? "" : selectedItemId;
            selectedStackDigest = selectedStackDigest == null ? "" : selectedStackDigest;
            selectedDestination = selectedDestination == null ? "" : selectedDestination;
            selectedTargetId = selectedTargetId == null ? "" : selectedTargetId;
            secondaryTargetId = secondaryTargetId == null ? "" : secondaryTargetId;
            fishingHookId = fishingHookId == null ? "" : fishingHookId;
        }
    }

    public record TickResult(Session session, Observation observation) { }

    private static final Map<DailyActionKind, List<DailyActionPhase>> FLOWS = flows();
    private static final int MAX_SESSIONS = 256;
    private final Map<String, State> sessions = new HashMap<>();

    public synchronized Session start(String sessionId, DailyActionRequest request, long tick) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (request == null) throw new IllegalArgumentException("request is required");
        if (sessions.containsKey(sessionId)) throw new IllegalArgumentException("session already exists");
        if (sessions.size() >= MAX_SESSIONS) throw new IllegalStateException("DAILY_ACTION_SESSION_LIMIT");
        State state = new State(sessionId, request, flowFor(request), tick);
        sessions.put(sessionId, state);
        return state.view();
    }

    public synchronized Session inspect(String sessionId) { return require(sessionId).view(); }

    public synchronized boolean canStart() { return sessions.size() < MAX_SESSIONS; }

    public synchronized void remove(String sessionId) { sessions.remove(sessionId); }

    public synchronized void forget(String sessionId) { sessions.remove(sessionId); }

    public synchronized Session restore(String sessionId, DailyActionRequest request, DailyActionPhase phase,
                                        Status status, long startedTick, long lastTick, int attempts,
                                        int completedCycles, Map<String, Integer> baseline, boolean dispatched) {
        return restore(sessionId, request, phase, status, startedTick, lastTick, attempts,
                completedCycles, baseline, dispatched, 0L);
    }

    public synchronized Session restore(String sessionId, DailyActionRequest request, DailyActionPhase phase,
                                        Status status, long startedTick, long lastTick, int attempts,
                                         int completedCycles, Map<String, Integer> baseline, boolean dispatched,
                                         long pausedTicks) {
        List<DailyActionPhase> flow = flowFor(request);
        if (dispatched || completedCycles != 0 || phase != flow.get(0)) {
            throw new IllegalArgumentException("complete Session snapshot is required for in-flight restore");
        }
        if (sessions.containsKey(sessionId)) throw new IllegalArgumentException("session already exists");
        if (sessions.size() >= MAX_SESSIONS) throw new IllegalStateException("DAILY_ACTION_SESSION_LIMIT");
        State state = new State(sessionId, request, flow, startedTick);
        state.phase = phase;
        state.status = status;
        state.lastTick = lastTick;
        state.attempts = Math.max(0, attempts);
        state.completedCycles = Math.max(0, completedCycles);
        state.pausedTicks = Math.max(0, pausedTicks);
        state.baseline = baseline == null ? Map.of() : Map.copyOf(baseline);
        state.phaseBaseline = Map.of();
        state.dispatched = dispatched;
        state.initialized = false;
        state.observation = state.observe(status, phase, "RESTORED");
        sessions.put(sessionId, state);
        return state.view();
    }

    /** Restores the complete shared state without re-selecting targets or replaying effects. */
    public synchronized Session restore(Session saved) {
        if (saved == null) throw new IllegalArgumentException("saved session is required");
        if (sessions.containsKey(saved.sessionId())) throw new IllegalArgumentException("session already exists");
        if (sessions.size() >= MAX_SESSIONS) throw new IllegalStateException("DAILY_ACTION_SESSION_LIMIT");
        List<DailyActionPhase> flow = flowFor(saved.request());
        if (saved.phase() != DailyActionPhase.COMPLETE && !flow.contains(saved.phase())) {
            throw new IllegalArgumentException("saved phase does not belong to request flow");
        }
        State state = new State(saved.sessionId(), saved.request(), flow, saved.startedTick());
        state.phase = saved.phase();
        state.status = saved.status();
        state.lastTick = saved.lastTick();
        state.attempts = Math.max(0, saved.attempts());
        state.completedCycles = Math.max(0, saved.completedCycles());
        state.baseline = saved.baseline();
        state.phaseBaseline = saved.phaseBaseline();
        state.dispatched = saved.dispatched();
        state.initialized = saved.initialized();
        state.cleaned = saved.cleaned();
        state.pausedTicks = Math.max(0, saved.pausedTicks());
        state.pausedSince = saved.pausedSince();
        state.selectedItemId = saved.selectedItemId();
        state.selectedSourceSlot = saved.selectedSourceSlot();
        state.selectedStackDigest = saved.selectedStackDigest();
        state.selectedDestination = saved.selectedDestination();
        state.selectedTargetId = saved.selectedTargetId();
        state.secondaryTargetId = saved.secondaryTargetId();
        state.selectedTarget = saved.selectedTarget();
        state.fishingHookId = saved.fishingHookId();
        state.observation = saved.observation() == null
                ? state.observe(saved.status(), saved.phase(), "RESTORED") : saved.observation();
        sessions.put(saved.sessionId(), state);
        return state.view();
    }

    public synchronized Session pause(String sessionId, String reason) {
        State state = require(sessionId);
        Map<String, String> details = state.observation == null ? Map.of() : state.observation.details();
        if (state.status == Status.RUNNING) {
            state.status = Status.PAUSED;
            state.pausedSince = state.lastTick;
        }
        state.observation = state.observe(Status.PAUSED, state.phase,
                reason == null ? "PAUSED" : reason, details);
        return state.view();
    }

    public synchronized Session pause(String sessionId, String reason, Adapter adapter) {
        Session paused = pause(sessionId, reason);
        State state = require(sessionId);
        try {
            adapter.cleanup(paused, reason == null ? "PAUSED" : reason);
        } catch (RuntimeException cleanupFailure) {
            state.observation = state.observe(Status.PAUSED, state.phase, "CLEANUP_FAILED",
                    merge(paused.observation().details(),
                            Map.of("cleanupFailure", cleanupFailure.getClass().getSimpleName())));
        }
        return state.view();
    }

    public synchronized Session resume(String sessionId, long tick) {
        State state = require(sessionId);
        if (state.status == Status.PAUSED) {
            Map<String, String> details = state.observation == null
                    ? Map.of() : state.observation.details();
            state.status = Status.RUNNING;
            state.pausedTicks += Math.max(0, tick - state.pausedSince);
            state.lastTick = tick;
            state.observation = state.observe(Status.RUNNING, state.phase, "RESUMED", details);
        }
        return state.view();
    }

    public synchronized Session cancel(String sessionId, String reason) {
        State state = require(sessionId);
        Map<String, String> details = state.observation == null ? Map.of() : state.observation.details();
        if (!terminal(state.status)) {
            state.status = Status.CANCELLED;
            state.observation = state.observe(Status.CANCELLED, state.phase,
                    reason == null ? "CANCELLED" : reason, details);
        }
        return state.view();
    }

    public synchronized Session cancel(String sessionId, String reason, Adapter adapter) {
        State state = require(sessionId);
        Session cancelled = cancel(sessionId, reason);
        if (!state.cleaned) {
            try {
                adapter.cleanup(cancelled, reason == null ? "CANCELLED" : reason);
            } catch (RuntimeException cleanupFailure) {
                state.status = Status.UNCERTAIN;
                state.observation = state.observe(Status.UNCERTAIN, state.phase, "CLEANUP_FAILED",
                        merge(cancelled.observation().details(),
                                Map.of("cleanupFailure", cleanupFailure.getClass().getSimpleName())));
            } finally {
                state.cleaned = true;
            }
        }
        return state.view();
    }

    public synchronized TickResult tick(String sessionId, DailyActionSnapshot snapshot, Adapter adapter) {
        if (snapshot == null || adapter == null) throw new IllegalArgumentException("snapshot and adapter are required");
        State state = require(sessionId);
        state.lastTick = snapshot.tick();
        if (state.status == Status.PAUSED || terminal(state.status)) return new TickResult(state.view(), state.observation);
        if (!state.initialized) {
            initialize(state, snapshot);
        }
        if (state.selectedTarget == null) state.selectedTarget = selectTarget(state.request, snapshot);
        if (!snapshot.alive()) return finish(state, Status.BLOCKED, "BODY_DEAD", snapshot, adapter);
        if (snapshot.tick() - state.startedTick - state.pausedTicks > state.request.maxTicks()) {
            return finish(state, Status.BLOCKED, "DAILY_ACTION_TIMEOUT", snapshot, adapter);
        }
        String invariant = invariantFailure(state, snapshot);
        if (invariant != null) return finish(state, Status.BLOCKED, invariant, snapshot, adapter);
        if (state.phase == DailyActionPhase.COMPLETE) return finish(state, Status.COMPLETE, "VERIFIED", snapshot, adapter);

        if (satisfied(state, snapshot)) {
            if (state.phase == DailyActionPhase.CAST_LINE && snapshot.fish() != null) {
                state.fishingHookId = snapshot.fish().hookId();
            }
            state.phase = state.advance();
            state.dispatched = false;
            state.phaseBaseline = phaseBaseline(state, snapshot);
            if (state.cycleRestarted) {
                state.resetCycle();
                initialize(state, snapshot);
            }
            if (state.phase == DailyActionPhase.COMPLETE) return finish(state, Status.COMPLETE, "VERIFIED", snapshot, adapter);
            state.observation = state.observe(Status.RUNNING, state.phase, "PHASE_ADVANCED");
            return new TickResult(state.view(), state.observation);
        }
        String blocked = blockedFact(state, snapshot);
        if (blocked != null) return finish(state, Status.BLOCKED, blocked, snapshot, adapter);
        if (!state.dispatched) {
            DailyActionCommand command = command(state);
            DailyActionCommand.CommandResult result = adapter.execute(command);
            state.attempts++;
            state.dispatched = result.accepted();
            if (!result.accepted()) {
                return finish(state, result.uncertain() ? Status.UNCERTAIN : Status.BLOCKED,
                        result.code(), snapshot, result.details(), adapter);
            }
            state.observation = state.observe(Status.RUNNING, state.phase, result.code(),
                    merge(result.details(), postcondition(state, snapshot)));
        } else if (continuous(state.phase)) {
            DailyActionCommand.CommandResult result = adapter.maintain(command(state));
            if (!result.accepted()) {
                return finish(state, result.uncertain() ? Status.UNCERTAIN : Status.BLOCKED,
                        result.code(), snapshot, result.details(), adapter);
            }
            state.observation = state.observe(Status.RUNNING, state.phase,
                    result.code(), merge(result.details(), postcondition(state, snapshot)));
        } else {
            state.observation = state.observe(Status.RUNNING, state.phase, "WAITING_POSTCONDITION",
                    postcondition(state, snapshot));
        }
        return new TickResult(state.view(), state.observation);
    }

    private static void initialize(State state, DailyActionSnapshot snapshot) {
        state.initialized = true;
        state.baseline = baseline(state.request, snapshot);
        state.selectedTarget = selectTarget(state.request, snapshot);
        selectAnimals(state, snapshot);
        selectEntityTarget(state, snapshot);
        state.selectedItemId = selectItem(state, snapshot);
        state.selectedSourceSlot = selectItemSlot(state.request, snapshot, state.selectedItemId);
        state.selectedStackDigest = selectStackDigest(snapshot, state.selectedItemId, state.selectedSourceSlot);
        state.selectedDestination = resolveDestination(state.request, snapshot,
                state.selectedItemId, state.selectedSourceSlot);
        state.phaseBaseline = phaseBaseline(state, snapshot);
    }

    private static void selectAnimals(State state, DailyActionSnapshot snapshot) {
        state.selectedTargetId = state.request.targetId();
        state.secondaryTargetId = state.request.secondaryTargetId();
        if (state.request.kind() != DailyActionKind.BREED_ANIMALS
                || !state.selectedTargetId.isBlank() && !state.secondaryTargetId.isBlank()) return;
        List<DailyActionSnapshot.AnimalCandidate> animals = snapshot.animalCandidates().stream()
                .filter(value -> value.alive() && value.adult() && !value.inLove()
                        && value.foodCompatible())
                .filter(value -> state.request.itemId().isBlank()
                        || value.type().equals(state.request.itemId()))
                .sorted(java.util.Comparator.comparingInt((DailyActionSnapshot.AnimalCandidate value)
                                -> distance(snapshot.position(), value.position()))
                        .thenComparing(value -> value.id().toString()))
                .toList();
        if (animals.size() >= 2) {
            state.selectedTargetId = animals.get(0).id().toString();
            state.secondaryTargetId = animals.get(1).id().toString();
        }
    }

    private static void selectEntityTarget(State state, DailyActionSnapshot snapshot) {
        if (state.request.kind() == DailyActionKind.USE_VEHICLE) {
            if (!state.request.targetId().isBlank()) {
                state.selectedTargetId = state.request.targetId();
                snapshot.vehicleCandidates().stream()
                        .filter(value -> value.alive() && id(value.id()).equals(state.request.targetId()))
                        .findFirst().ifPresent(value -> state.selectedTarget = value.position());
                if (snapshot.vehicle() != null && snapshot.vehicle().found()
                        && id(snapshot.vehicle().id()).equals(state.request.targetId())
                        && state.selectedTarget == null) {
                    state.selectedTarget = snapshot.vehicle().position();
                }
                return;
            }
            snapshot.vehicleCandidates().stream()
                    .filter(value -> value.alive() && value.position().equals(state.selectedTarget)
                            && (state.request.itemId().isBlank()
                            || value.type().equals(state.request.itemId())))
                    .findFirst().ifPresent(value -> state.selectedTargetId = id(value.id()));
            if (state.selectedTargetId.isBlank() && snapshot.vehicle() != null
                    && snapshot.vehicle().found() && vehicleMatches(state.request,
                    id(snapshot.vehicle().id()), snapshot.vehicle().type())) {
                state.selectedTargetId = id(snapshot.vehicle().id());
                if (state.selectedTarget == null) state.selectedTarget = snapshot.vehicle().position();
            }
        } else if (state.request.kind() == DailyActionKind.TRADE_WITH_VILLAGER) {
            snapshot.entityCandidates().stream()
                    .filter(value -> value.alive() && value.visible()
                            && (state.request.targetId().isBlank()
                            || id(value.id()).equals(state.request.targetId())))
                    .filter(value -> value.type().equals("minecraft:villager"))
                    .min(java.util.Comparator.comparingInt(value -> distance(snapshot.position(), value.position())))
                    .ifPresent(value -> {
                        state.selectedTargetId = id(value.id());
                        state.selectedTarget = value.position();
                    });
        }
    }

    private static Map<String, String> phaseBaseline(State state, DailyActionSnapshot snapshot) {
        Map<String, String> values = new HashMap<>();
        values.put("phase", state.phase.name());
        values.put("xp", Integer.toString(snapshot.experienceLevel()));
        values.put("lapis", Integer.toString(snapshot.enchant() == null
                ? snapshot.menu() == null ? 0 : snapshot.menu().lapisCount()
                : snapshot.enchant().lapisAvailable()));
        values.put("tradeUses", Integer.toString(snapshot.trade() == null
                ? snapshot.menu() == null ? 0 : snapshot.menu().offerUses() : snapshot.trade().uses()));
        values.put("tradeOutput", Integer.toString(snapshot.trade() == null ? 0 : snapshot.trade().outputReceived()));
        values.put("tradeInputA", snapshot.trade() == null ? "" : snapshot.trade().inputA());
        values.put("tradeInputB", snapshot.trade() == null ? "" : snapshot.trade().inputB());
        values.put("tradeResult", snapshot.trade() == null ? "" : snapshot.trade().output());
        values.put("tradeInputACount", Integer.toString(
                snapshot.trade() == null ? 0 : snapshot.trade().inputACount()));
        values.put("tradeInputBCount", Integer.toString(
                snapshot.trade() == null ? 0 : snapshot.trade().inputBCount()));
        values.put("tradeResultCount", Integer.toString(
                snapshot.trade() == null ? 0 : snapshot.trade().outputCount()));
        values.put("menuItemDigest", snapshot.enchant() == null
                ? detail(snapshot, "itemDigest") : snapshot.enchant().itemDigest());
        values.put("enchantCost", Integer.toString(snapshot.enchant() == null ? 0 : snapshot.enchant().cost()));
        values.put("brewResultDigest", snapshot.brew() == null
                ? detail(snapshot, "resultDigest") : snapshot.brew().digest());
        values.put("brewInventoryDelta", Integer.toString(snapshot.brew() == null
                ? 0 : snapshot.brew().inventoryResultDelta()));
        return Map.copyOf(values);
    }

    private static boolean continuous(DailyActionPhase phase) {
        return phase == DailyActionPhase.NAVIGATE_BED
                || phase == DailyActionPhase.NAVIGATE_BUCKET
                || phase == DailyActionPhase.APPROACH_VEHICLE
                || phase == DailyActionPhase.TRAVEL_VEHICLE
                || phase == DailyActionPhase.NAVIGATE_FISH
                || phase == DailyActionPhase.VERIFY_LOOT
                || phase == DailyActionPhase.NAVIGATE_CROP
                || phase == DailyActionPhase.PICKUP_CROP
                || phase == DailyActionPhase.APPROACH_FIRST_ANIMAL
                || phase == DailyActionPhase.APPROACH_SECOND_ANIMAL
                || phase == DailyActionPhase.APPROACH_TRADE
                || phase == DailyActionPhase.NAVIGATE_ENCHANT
                || phase == DailyActionPhase.NAVIGATE_BREW
                || phase == DailyActionPhase.GLIDE
                || phase == DailyActionPhase.LAND;
    }

    private TickResult finish(State state, Status status, String code, DailyActionSnapshot snapshot,
                              Adapter adapter) {
        return finish(state, status, code, snapshot, Map.of(), adapter);
    }

    private TickResult finish(State state, Status status, String code, DailyActionSnapshot snapshot,
                              Map<String, String> commandDetails, Adapter adapter) {
        state.status = status;
        state.observation = state.observe(status, state.phase, code, merge(commandDetails, postcondition(state, snapshot)));
        if (!state.cleaned) {
            try {
                adapter.cleanup(state.view(), code);
                state.cleaned = true;
            } catch (RuntimeException cleanupFailure) {
                state.status = Status.UNCERTAIN;
                state.observation = state.observe(Status.UNCERTAIN, state.phase, "CLEANUP_FAILED",
                        merge(Map.of("cleanupFailure", cleanupFailure.getClass().getSimpleName()),
                                postcondition(state, snapshot)));
            }
        }
        return new TickResult(state.view(), state.observation);
    }

    private static List<DailyActionPhase> flowFor(DailyActionRequest request) {
        DailyActionKind kind = request.kind();
        return switch (kind) {
            case SLEEP_AT_BED -> request.action().equals("WAKE")
                    ? List.of(DailyActionPhase.WAKE)
                    : List.of(DailyActionPhase.FIND_BED, DailyActionPhase.NAVIGATE_BED, DailyActionPhase.SLEEP);
            case USE_WATER_BUCKET -> List.of(DailyActionPhase.NAVIGATE_BUCKET,
                    request.action().equals("EMPTY")
                            ? DailyActionPhase.EMPTY_BUCKET : DailyActionPhase.FILL_BUCKET);
            case USE_VEHICLE -> switch (request.action()) {
                case "DISMOUNT" -> List.of(DailyActionPhase.DISEMBARK_VEHICLE);
                case "MOUNT" -> List.of(DailyActionPhase.APPROACH_VEHICLE, DailyActionPhase.BOARD_VEHICLE);
                default -> List.of(DailyActionPhase.APPROACH_VEHICLE, DailyActionPhase.BOARD_VEHICLE,
                        DailyActionPhase.TRAVEL_VEHICLE);
            };
            default -> FLOWS.get(kind);
        };
    }

    private static boolean terminal(Status status) {
        return status == Status.COMPLETE || status == Status.BLOCKED || status == Status.UNCERTAIN || status == Status.CANCELLED;
    }

    private static DailyActionCommand command(State s) {
        DailyActionRequest r = s.request;
        return switch (s.phase) {
            case VALIDATE -> new DailyActionCommand.Equip(r.action(), s.selectedItemId,
                    s.selectedSourceSlot, s.selectedDestination, s.selectedStackDigest);
            case FIND_BED, SLEEP, WAKE -> new DailyActionCommand.Bed(s.phase.name(), s.selectedTarget);
            case NAVIGATE_BED -> new DailyActionCommand.Navigate(r.kind(), s.selectedTarget, "BED");
            case NAVIGATE_BUCKET -> new DailyActionCommand.Navigate(r.kind(), r.target(), "BUCKET");
            case FILL_BUCKET, EMPTY_BUCKET -> new DailyActionCommand.Bucket(s.phase.name(), r.target(), r.direction());
            case APPROACH_VEHICLE, BOARD_VEHICLE, TRAVEL_VEHICLE, DISEMBARK_VEHICLE ->
                    s.phase == DailyActionPhase.APPROACH_VEHICLE
                            ? new DailyActionCommand.NavigateEntity(r.kind(), s.selectedTargetId, "VEHICLE")
                            : new DailyActionCommand.Vehicle(s.phase.name(), s.selectedTargetId,
                                    s.phase == DailyActionPhase.TRAVEL_VEHICLE ? r.target() : s.selectedTarget,
                                    r.direction());
            case EQUIP_ROD -> new DailyActionCommand.Equip("EQUIP", s.selectedItemId,
                    s.selectedSourceSlot, "MAIN_HAND", s.selectedStackDigest);
            case NAVIGATE_FISH -> new DailyActionCommand.Navigate(r.kind(), r.target(), "FISHING_WATER");
            case CAST_LINE, WAIT_BITE, REEL_LINE, VERIFY_LOOT ->
                    new DailyActionCommand.Fishing(s.phase.name(), s.selectedItemId, r.target());
            case NAVIGATE_CROP -> new DailyActionCommand.Navigate(r.kind(), s.selectedTarget, "CROP");
            case SCAN_CROP, HARVEST_CROP, PICKUP_CROP, REPLANT_CROP, VERIFY_CROP ->
                    new DailyActionCommand.Crop(s.phase.name(), s.selectedTarget, r.itemId(), s.selectedItemId);
            case APPROACH_FIRST_ANIMAL -> new DailyActionCommand.NavigateEntity(r.kind(),
                    s.selectedTargetId, "FIRST_ANIMAL");
            case APPROACH_SECOND_ANIMAL -> new DailyActionCommand.NavigateEntity(r.kind(),
                    s.secondaryTargetId, "SECOND_ANIMAL");
            case FEED_FIRST, FEED_SECOND, WAIT_BABY, VERIFY_BREED ->
                    new DailyActionCommand.Breed(s.phase.name(), s.selectedTargetId, s.secondaryTargetId, s.selectedItemId);
            case APPROACH_TRADE -> new DailyActionCommand.NavigateEntity(r.kind(), s.selectedTargetId, "VILLAGER");
            case OPEN_TRADE, SELECT_TRADE, EXECUTE_TRADE, VERIFY_TRADE ->
                    new DailyActionCommand.Trade(s.phase.name(), s.selectedTargetId, r.slot());
            case NAVIGATE_ENCHANT -> new DailyActionCommand.Navigate(r.kind(), r.target(), "ENCHANTING_TABLE");
            case OPEN_ENCHANT, SELECT_ENCHANT, APPLY_ENCHANT, VERIFY_ENCHANT ->
                    new DailyActionCommand.Enchant(s.phase.name(), r.target(), r.slot());
            case NAVIGATE_BREW -> new DailyActionCommand.Navigate(r.kind(), r.target(), "BREWING_STAND");
            case OPEN_BREW, LOAD_BREW, WAIT_BREW, TAKE_BREW, VERIFY_BREW ->
                    new DailyActionCommand.Brew(s.phase.name(), r.target(), s.selectedItemId,
                            r.slot(), r.quantity());
            case EQUIP_ELYTRA, START_GLIDE, GLIDE, LAND -> new DailyActionCommand.Glide(
                    s.phase.name(), s.selectedTarget, r.direction(), s.selectedItemId,
                    s.selectedSourceSlot, s.selectedStackDigest);
            case COMPLETE -> throw new IllegalStateException("terminal phase");
        };
    }

    private static boolean satisfied(State s, DailyActionSnapshot x) {
        DailyActionRequest r = s.request;
        return switch (s.phase) {
            case VALIDATE -> equipped(x, s);
            case FIND_BED -> selectedBed(s, x) != null;
            case NAVIGATE_BED -> bedNear(x.position(), s.selectedTarget);
            case SLEEP -> x.sleeping() && x.bed() != null
                    && s.selectedTarget != null && s.selectedTarget.equals(x.bed().position());
            case WAKE -> !x.sleeping() && s.dispatched;
            case NAVIGATE_BUCKET -> near(x.position(), r.target());
            case FILL_BUCKET, EMPTY_BUCKET -> bucketMatches(x.bucket(), r, s.phase)
                    && (x.count("minecraft:water_bucket") != s.baseline.getOrDefault("minecraft:water_bucket", 0)
                    || x.count("minecraft:bucket") != s.baseline.getOrDefault("minecraft:bucket", 0));
            case APPROACH_VEHICLE -> x.vehicle() != null && x.vehicle().seated()
                    && (s.selectedTargetId.isBlank() || s.selectedTargetId.equals(id(x.vehicle().id())))
                    && (r.itemId().isBlank() || r.itemId().equals(x.vehicle().type()))
                    || near(x.position(), s.selectedTarget);
            case BOARD_VEHICLE -> x.vehicle() != null && x.vehicle().seated()
                    && (s.selectedTargetId.isBlank() || s.selectedTargetId.equals(id(x.vehicle().id())));
            case TRAVEL_VEHICLE -> x.vehicle() != null && x.vehicle().found() && !x.vehicle().stuck()
                    && x.vehicle().seated() && (s.selectedTargetId.isBlank()
                    || s.selectedTargetId.equals(id(x.vehicle().id())))
                    && near(x.vehicle().position(), r.target());
            case DISEMBARK_VEHICLE -> x.vehicle() == null || !x.vehicle().seated();
            case EQUIP_ROD -> equipmentIs(x, "MAIN_HAND", s.selectedItemId);
            case NAVIGATE_FISH -> r.target() == null || near(x.position(), r.target());
            case CAST_LINE -> x.fish() != null && x.fish().bobberCast();
            case WAIT_BITE -> x.fish() != null && x.fish().biting();
            case REEL_LINE -> x.fish() != null && !x.fish().hookAlive();
            case VERIFY_LOOT -> x.fish() != null && x.fish().lootVerified();
            case SCAN_CROP -> selectedCrop(s, x) != null;
            case NAVIGATE_CROP -> near(x.position(), s.selectedTarget);
            case HARVEST_CROP -> x.crop() != null && x.crop().harvested();
            case PICKUP_CROP -> x.crop() != null && x.crop().pickedUp();
            case REPLANT_CROP -> x.crop() != null && x.crop().replanted();
            case VERIFY_CROP -> x.crop() != null && x.crop().age() == 0
                    && x.crop().replanted() && x.crop().position().equals(s.selectedTarget)
                    && (x.crop().cropId().isBlank() || x.crop().cropId().equals(r.itemId()))
                    && x.crop().harvestedCount() > 0 && x.crop().seedConsumed() > 0;
            case APPROACH_FIRST_ANIMAL -> near(x.position(), animalPosition(x, s.selectedTargetId));
            case FEED_FIRST -> x.breed() != null && x.breed().firstFed();
            case APPROACH_SECOND_ANIMAL -> near(x.position(), animalPosition(x, s.secondaryTargetId));
            case FEED_SECOND -> x.breed() != null && x.breed().secondFed();
            case WAIT_BABY -> x.breed() != null && x.breed().babyVerified();
            case VERIFY_BREED -> x.breed() != null && x.breed().babyCount() > 0
                    && x.breed().foodConsumed() >= 2
                    && s.selectedTargetId.equals(id(x.breed().firstId()))
                    && s.secondaryTargetId.equals(id(x.breed().secondId()));
            case APPROACH_TRADE -> near(x.position(), entityPosition(x, s.selectedTargetId));
            case OPEN_TRADE -> menu(x, "MERCHANT");
            case SELECT_TRADE -> tradeMatches(x.trade(), s, r) && x.trade().inputsReady();
            case EXECUTE_TRADE -> tradeMatches(x.trade(), s, r)
                    && x.trade().uses() > phaseInt(s, "tradeUses", x.trade().uses())
                    && x.trade().outputReceived() > 0;
            case VERIFY_TRADE -> tradeMatches(x.trade(), s, r)
                    && x.trade().outputReceived() > 0
                    && x.trade().inputAConsumed() > 0
                    && (x.trade().inputB().isBlank() || x.trade().inputBConsumed() > 0);
            case NAVIGATE_ENCHANT -> near(x.position(), r.target());
            case OPEN_ENCHANT -> menu(x, "ENCHANTMENT");
            case SELECT_ENCHANT -> enchantMatches(x.enchant(), r) && x.enchant().inputsReady();
            case APPLY_ENCHANT -> enchantMatches(x.enchant(), r)
                    && x.enchant().levelsSpent() > 0 && x.enchant().lapisSpent() > 0
                    && !x.enchant().itemDigest().equals(s.phaseBaseline.getOrDefault("menuItemDigest", ""));
            case VERIFY_ENCHANT -> enchantMatches(x.enchant(), r)
                    && x.enchant().levelsSpent() > 0 && x.enchant().lapisSpent() > 0;
            case NAVIGATE_BREW -> near(x.position(), r.target());
            case OPEN_BREW -> menu(x, "BREWING");
            case LOAD_BREW -> brewMatches(x.brew(), r) && x.brew().inputsReady()
                    && x.brew().brewingTicks() > 0;
            case WAIT_BREW -> brewMatches(x.brew(), r) && x.brew().brewingTicks() == 0
                    && !x.brew().digest().equals(s.phaseBaseline.getOrDefault("brewResultDigest", ""));
            case TAKE_BREW -> brewMatches(x.brew(), r) && x.brew().resultsTaken() >= r.quantity();
            case VERIFY_BREW -> brewMatches(x.brew(), r)
                    && x.brew().resultsTaken() >= r.quantity()
                    && x.brew().inventoryResultDelta() >= r.quantity();
            case EQUIP_ELYTRA -> equipmentIs(x, "CHEST", "minecraft:elytra");
            case START_GLIDE -> x.fallFlying();
            case GLIDE -> x.glide() != null && x.glide().targetReached()
                    && x.glide().terrainSafe() && x.fallFlying() && !x.onGround();
            case LAND -> x.onGround() && !x.fallFlying();
            case COMPLETE -> true;
        };
    }

    /** Identity/world/menu invariants are checked before a postcondition may advance. */
    private static String invariantFailure(State state, DailyActionSnapshot snapshot) {
        if (!snapshot.dimension().equals(state.request.dimension())) return "WORLD_CHANGED";
        if ((state.phase == DailyActionPhase.BOARD_VEHICLE
                || state.phase == DailyActionPhase.TRAVEL_VEHICLE)
                && snapshot.vehicle() != null && snapshot.vehicle().seated()
                && (!state.selectedTargetId.isBlank()
                && !state.selectedTargetId.equals(id(snapshot.vehicle().id()))
                || !state.request.itemId().isBlank()
                && !state.request.itemId().equals(snapshot.vehicle().type()))) {
            return "VEHICLE_CHANGED";
        }
        if (state.request.kind() == DailyActionKind.BREED_ANIMALS) {
            DailyActionSnapshot.AnimalCandidate first = animal(snapshot, state.selectedTargetId);
            DailyActionSnapshot.AnimalCandidate second = animal(snapshot, state.secondaryTargetId);
            if (first == null || !first.alive()) return "FIRST_ANIMAL_LOST";
            if (second == null || !second.alive()) return "SECOND_ANIMAL_LOST";
            boolean preparing = state.phase == DailyActionPhase.APPROACH_FIRST_ANIMAL
                    || state.phase == DailyActionPhase.FEED_FIRST
                    || state.phase == DailyActionPhase.APPROACH_SECOND_ANIMAL
                    || state.phase == DailyActionPhase.FEED_SECOND;
            if (preparing && (!first.adult() || !second.adult())) return "ANIMAL_NOT_ADULT";
            // Once an animal has been fed it is expected to consume the last available
            // food while entering love mode; requiring food to remain in the inventory
            // during FEED_SECOND would incorrectly abort before WAIT_BABY.
            if (preparing && ((!first.inLove() && !first.foodCompatible())
                    || (!second.inLove() && !second.foodCompatible()))) return "ANIMAL_FOOD_INVALID";
            if (!state.request.itemId().isBlank()
                    && (!first.type().equals(state.request.itemId())
                    || !second.type().equals(state.request.itemId()))) return "ANIMAL_TYPE_CHANGED";
        }
        if (state.phase == DailyActionPhase.WAIT_BITE || state.phase == DailyActionPhase.REEL_LINE) {
            DailyActionSnapshot.ItemFact rod = snapshot.equipment().get("MAIN_HAND");
            if (rod == null || !rod.itemId().equals(state.selectedItemId)) return "FISHING_ROD_MISSING";
            if (rod.maxDamage() > 0 && rod.damage() >= rod.maxDamage() - 1) return "FISHING_ROD_BROKEN";
            if (snapshot.fish() == null || state.fishingHookId.isBlank()
                    || !state.fishingHookId.equals(snapshot.fish().hookId())) return "FISHING_HOOK_LOST";
            if (state.phase == DailyActionPhase.WAIT_BITE && !snapshot.fish().hookAlive()) {
                return "FISHING_HOOK_LOST";
            }
        }
        if ((state.phase == DailyActionPhase.SELECT_TRADE
                || state.phase == DailyActionPhase.EXECUTE_TRADE) && !menu(snapshot, "MERCHANT")) {
            return "TRADE_MENU_INVALIDATED";
        }
        if ((state.phase == DailyActionPhase.SELECT_TRADE
                || state.phase == DailyActionPhase.EXECUTE_TRADE
                || state.phase == DailyActionPhase.VERIFY_TRADE)
                && (snapshot.trade() == null || !snapshot.trade().valid())) return "TRADE_TARGET_INVALIDATED";
        if ((state.phase == DailyActionPhase.SELECT_TRADE
                || state.phase == DailyActionPhase.EXECUTE_TRADE
                || state.phase == DailyActionPhase.VERIFY_TRADE)
                && !sameTradeOffer(state, snapshot.trade())) return "TRADE_OFFER_CHANGED";
        if ((state.phase == DailyActionPhase.SELECT_ENCHANT
                || state.phase == DailyActionPhase.APPLY_ENCHANT
                || state.phase == DailyActionPhase.VERIFY_ENCHANT)
                && !menu(snapshot, "ENCHANTMENT")) return "ENCHANTMENT_MENU_INVALIDATED";
        if ((state.phase == DailyActionPhase.SELECT_ENCHANT
                || state.phase == DailyActionPhase.APPLY_ENCHANT
                || state.phase == DailyActionPhase.VERIFY_ENCHANT)
                && (snapshot.enchant() == null || !snapshot.enchant().valid())) {
            return "ENCHANTMENT_TARGET_INVALIDATED";
        }
        if ((state.phase == DailyActionPhase.SELECT_ENCHANT
                || state.phase == DailyActionPhase.APPLY_ENCHANT
                || state.phase == DailyActionPhase.VERIFY_ENCHANT)
                && !snapshot.enchant().itemId().isBlank()
                && !snapshot.enchant().itemId().equals(state.request.itemId())) {
            return "ENCHANTMENT_ITEM_CHANGED";
        }
        if (state.phase == DailyActionPhase.APPLY_ENCHANT
                && snapshot.enchant().levelsSpent() == 0 && snapshot.enchant().lapisSpent() == 0
                && phaseInt(state, "enchantCost", snapshot.enchant().cost()) != snapshot.enchant().cost()) {
            return "ENCHANTMENT_OPTIONS_CHANGED";
        }
        if (state.phase == DailyActionPhase.APPLY_ENCHANT
                && snapshot.enchant().levelsSpent() == 0 && snapshot.enchant().lapisSpent() == 0
                && !state.phaseBaseline.getOrDefault(
                "menuItemDigest", snapshot.enchant().itemDigest()).equals(snapshot.enchant().itemDigest())) {
            return "ENCHANTMENT_ITEM_CHANGED";
        }
        if (state.phase == DailyActionPhase.VERIFY_ENCHANT
                && !state.phaseBaseline.getOrDefault(
                "menuItemDigest", snapshot.enchant().itemDigest()).equals(snapshot.enchant().itemDigest())) {
            return "ENCHANTMENT_ITEM_CHANGED";
        }
        if ((state.phase == DailyActionPhase.LOAD_BREW
                || state.phase == DailyActionPhase.WAIT_BREW
                || state.phase == DailyActionPhase.TAKE_BREW)
                && !menu(snapshot, "BREWING")) return "BREWING_MENU_INVALIDATED";
        if ((state.phase == DailyActionPhase.LOAD_BREW
                || state.phase == DailyActionPhase.WAIT_BREW
                || state.phase == DailyActionPhase.TAKE_BREW
                || state.phase == DailyActionPhase.VERIFY_BREW)
                && (snapshot.brew() == null || !snapshot.brew().valid())) return "BREWING_TARGET_INVALIDATED";
        if ((state.phase == DailyActionPhase.LOAD_BREW
                || state.phase == DailyActionPhase.WAIT_BREW
                || state.phase == DailyActionPhase.TAKE_BREW
                || state.phase == DailyActionPhase.VERIFY_BREW)
                && !snapshot.brew().ingredientId().isBlank()
                && !snapshot.brew().ingredientId().equals(state.request.itemId())) {
            return "BREWING_INGREDIENT_CHANGED";
        }
        if (state.phase == DailyActionPhase.WAIT_BREW && snapshot.brew().brewingTicks() > 0
                && !snapshot.brew().digest().equals(
                state.phaseBaseline.getOrDefault("brewResultDigest", snapshot.brew().digest()))) {
            return "BREWING_CONTENT_CHANGED";
        }
        if (snapshot.menu() != null && snapshot.menu().details().containsKey("valid")
                && !detailBoolean(snapshot, "valid")) return "MENU_TARGET_INVALIDATED";
        return null;
    }

    private static String blockedFact(State s, DailyActionSnapshot x) {
        if (s.request.kind() == DailyActionKind.EQUIP_ITEM
                && !s.request.action().equals("UNEQUIP") && s.selectedSourceSlot < 0
                && !equipped(x, s)) {
            DailyActionSnapshot.ItemFact worn = x.equipment().get(
                    s.selectedDestination.isBlank() ? "MAIN_HAND" : s.selectedDestination);
            if (worn != null && worn.itemId().equals(s.selectedItemId)
                    && worn.maxDamage() > 0 && worn.damage() >= worn.maxDamage() - 1) {
                return "EQUIPMENT_UNUSABLE";
            }
            if (!s.request.itemId().isBlank() && x.itemCandidates().stream()
                    .anyMatch(value -> value.itemId().equals(s.request.itemId()) && !value.usable())) {
                return "EQUIPMENT_UNUSABLE";
            }
            return s.request.action().startsWith("BEST_") ? "USABLE_EQUIPMENT_NOT_FOUND" : "ITEM_NOT_FOUND";
        }
        if ((s.phase == DailyActionPhase.NAVIGATE_BUCKET
                || s.phase == DailyActionPhase.NAVIGATE_ENCHANT
                || s.phase == DailyActionPhase.NAVIGATE_BREW)
                && s.request.target() == null) return "TARGET_MISSING";
        if (s.phase == DailyActionPhase.APPROACH_TRADE && s.selectedTargetId.isBlank()) {
            return "VILLAGER_NOT_FOUND";
        }
        if ((s.phase == DailyActionPhase.APPROACH_FIRST_ANIMAL
                || s.phase == DailyActionPhase.APPROACH_SECOND_ANIMAL)
                && (s.selectedTargetId.isBlank() || s.secondaryTargetId.isBlank())) {
            return "ANIMALS_INSUFFICIENT";
        }
        return switch (s.phase) {
            case FIND_BED -> s.selectedTarget == null ? x.bedCandidates().stream()
                    .filter(value -> within(x.position(), value.position(), s.request.radius()))
                    .findFirst().map(value -> value.occupied() ? "BED_OCCUPIED"
                            : problem(value.problem(), "BED_UNUSABLE")).orElse("BED_NOT_FOUND")
                    : selectedBed(s, x) != null && !selectedBed(s, x).usable()
                    ? problem(selectedBed(s, x).problem(), "BED_UNUSABLE") : null;
            case NAVIGATE_BED, SLEEP -> s.phase == DailyActionPhase.SLEEP && x.sleeping() ? null
                    : selectedBed(s, x) == null ? "BED_LOST"
                    : !selectedBed(s, x).usable()
                    ? problem(selectedBed(s, x).problem(), "BED_UNUSABLE") : null;
            case FILL_BUCKET -> x.bucket() != null && !x.bucket().allowed() ? problem(x.bucket().problem(), "POSITION_NOT_ALLOWED")
                    : !s.dispatched && x.count("minecraft:bucket") < 1 ? "BUCKET_MISSING"
                    : !s.dispatched && x.bucket() != null && !x.bucket().sourceValid()
                    ? "WATER_SOURCE_INVALID" : null;
            case EMPTY_BUCKET -> x.bucket() != null && !x.bucket().allowed() ? problem(x.bucket().problem(), "POSITION_NOT_ALLOWED")
                    : !s.dispatched && x.count("minecraft:water_bucket") < 1 ? "WATER_BUCKET_MISSING"
                    : !s.dispatched && x.bucket() != null && !x.bucket().placementValid()
                    ? "WATER_TARGET_INVALID" : null;
            case APPROACH_VEHICLE -> s.selectedTarget == null ? "VEHICLE_NOT_FOUND" : null;
            case TRAVEL_VEHICLE -> x.vehicle() == null || !x.vehicle().found() ? "VEHICLE_LOST"
                    : x.vehicle() != null && x.vehicle().stuck() ? "VEHICLE_STUCK"
                    : x.vehicle() != null && !x.vehicle().safe() ? "VEHICLE_UNSAFE" : null;
            case EQUIP_ROD, CAST_LINE -> s.selectedSourceSlot < 0
                    && !equipmentIs(x, "MAIN_HAND", s.selectedItemId) ? "FISHING_ROD_MISSING" : null;
            case SCAN_CROP -> s.selectedTarget == null ? "MATURE_CROP_NOT_FOUND" : null;
            case REPLANT_CROP -> !s.dispatched && x.crop() != null && !x.crop().seedItem().isBlank()
                    && x.count(x.crop().seedItem()) < 1 ? "SEED_MISSING" : null;
            case FEED_FIRST, FEED_SECOND -> s.selectedTargetId.isBlank() || s.secondaryTargetId.isBlank()
                    ? "ANIMALS_INSUFFICIENT" : s.selectedItemId.isBlank() ? "BREEDING_FOOD_MISSING" : null;
            case SELECT_TRADE -> x.trade() != null && x.trade().disabled() ? "TRADE_DISABLED" : null;
            case EXECUTE_TRADE -> !s.dispatched && x.trade() != null && !x.trade().resourcesAvailable()
                    ? "TRADE_RESOURCES_INSUFFICIENT" : !s.dispatched && x.trade() != null
                    && !x.trade().inventorySpace()
                    ? "INVENTORY_FULL" : null;
            case SELECT_ENCHANT, APPLY_ENCHANT -> x.enchant() != null
                    && x.experienceLevel() < x.enchant().cost()
                    ? "EXPERIENCE_INSUFFICIENT" : x.enchant() != null && x.enchant().lapisAvailable() < 1
                    ? "LAPIS_INSUFFICIENT" : null;
            case LOAD_BREW -> !s.dispatched && x.brew() != null && !x.brew().materialsAvailable()
                    ? "BREWING_MATERIALS_INSUFFICIENT" : !s.dispatched && x.brew() != null
                    && !x.brew().fuelAvailable()
                    ? "BREWING_FUEL_MISSING" : null;
            case OPEN_BREW, WAIT_BREW, TAKE_BREW -> x.menu() != null && !x.menu().type().toUpperCase().contains("BREWING")
                    ? "BREWING_MENU_INVALID" : null;
            case EQUIP_ELYTRA -> !equipmentIs(x, "CHEST", "minecraft:elytra")
                    && !hasUsableCandidate(x, "minecraft:elytra") ? "ELYTRA_UNAVAILABLE" : null;
            case START_GLIDE -> x.glide() != null && !x.glide().terrainSafe()
                    ? problem(x.glide().problem(), "GLIDE_TERRAIN_UNSAFE")
                    : x.glide() != null && !x.glide().safeToStart()
                    ? problem(x.glide().problem(), "GLIDE_START_UNSAFE")
                    : x.onGround() ? "GLIDE_START_UNSAFE" : null;
            case GLIDE, LAND -> x.glide() != null && !x.glide().terrainSafe()
                    ? problem(x.glide().problem(), "GLIDE_TERRAIN_UNSAFE")
                    : s.phase == DailyActionPhase.GLIDE && x.onGround() && !x.glide().targetReached()
                    ? "GLIDE_ENDED_EARLY" : !equipmentIs(x, "CHEST", "minecraft:elytra")
                    ? "ELYTRA_UNAVAILABLE" : null;
            default -> null;
        };
    }

    private static boolean equipped(DailyActionSnapshot x, State state) {
        String slot = state.selectedDestination.isBlank() ? "MAIN_HAND" : state.selectedDestination;
        DailyActionSnapshot.ItemFact item = x.equipment().get(slot);
        if (state.request.action().equals("UNEQUIP")) return item == null;
        return item != null && item.itemId().equals(state.selectedItemId)
                && (item.maxDamage() <= 0 || item.damage() < item.maxDamage() - 1)
                && (state.selectedStackDigest.isBlank()
                || state.selectedStackDigest.equals(item.components().getOrDefault("digest", "")));
    }

    private static boolean equipmentIs(DailyActionSnapshot x, String slot, String itemId) {
        DailyActionSnapshot.ItemFact item = x.equipment().get(slot);
        return item != null && item.itemId().equals(itemId)
                && (item.maxDamage() <= 0 || item.damage() < item.maxDamage() - 1);
    }

    private static boolean tradeMatches(DailyActionSnapshot.TradeFact fact, State state,
                                        DailyActionRequest request) {
        boolean exhaustedByThisTrade = fact != null && fact.outputReceived() > 0
                && (state.phase == DailyActionPhase.EXECUTE_TRADE
                || state.phase == DailyActionPhase.VERIFY_TRADE);
        return fact != null && fact.valid() && (!fact.disabled() || exhaustedByThisTrade)
                && fact.offerIndex() == request.slot()
                && (state.selectedTargetId.isBlank() || fact.villagerId().equals(state.selectedTargetId));
    }

    private static boolean sameTradeOffer(State state, DailyActionSnapshot.TradeFact fact) {
        if (fact == null) return false;
        return state.phaseBaseline.getOrDefault("tradeInputA", fact.inputA()).equals(fact.inputA())
                && state.phaseBaseline.getOrDefault("tradeInputB", fact.inputB()).equals(fact.inputB())
                && state.phaseBaseline.getOrDefault("tradeResult", fact.output()).equals(fact.output())
                && phaseInt(state, "tradeInputACount", fact.inputACount()) == fact.inputACount()
                && phaseInt(state, "tradeInputBCount", fact.inputBCount()) == fact.inputBCount()
                && phaseInt(state, "tradeResultCount", fact.outputCount()) == fact.outputCount();
    }

    private static boolean enchantMatches(DailyActionSnapshot.EnchantFact fact,
                                          DailyActionRequest request) {
        return fact != null && fact.valid() && fact.option() == request.slot()
                && fact.station() != null && fact.station().equals(request.target())
                && fact.itemId().equals(request.itemId());
    }

    private static boolean brewMatches(DailyActionSnapshot.BrewFact fact,
                                       DailyActionRequest request) {
        return fact != null && fact.valid() && fact.station() != null
                && fact.station().equals(request.target())
                && fact.ingredientId().equals(request.itemId())
                && fact.bottleCount() >= request.quantity();
    }

    private static boolean bucketMatches(DailyActionSnapshot.BucketFact fact, DailyActionRequest request,
                                         DailyActionPhase phase) {
        if (fact == null || !fact.targetChanged() || fact.target() == null
                || !fact.target().equals(request.target())) return false;
        String expectedAction = phase == DailyActionPhase.FILL_BUCKET ? "FILL" : "EMPTY";
        if (!expectedAction.equalsIgnoreCase(fact.action())) return false;
        if (!request.direction().isBlank() && !fact.direction().isBlank()
                && !request.direction().equalsIgnoreCase(fact.direction())) return false;
        return phase == DailyActionPhase.FILL_BUCKET
                ? !fact.targetBlock().equals("minecraft:water")
                : fact.targetBlock().equals("minecraft:water");
    }

    private static boolean menu(DailyActionSnapshot x, String expected) {
        return x.menu() != null && x.menu().open() && x.menu().type().toUpperCase().contains(expected);
    }

    private static boolean near(DailyActionRequest.Position a, DailyActionRequest.Position b) {
        if (a == null || b == null || !a.dimension().equals(b.dimension())) return false;
        long dx = (long) a.x() - b.x(), dy = (long) a.y() - b.y(), dz = (long) a.z() - b.z();
        return dx * dx + dy * dy + dz * dz <= 9;
    }

    private static boolean bedNear(DailyActionRequest.Position a, DailyActionRequest.Position b) {
        if (a == null || b == null || !a.dimension().equals(b.dimension())) return false;
        long dx = (long) a.x() - b.x(), dy = (long) a.y() - b.y(), dz = (long) a.z() - b.z();
        return dx * dx + dy * dy + dz * dz <= 4;
    }

    private static Map<String, Integer> baseline(DailyActionRequest r, DailyActionSnapshot x) {
        Map<String, Integer> values = new HashMap<>();
        values.put(r.itemId(), x.count(r.itemId()));
        values.put("minecraft:bucket", x.count("minecraft:bucket"));
        values.put("minecraft:water_bucket", x.count("minecraft:water_bucket"));
        values.put("xp", x.experienceLevel());
        if (x.fish() != null) values.put("fishLoot", x.fish().lootCount());
        if (x.menu() != null) {
            values.put("tradeUses", x.menu().offerUses());
            values.put("tradeOutput", x.count(x.menu().offerOutput()));
        }
        return values;
    }

    private static String selectItem(State state, DailyActionSnapshot snapshot) {
        DailyActionRequest request = state.request;
        if (request.kind() == DailyActionKind.EQUIP_ITEM && request.action().equals("UNEQUIP")) return "";
        if (request.kind() == DailyActionKind.FARM_CROP) {
            DailyActionSnapshot.CropCandidate crop = selectedCrop(state, snapshot);
            return crop == null ? "" : crop.seedItem();
        }
        if (request.kind() == DailyActionKind.BREED_ANIMALS) {
            DailyActionSnapshot.AnimalCandidate first = animal(snapshot, state.selectedTargetId);
            DailyActionSnapshot.AnimalCandidate second = animal(snapshot, state.secondaryTargetId);
            if (first == null || second == null || !first.foodCompatible() || !second.foodCompatible()
                    || first.foodItem().isBlank() || !first.foodItem().equals(second.foodItem())) return "";
            return snapshot.count(first.foodItem()) >= 2 ? first.foodItem() : "";
        }
        String requested = request.kind() == DailyActionKind.GLIDE_WITH_ELYTRA
                ? "minecraft:elytra" : request.itemId();
        return selectCandidate(request, snapshot, requested).map(DailyActionSnapshot.ItemCandidate::itemId)
                .orElse(requested);
    }

    private static int selectItemSlot(DailyActionRequest request, DailyActionSnapshot snapshot, String itemId) {
        return selectCandidate(request, snapshot, itemId)
                .map(DailyActionSnapshot.ItemCandidate::slot).orElse(request.slot());
    }

    private static String selectStackDigest(DailyActionSnapshot snapshot, String itemId, int sourceSlot) {
        return snapshot.itemCandidates().stream()
                .filter(value -> value.slot() == sourceSlot && value.itemId().equals(itemId))
                .map(value -> value.traits().getOrDefault("componentDigest", ""))
                .findFirst().orElse("");
    }

    private static String resolveDestination(DailyActionRequest request, DailyActionSnapshot snapshot,
                                             String itemId, int sourceSlot) {
        if (request.kind() == DailyActionKind.GLIDE_WITH_ELYTRA) return "CHEST";
        if (request.action().startsWith("BEST_TOOL") || request.action().startsWith("BEST_WEAPON")) {
            return "MAIN_HAND";
        }
        if (!request.destination().isBlank() && !request.destination().equals("AUTO")) {
            return request.destination();
        }
        return snapshot.itemCandidates().stream()
                .filter(value -> value.slot() == sourceSlot && value.itemId().equals(itemId))
                .map(value -> value.traits().getOrDefault("equipmentSlot", "MAIN_HAND"))
                .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                .findFirst().orElse("MAIN_HAND");
    }

    private static java.util.Optional<DailyActionSnapshot.ItemCandidate> selectCandidate(
            DailyActionRequest request, DailyActionSnapshot snapshot, String requested) {
        java.util.stream.Stream<DailyActionSnapshot.ItemCandidate> candidates = snapshot.itemCandidates().stream()
                .filter(DailyActionSnapshot.ItemCandidate::usable);
        boolean bestTool = request.action().equals("BEST_TOOL");
        boolean bestWeapon = request.action().startsWith("BEST_WEAPON");
        if (!bestTool && !bestWeapon && requested != null && !requested.isBlank()) {
            candidates = candidates.filter(value -> value.itemId().equals(requested));
        }
        if (bestTool) candidates = candidates.filter(DailyActionSnapshot.ItemCandidate::correctTool)
                .filter(value -> request.targetBlockId().equals(
                        value.traits().getOrDefault("targetBlock", "")));
        if (bestWeapon) {
            candidates = candidates.filter(value -> !value.traits()
                    .getOrDefault("weaponType", "").isBlank());
        }
        if (bestWeapon && request.action().endsWith("_MELEE")) {
            candidates = candidates.filter(value -> "MELEE".equalsIgnoreCase(value.traits().get("weaponType")));
        } else if (bestWeapon && request.action().endsWith("_RANGED")) {
            candidates = candidates.filter(value -> "RANGED".equalsIgnoreCase(value.traits().get("weaponType")));
        }
        java.util.Comparator<DailyActionSnapshot.ItemCandidate> comparator = java.util.Comparator
                .comparingDouble((DailyActionSnapshot.ItemCandidate value) -> bestWeapon
                        ? value.attackDamage() : bestTool ? value.destroySpeed() : 0.0D)
                .thenComparingInt(value -> value.maxDamage() <= 0
                        ? Integer.MAX_VALUE : value.maxDamage() - value.damage())
                .thenComparingInt(value -> -value.slot());
        return candidates.max(comparator);
    }

    private static DailyActionRequest.Position selectTarget(DailyActionRequest request, DailyActionSnapshot snapshot) {
        if (request.target() != null && request.kind() != DailyActionKind.USE_VEHICLE
                && request.kind() != DailyActionKind.TRADE_WITH_VILLAGER
                && request.kind() != DailyActionKind.FARM_CROP) return request.target();
        return switch (request.kind()) {
            case SLEEP_AT_BED -> snapshot.bedCandidates().stream()
                    .filter(value -> value.usable() && !value.occupied() && within(snapshot.position(), value.position(), request.radius()))
                    .min(java.util.Comparator.comparingInt(value -> distance(snapshot.position(), value.position())))
                    .map(DailyActionSnapshot.BedCandidate::position).orElse(null);
            case USE_VEHICLE -> snapshot.vehicleCandidates().stream().filter(DailyActionSnapshot.VehicleCandidate::alive)
                    .filter(value -> !request.targetId().isBlank()
                            || within(snapshot.position(), value.position(), request.radius()))
                    .filter(value -> request.targetId().isBlank() || value.id().toString().equals(request.targetId()))
                    .filter(value -> request.itemId().isBlank() || value.type().equals(request.itemId()))
                    .min(java.util.Comparator.comparingInt(value -> distance(snapshot.position(), value.position())))
                    .map(DailyActionSnapshot.VehicleCandidate::position)
                    .orElseGet(() -> snapshot.vehicle() != null && snapshot.vehicle().found()
                            && vehicleMatches(request, id(snapshot.vehicle().id()), snapshot.vehicle().type())
                            ? snapshot.vehicle().position() : null);
            case FARM_CROP -> snapshot.cropCandidates().stream().filter(DailyActionSnapshot.CropCandidate::mature)
                    .filter(value -> request.itemId().isBlank() || value.blockId().isBlank()
                            || value.blockId().equals(request.itemId()))
                    .filter(value -> within(request.target() == null ? snapshot.position() : request.target(),
                            value.position(), request.radius()))
                    .min(java.util.Comparator.comparingInt(value -> distance(
                            request.target() == null ? snapshot.position() : request.target(), value.position())))
                    .map(DailyActionSnapshot.CropCandidate::position).orElse(null);
            case TRADE_WITH_VILLAGER -> snapshot.entityCandidates().stream()
                    .filter(value -> value.alive() && value.visible() && value.type().equals("minecraft:villager"))
                    .filter(value -> request.targetId().isBlank() || id(value.id()).equals(request.targetId()))
                    .min(java.util.Comparator.comparingInt(value -> distance(snapshot.position(), value.position())))
                    .map(DailyActionSnapshot.EntityCandidate::position).orElse(null);
            default -> null;
        };
    }

    private static boolean within(DailyActionRequest.Position from, DailyActionRequest.Position to, int radius) {
        if (from == null || to == null || !from.dimension().equals(to.dimension())) return false;
        long dx = (long) from.x() - to.x(), dy = (long) from.y() - to.y(), dz = (long) from.z() - to.z();
        return dx * dx + dy * dy + dz * dz <= (long) radius * radius;
    }

    private static boolean vehicleMatches(DailyActionRequest request, String targetId, String type) {
        return (request.targetId().isBlank() || request.targetId().equals(targetId))
                && (request.itemId().isBlank() || request.itemId().equals(type));
    }

    private static int distance(DailyActionRequest.Position from, DailyActionRequest.Position to) {
        if (from == null || to == null) return Integer.MAX_VALUE;
        long dx = (long) from.x() - to.x(), dy = (long) from.y() - to.y(), dz = (long) from.z() - to.z();
        return (int) Math.min(Integer.MAX_VALUE, dx * dx + dy * dy + dz * dz);
    }

    private static DailyActionSnapshot.BedCandidate selectedBed(State state, DailyActionSnapshot snapshot) {
        if (state.selectedTarget == null) return null;
        return snapshot.bedCandidates().stream()
                .filter(value -> state.selectedTarget.equals(value.position()))
                .findFirst().orElseGet(() -> snapshot.bed() != null
                        && state.selectedTarget.equals(snapshot.bed().position())
                        ? new DailyActionSnapshot.BedCandidate(snapshot.bed().position(), false,
                        snapshot.bed().usable(), snapshot.bed().problem()) : null);
    }

    private static DailyActionSnapshot.CropCandidate selectedCrop(State state, DailyActionSnapshot snapshot) {
        if (state.selectedTarget == null) return null;
        return snapshot.cropCandidates().stream()
                .filter(value -> state.selectedTarget.equals(value.position()) && value.mature())
                .findFirst().orElseGet(() -> snapshot.crop() != null && snapshot.crop().mature()
                        && state.selectedTarget.equals(snapshot.crop().position())
                        ? new DailyActionSnapshot.CropCandidate(snapshot.crop().position(),
                        state.request.itemId(), true, snapshot.crop().seedItem()) : null);
    }

    private static DailyActionRequest.Position animalPosition(DailyActionSnapshot snapshot, String targetId) {
        return snapshot.animalCandidates().stream().filter(value -> id(value.id()).equals(targetId))
                .map(DailyActionSnapshot.AnimalCandidate::position).findFirst().orElse(null);
    }

    private static DailyActionSnapshot.AnimalCandidate animal(DailyActionSnapshot snapshot, String targetId) {
        return snapshot.animalCandidates().stream().filter(value -> id(value.id()).equals(targetId))
                .findFirst().orElse(null);
    }

    private static DailyActionRequest.Position entityPosition(DailyActionSnapshot snapshot, String targetId) {
        return snapshot.entityCandidates().stream().filter(value -> id(value.id()).equals(targetId))
                .map(DailyActionSnapshot.EntityCandidate::position).findFirst().orElse(null);
    }

    private static boolean hasUsableCandidate(DailyActionSnapshot snapshot, String itemId) {
        return snapshot.itemCandidates().stream()
                .anyMatch(value -> value.usable() && value.itemId().equals(itemId));
    }

    private static String detail(DailyActionSnapshot snapshot, String key) {
        return snapshot.menu() == null ? "" : snapshot.menu().details().getOrDefault(key, "");
    }

    private static boolean detailBoolean(DailyActionSnapshot snapshot, String key) {
        return Boolean.parseBoolean(detail(snapshot, key));
    }

    private static int phaseInt(State state, String key, int fallback) {
        try { return Integer.parseInt(state.phaseBaseline.getOrDefault(key, Integer.toString(fallback))); }
        catch (NumberFormatException invalid) { return fallback; }
    }

    private static String id(UUID id) { return id == null ? "" : id.toString(); }

    private static String problem(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Map<String, String> postcondition(State s, DailyActionSnapshot x) {
        Map<String, String> result = new HashMap<>();
        result.put("phase", s.phase.name());
        result.put("action", s.request.action());
        result.put("selectedItem", s.selectedItemId);
        result.put("selectedSourceSlot", Integer.toString(s.selectedSourceSlot));
        result.put("selectedStackDigest", s.selectedStackDigest);
        result.put("selectedDestination", s.selectedDestination);
        result.put("targetId", s.selectedTargetId);
        result.put("secondaryTargetId", s.secondaryTargetId);
        result.put("target", s.selectedTarget == null ? "" : s.selectedTarget.toString());
        result.put("completedCycles", Integer.toString(s.completedCycles));
        result.put("dimension", x.dimension());
        result.put("position", x.position() == null ? "" : x.position().toString());
        result.put("sleeping", Boolean.toString(x.sleeping()));
        result.put("fallFlying", Boolean.toString(x.fallFlying()));
        if (x.vehicle() != null) {
            result.put("vehicleId", id(x.vehicle().id()));
            result.put("vehicleType", x.vehicle().type());
            result.put("vehicleSeated", Boolean.toString(x.vehicle().seated()));
            result.put("vehiclePosition", String.valueOf(x.vehicle().position()));
        }
        if (x.bucket() != null) {
            result.put("bucketTargetBlock", x.bucket().targetBlock());
            result.put("bucketEmptyCount", Integer.toString(x.bucket().emptyBuckets()));
            result.put("bucketWaterCount", Integer.toString(x.bucket().waterBuckets()));
            result.put("bucketAllowed", Boolean.toString(x.bucket().allowed()));
            result.put("bucketSourceValid", Boolean.toString(x.bucket().sourceValid()));
            result.put("bucketPlacementValid", Boolean.toString(x.bucket().placementValid()));
            result.put("bucketTargetChanged", Boolean.toString(x.bucket().targetChanged()));
        }
        if (x.menu() != null) {
            result.put("menuType", x.menu().type());
            result.put("offerIndex", Integer.toString(x.menu().offerIndex()));
            result.put("offerUses", Integer.toString(x.menu().offerUses()));
            result.put("offerOutput", x.menu().offerOutput());
            result.put("experienceLevel", Integer.toString(x.experienceLevel()));
            result.put("lapis", Integer.toString(x.menu().lapisCount()));
            result.put("brewingTicks", Integer.toString(x.menu().brewingTicks()));
            result.putAll(x.menu().details());
        }
        if (x.trade() != null) {
            result.put("tradeVillager", x.trade().villagerId());
            result.put("tradeInputA", x.trade().inputA());
            result.put("tradeInputB", x.trade().inputB());
            result.put("tradeOutput", x.trade().output());
            result.put("tradeInputAConsumed", Integer.toString(x.trade().inputAConsumed()));
            result.put("tradeInputBConsumed", Integer.toString(x.trade().inputBConsumed()));
            result.put("tradeOutputReceived", Integer.toString(x.trade().outputReceived()));
            result.put("tradeInputACount", Integer.toString(x.trade().inputACount()));
            result.put("tradeInputBCount", Integer.toString(x.trade().inputBCount()));
            result.put("tradeOutputCount", Integer.toString(x.trade().outputCount()));
        }
        if (x.enchant() != null) {
            result.put("enchantItem", x.enchant().itemId());
            result.put("enchantItemDigest", x.enchant().itemDigest());
            result.put("enchantLevelsSpent", Integer.toString(x.enchant().levelsSpent()));
            result.put("enchantLapisSpent", Integer.toString(x.enchant().lapisSpent()));
        }
        if (x.brew() != null) {
            result.put("brewIngredient", x.brew().ingredientId());
            result.put("brewBottleDigest", x.brew().digest());
            result.put("brewResultsTaken", Integer.toString(x.brew().resultsTaken()));
            result.put("brewInventoryResultDelta", Integer.toString(x.brew().inventoryResultDelta()));
        }
        if (x.crop() != null) {
            result.put("cropPosition", String.valueOf(x.crop().position()));
            result.put("cropAge", Integer.toString(x.crop().age()));
            result.put("seedItem", x.crop().seedItem());
            result.put("cropHarvested", Boolean.toString(x.crop().harvested()));
            result.put("cropPickedUp", Boolean.toString(x.crop().pickedUp()));
            result.put("cropReplanted", Boolean.toString(x.crop().replanted()));
            result.put("cropHarvestedCount", Integer.toString(x.crop().harvestedCount()));
            result.put("cropSeedConsumed", Integer.toString(x.crop().seedConsumed()));
        }
        if (x.fish() != null) {
            result.put("fishingHookId", x.fish().hookId());
            result.put("fishingRodDigest", x.fish().rodDigest());
            result.put("fishingRodDamageDelta", Integer.toString(x.fish().rodDamageDelta()));
            result.put("fishLootCount", Integer.toString(x.fish().lootCount()));
        }
        if (x.breed() != null) {
            result.put("babyCount", Integer.toString(x.breed().babyCount()));
            result.put("breedFirstFed", Boolean.toString(x.breed().firstFed()));
            result.put("breedSecondFed", Boolean.toString(x.breed().secondFed()));
            result.put("breedFoodConsumed", Integer.toString(x.breed().foodConsumed()));
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> merge(Map<String, String> left, Map<String, String> right) {
        Map<String, String> result = new HashMap<>(left);
        result.putAll(right);
        return result;
    }

    private State require(String id) {
        State state = sessions.get(id);
        if (state == null) throw new IllegalArgumentException("unknown daily action session: " + id);
        return state;
    }

    private static Map<DailyActionKind, List<DailyActionPhase>> flows() {
        EnumMap<DailyActionKind, List<DailyActionPhase>> result = new EnumMap<>(DailyActionKind.class);
        result.put(DailyActionKind.EQUIP_ITEM, List.of(DailyActionPhase.VALIDATE));
        result.put(DailyActionKind.SLEEP_AT_BED, List.of(DailyActionPhase.FIND_BED, DailyActionPhase.NAVIGATE_BED,
                DailyActionPhase.SLEEP, DailyActionPhase.WAKE));
        result.put(DailyActionKind.USE_WATER_BUCKET, List.of(DailyActionPhase.FILL_BUCKET));
        result.put(DailyActionKind.USE_VEHICLE, List.of(DailyActionPhase.APPROACH_VEHICLE,
                DailyActionPhase.BOARD_VEHICLE, DailyActionPhase.TRAVEL_VEHICLE, DailyActionPhase.DISEMBARK_VEHICLE));
        result.put(DailyActionKind.FISH, List.of(DailyActionPhase.EQUIP_ROD, DailyActionPhase.NAVIGATE_FISH,
                DailyActionPhase.CAST_LINE, DailyActionPhase.WAIT_BITE,
                DailyActionPhase.REEL_LINE, DailyActionPhase.VERIFY_LOOT));
        result.put(DailyActionKind.FARM_CROP, List.of(DailyActionPhase.SCAN_CROP, DailyActionPhase.NAVIGATE_CROP,
                DailyActionPhase.HARVEST_CROP,
                DailyActionPhase.PICKUP_CROP, DailyActionPhase.REPLANT_CROP, DailyActionPhase.VERIFY_CROP));
        result.put(DailyActionKind.BREED_ANIMALS, List.of(DailyActionPhase.APPROACH_FIRST_ANIMAL,
                DailyActionPhase.FEED_FIRST, DailyActionPhase.APPROACH_SECOND_ANIMAL, DailyActionPhase.FEED_SECOND,
                DailyActionPhase.WAIT_BABY, DailyActionPhase.VERIFY_BREED));
        result.put(DailyActionKind.TRADE_WITH_VILLAGER, List.of(DailyActionPhase.APPROACH_TRADE,
                DailyActionPhase.OPEN_TRADE,
                DailyActionPhase.SELECT_TRADE, DailyActionPhase.EXECUTE_TRADE, DailyActionPhase.VERIFY_TRADE));
        result.put(DailyActionKind.ENCHANT_ITEM, List.of(DailyActionPhase.NAVIGATE_ENCHANT,
                DailyActionPhase.OPEN_ENCHANT, DailyActionPhase.SELECT_ENCHANT,
                DailyActionPhase.APPLY_ENCHANT, DailyActionPhase.VERIFY_ENCHANT));
        result.put(DailyActionKind.BREW_POTION, List.of(DailyActionPhase.NAVIGATE_BREW,
                DailyActionPhase.OPEN_BREW, DailyActionPhase.LOAD_BREW,
                DailyActionPhase.WAIT_BREW, DailyActionPhase.TAKE_BREW, DailyActionPhase.VERIFY_BREW));
        result.put(DailyActionKind.GLIDE_WITH_ELYTRA, List.of(DailyActionPhase.EQUIP_ELYTRA,
                DailyActionPhase.START_GLIDE, DailyActionPhase.GLIDE, DailyActionPhase.LAND));
        return Map.copyOf(result);
    }

    private static final class State {
        private final String id;
        private final DailyActionRequest request;
        private final List<DailyActionPhase> flow;
        private final long startedTick;
        private long lastTick;
        private DailyActionPhase phase;
        private Status status = Status.RUNNING;
        private int attempts;
        private int completedCycles;
        private long pausedTicks;
        private long pausedSince;
        private String selectedItemId = "";
        private int selectedSourceSlot = -1;
        private String selectedStackDigest = "";
        private String selectedDestination = "";
        private String selectedTargetId = "";
        private String secondaryTargetId = "";
        private DailyActionRequest.Position selectedTarget;
        private String fishingHookId = "";
        private boolean dispatched;
        private boolean cleaned;
        private boolean initialized;
        private boolean cycleRestarted;
        private Map<String, Integer> baseline = Map.of();
        private Map<String, String> phaseBaseline = Map.of();
        private Observation observation;

        private State(String id, DailyActionRequest request, List<DailyActionPhase> flow, long startedTick) {
            this.id = id;
            this.request = request;
            this.flow = List.copyOf(flow);
            this.phase = flow.get(0);
            this.startedTick = startedTick;
            this.lastTick = startedTick;
            this.observation = observe(Status.RUNNING, phase, "STARTED");
        }

        private DailyActionPhase advance() {
            cycleRestarted = false;
            int index = flow.indexOf(phase);
            if (index >= 0 && index + 1 < flow.size()) return flow.get(index + 1);
            if (completedCycles + 1 < request.quantity()
                    && (request.kind() == DailyActionKind.FISH || request.kind() == DailyActionKind.FARM_CROP
                    || request.kind() == DailyActionKind.BREED_ANIMALS || request.kind() == DailyActionKind.TRADE_WITH_VILLAGER)) {
                completedCycles++;
                dispatched = false;
                cycleRestarted = true;
                return flow.get(0);
            }
            return DailyActionPhase.COMPLETE;
        }

        private void resetCycle() {
            initialized = false;
            dispatched = false;
            cleaned = false;
            selectedItemId = "";
            selectedSourceSlot = -1;
            selectedStackDigest = "";
            selectedDestination = "";
            selectedTargetId = request.targetId();
            secondaryTargetId = request.secondaryTargetId();
            selectedTarget = null;
            fishingHookId = "";
            phaseBaseline = Map.of();
            cycleRestarted = false;
        }

        private Observation observe(Status status, DailyActionPhase phase, String code) {
            return observe(status, phase, code, Map.of());
        }

        private Observation observe(Status status, DailyActionPhase phase, String code, Map<String, String> details) {
            return new Observation(status, request.kind(), phase, code, details);
        }

        private Session view() {
            return new Session(id, request, status, phase, startedTick, lastTick, attempts,
                    flow.indexOf(phase), completedCycles, baseline, phaseBaseline, dispatched,
                    initialized, cleaned, pausedTicks, pausedSince, selectedItemId, selectedSourceSlot,
                    selectedStackDigest,
                    selectedDestination, selectedTargetId, secondaryTargetId, selectedTarget,
                    fishingHookId, observation);
        }
    }
}

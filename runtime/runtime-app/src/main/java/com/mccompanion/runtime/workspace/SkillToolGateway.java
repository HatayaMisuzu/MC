package com.mccompanion.runtime.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.taskgraph.TaskGraphCodec;
import com.mccompanion.runtime.taskgraph.TaskGraphExecutor;
import com.mccompanion.runtime.taskgraph.TaskGraphRuntime;
import com.mccompanion.runtime.taskgraph.TaskGraphValidator;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolDefinition;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;
import com.mccompanion.runtime.security.Digests;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Bounded declarative Skill draft access over the logical Agent Workspace. */
public final class SkillToolGateway implements ToolGateway {
    private static final Pattern SKILL_ID = Pattern.compile("[a-z][a-z0-9_-]{2,63}");
    private static final Set<String> TRIAL_PERMISSIONS =
            Set.of("READ_WORLD", "MEMORY", "CONTROL_TASK");
    private final AgentWorkspace workspace;
    private final SkillRepository skills;
    private final BuiltinSkillCatalog builtins = new BuiltinSkillCatalog();
    private final String profileId;
    private final Function<ToolContext, List<ToolDefinition>> availableTools;
    private final TaskGraphValidator validator = new TaskGraphValidator();
    private final Map<String, ActiveSkillExecution> activeExecutions = new ConcurrentHashMap<>();
    private volatile TaskGraphRuntime taskGraphRuntime;

    public SkillToolGateway(AgentWorkspace workspace, SkillRepository skills, String profileId,
                            Function<ToolContext, List<ToolDefinition>> availableTools) {
        this.workspace = java.util.Objects.requireNonNull(workspace, "workspace");
        this.skills = java.util.Objects.requireNonNull(skills, "skills");
        this.profileId = requiredIdentifier(profileId, "profileId");
        this.availableTools = java.util.Objects.requireNonNull(availableTools, "availableTools");
    }

    public void attachTaskGraphRuntime(TaskGraphRuntime runtime) {
        if (taskGraphRuntime != null) throw new IllegalStateException("Task Graph Runtime is already attached");
        taskGraphRuntime = java.util.Objects.requireNonNull(runtime, "runtime");
    }

    /** Authenticated local-management snapshot; contains logical metadata but never host paths. */
    public ObjectNode managementSnapshot(String companionId) throws IOException, SQLException {
        String scopedCompanion = requiredIdentifier(companionId, "companionId");
        ObjectNode observation = Json.object().put("companionId", scopedCompanion);
        var drafts = Json.MAPPER.createArrayNode();
        for (WorkspaceResource draft : workspace.list(scopedCompanion, "skills/")) {
            ObjectNode value = Json.MAPPER.valueToTree(draft);
            value.put("document", workspace.read(scopedCompanion, draft.logicalPath()).content());
            value.set("retainedVersions", Json.MAPPER.valueToTree(
                    workspace.retainedVersions(scopedCompanion, draft.logicalPath())));
            drafts.add(value);
        }
        observation.set("drafts", drafts);
        observation.set("builtins", Json.MAPPER.valueToTree(builtins.list().stream()
                .map(SkillToolGateway::withoutBuiltinDocument).toList()));
        observation.set("versions", Json.MAPPER.valueToTree(skills.list(profileId, scopedCompanion)));
        observation.set("trials", Json.MAPPER.valueToTree(skills.trials(profileId, scopedCompanion).stream()
                .map(SkillToolGateway::withoutTrialDocument).toList()));
        return observation;
    }

    public SkillTrialLease revokeTrialForLocalUser(String companionId, String leaseId) throws SQLException {
        SkillTrialLease revoked = skills.revokeTrial(profileId,
                requiredIdentifier(companionId, "companionId"), leaseId, "LOCAL_MANAGEMENT_USER");
        if (taskGraphRuntime != null && revoked.executionId() != null) {
            ToolContext persistedOwner = new ToolContext(
                    revoked.controllerId(), revoked.brainSessionId(), revoked.companionId());
            taskGraphRuntime.cancel(persistedOwner, revoked.executionId(), "SKILL_TRIAL_REVOKED");
            activeExecutions.forEach((key, execution) -> {
                if (revoked.leaseId().equals(execution.leaseId())
                        && activeExecutions.remove(key, execution)) {
                    taskGraphRuntime.cancel(execution.context(), execution.callId(),
                            "SKILL_TRIAL_REVOKED");
                }
            });
        }
        return revoked;
    }

    public WorkspaceResource restoreDraftForLocalUser(
            String companionId, String requestedSkillId, String requestedFormat, long version) throws IOException {
        String scopedCompanion = requiredIdentifier(companionId, "companionId");
        ObjectNode arguments = Json.object().put("skillId", requestedSkillId)
                .put("format", requestedFormat);
        String parsedSkillId = skillId(arguments);
        rejectBuiltinId(parsedSkillId);
        if (version < 1 || version > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("version is out of range");
        }
        return workspace.restore(scopedCompanion, path(parsedSkillId, format(arguments)), version);
    }

    @Override
    public List<ToolDefinition> definitions(ToolContext context) {
        return List.of(
                definition("skill.list", "List logical Skill draft resources", listSchema(), true),
                definition("skill.read", "Read one logical Skill draft or durable version without a host path",
                        readSchema(), true),
                definition("skill.save_draft", "Atomically save a quarantined declarative Skill draft",
                        saveSchema(), true),
                definition("skill.restore_draft",
                        "Restore a retained draft as a new quarantined workspace version",
                        restoreDraftSchema(), true),
                definition("skill.validate", "Validate a Skill draft against current Task Graph and Tool contracts",
                        draftSchema(), true),
                definition("skill.request_promotion",
                        "Request user review of a validated Skill version; the Brain cannot approve it",
                        draftSchema(), true),
                definition("skill.request_trial",
                        "Request one bounded low-risk single-use lease for a validated quarantined draft",
                        trialRequestSchema(), false),
                definition("skill.disable", "Disable the active approved Skill version", disableSchema(), true),
                definition("skill.rollback", "Rollback to a previously approved Skill version",
                        rollbackSchema(), true),
                new ToolDefinition("skill.execute", "1.0",
                        "Execute the current approved Skill through the persistent Task Graph Runtime",
                        executeSchema(), "MEDIUM", "EXECUTE_TASK_GRAPH", Duration.ofSeconds(30), false),
                new ToolDefinition("skill.execute_trial", "1.0",
                        "Consume one exact-scope generated Skill trial lease through Task Graph Runtime",
                        trialExecuteSchema(), "LOW", "EXECUTE_TASK_GRAPH", Duration.ofSeconds(30), false));
    }

    @Override
    public ToolResult execute(ToolContext context, ToolCall call) {
        try {
            return switch (call.name()) {
                case "skill.list" -> list(context, call);
                case "skill.read" -> read(context, call);
                case "skill.save_draft" -> save(context, call);
                case "skill.restore_draft" -> restoreDraft(context, call);
                case "skill.validate" -> validate(context, call);
                case "skill.request_promotion" -> requestPromotion(context, call);
                case "skill.request_trial" -> requestTrial(context, call);
                case "skill.disable" -> disable(context, call);
                case "skill.rollback" -> rollback(context, call);
                case "skill.execute" -> executeApproved(context, call);
                case "skill.execute_trial" -> executeTrial(context, call);
                default -> ToolResult.rejected(call, "TOOL_UNAVAILABLE", "Skill tool is unavailable");
            };
        } catch (IllegalArgumentException failure) {
            return ToolResult.rejected(call, "INVALID_TOOL_ARGUMENTS", failure.getMessage());
        } catch (IllegalStateException failure) {
            String code = failure.getMessage() != null
                    && failure.getMessage().matches("SKILL_[A-Z0-9_]{1,64}")
                    ? failure.getMessage() : "SKILL_STATE_CONFLICT";
            return ToolResult.rejected(call, code, "Skill lifecycle state does not allow this operation");
        } catch (IOException failure) {
            return ToolResult.rejected(call, "WORKSPACE_IO_FAILED", "Workspace operation failed");
        } catch (SQLException failure) {
            return ToolResult.rejected(call, "SKILL_PERSISTENCE_FAILED", "Skill lifecycle persistence failed");
        }
    }

    @Override
    public ToolResult awaitTerminal(ToolContext context, ToolCall call, ToolResult accepted, Duration timeout,
                                    java.util.function.Consumer<ToolResult> progress) {
        if ((call.name().equals("skill.execute") || call.name().equals("skill.execute_trial"))
                && taskGraphRuntime != null && !accepted.terminal()) {
            ActiveSkillExecution execution = activeExecutions.get(executionKey(context, call.callId()));
            try {
                ToolResult terminal = taskGraphRuntime.await(context, call, timeout, progress);
                String leaseId = execution == null ? skills.trialForExecution(call.callId())
                        .map(SkillTrialLease::leaseId).orElse(null) : execution.leaseId();
                if (leaseId != null) {
                    SkillTrialLease trial = skills.trial(leaseId).orElse(null);
                    if (trial != null && trial.status().equals("RUNNING")) {
                        skills.finishTrial(leaseId, call.callId(), trialEvidence(terminal));
                    }
                }
                return terminal;
            } catch (SQLException failure) {
                return ToolResult.rejected(call, "SKILL_TRIAL_EVIDENCE_FAILED",
                        "Skill trial evidence could not be persisted");
            } finally {
                activeExecutions.remove(executionKey(context, call.callId()));
            }
        }
        return accepted;
    }

    @Override
    public void cancel(ToolContext context, String callId, String reason) {
        ActiveSkillExecution execution = activeExecutions.remove(executionKey(context, callId));
        boolean persistedSkill = taskGraphRuntime != null && taskGraphRuntime.isSkillExecution(context, callId);
        if (taskGraphRuntime != null && (execution != null || persistedSkill)) {
            String leaseId = execution == null ? null : execution.leaseId();
            if (leaseId == null) {
                try {
                    leaseId = skills.trialForExecution(callId).map(SkillTrialLease::leaseId).orElse(null);
                } catch (SQLException ignored) {
                    // Durable graph cancellation still wins; lease state remains visible for repair.
                }
            }
            if (leaseId != null) {
                try {
                    skills.revokeTrial(profileId, context.companionId(),
                            leaseId, "BRAIN_CANCELLED");
                } catch (SQLException ignored) {
                    // Task cancellation still wins; the durable running lease remains visible for repair.
                }
            }
            taskGraphRuntime.cancel(context, callId, reason);
        }
    }

    @Override
    public boolean pause(ToolContext context, String callId, String reason) {
        ActiveSkillExecution execution = activeExecutions.get(executionKey(context, callId));
        if (taskGraphRuntime == null || (execution == null
                && !taskGraphRuntime.isSkillExecution(context, callId))) return false;
        ToolResult paused = taskGraphRuntime.pause(context,
                new ToolCall("interrupt-pause-" + callId, "task_graph.pause",
                        Json.object().put("executionId", callId)), callId);
        return paused.success();
    }

    private ToolResult list(ToolContext context, ToolCall call) throws IOException, SQLException {
        rejectUnexpected(call.arguments(), Set.of());
        ObjectNode observation = Json.object();
        observation.set("drafts", Json.MAPPER.valueToTree(workspace.list(context.companionId(), "skills/")));
        observation.set("builtins", Json.MAPPER.valueToTree(builtins.list().stream()
                .map(SkillToolGateway::withoutBuiltinDocument).toList()));
        observation.set("versions", Json.MAPPER.valueToTree(skills.list(profileId, context.companionId()).stream()
                .map(SkillToolGateway::withoutDocument).toList()));
        return ok(call, observation);
    }

    private ToolResult read(ToolContext context, ToolCall call) throws IOException, SQLException {
        rejectUnexpected(call.arguments(), Set.of("skillId", "format", "version"));
        String skillId = skillId(call.arguments());
        String format = format(call.arguments());
        var builtin = builtins.get(skillId);
        if (builtin.isPresent()) {
            if (call.arguments().has("version")) {
                throw new IllegalArgumentException("built-in Skills do not have generated version numbers");
            }
            if (!builtin.get().format().equals(format)) throw new IllegalArgumentException("skill format does not match");
            return ok(call, Json.MAPPER.valueToTree(builtin.get()));
        }
        if (call.arguments().has("version")) {
            long version = integer(call.arguments(), "version", 1, Integer.MAX_VALUE);
            SkillVersion stored = skills.version(profileId, context.companionId(), skillId, version)
                    .orElseThrow(() -> new IllegalArgumentException("skill version does not exist"));
            if (!stored.format().equals(format)) throw new IllegalArgumentException("skill format does not match");
            return ok(call, Json.MAPPER.valueToTree(stored));
        }
        return ok(call, Json.MAPPER.valueToTree(workspace.read(context.companionId(), path(skillId, format))));
    }

    private ToolResult save(ToolContext context, ToolCall call) throws IOException {
        rejectUnexpected(call.arguments(), Set.of("skillId", "format", "document"));
        String skillId = skillId(call.arguments());
        rejectBuiltinId(skillId);
        String format = format(call.arguments());
        String document = text(call.arguments(), "document", 1, 65_536);
        WorkspaceResource resource = workspace.save(context.companionId(), path(skillId, format), document);
        ObjectNode observation = Json.MAPPER.valueToTree(resource);
        observation.put("trust", "QUARANTINED");
        return new ToolResult(call.callId(), call.name(), true, "SKILL_DRAFT_QUARANTINED",
                observation, true);
    }

    private ToolResult restoreDraft(ToolContext context, ToolCall call) throws IOException {
        rejectUnexpected(call.arguments(), Set.of("skillId", "format", "version"));
        String skillId = skillId(call.arguments());
        rejectBuiltinId(skillId);
        String format = format(call.arguments());
        long version = integer(call.arguments(), "version", 1, Integer.MAX_VALUE);
        WorkspaceResource resource = workspace.restore(
                context.companionId(), path(skillId, format), version);
        ObjectNode observation = Json.MAPPER.valueToTree(resource);
        observation.put("restoredFromVersion", version).put("trust", "QUARANTINED");
        return new ToolResult(call.callId(), call.name(), true, "SKILL_DRAFT_RESTORED",
                observation, true);
    }

    private ToolResult validate(ToolContext context, ToolCall call) throws IOException {
        rejectUnexpected(call.arguments(), Set.of("skillId", "format"));
        rejectBuiltinId(skillId(call.arguments()));
        ValidatedDraft draft = validatedDraft(context, call.arguments());
        var result = draft.validation();
        ObjectNode observation = Json.object().put("skillId", draft.skillId()).put("valid", result.valid())
                .put("sha256", draft.document().resource().sha256())
                .put("version", draft.document().resource().version())
                .put("trust", result.valid() ? "GENERATED_VALIDATED" : "QUARANTINED");
        observation.set("validation", Json.MAPPER.valueToTree(result));
        return new ToolResult(call.callId(), call.name(), result.valid(),
                result.valid() ? "SKILL_VALID" : "SKILL_INVALID", observation, true);
    }

    private ToolResult requestPromotion(ToolContext context, ToolCall call) throws IOException, SQLException {
        rejectUnexpected(call.arguments(), Set.of("skillId", "format"));
        rejectBuiltinId(skillId(call.arguments()));
        ValidatedDraft draft = validatedDraft(context, call.arguments());
        if (!draft.validation().valid()) {
            return ToolResult.rejected(call, "SKILL_INVALID", "Skill must pass current validation before review");
        }
        JsonNode provenance = draft.graph().path("provenance").isMissingNode()
                ? Json.object() : draft.graph().path("provenance");
        SkillVersion requested = skills.requestPromotion(profileId, context.companionId(), draft.skillId(),
                draft.format(), draft.document().content(), draft.document().resource().sha256(),
                draft.graph().path("permissions").isMissingNode() ? Json.MAPPER.createArrayNode()
                        : draft.graph().path("permissions"),
                Json.object().put("controllerId", context.controllerId())
                        .put("brainSessionId", context.brainSessionId()).set("graph", provenance.deepCopy()),
                Json.MAPPER.valueToTree(draft.validation()), context.controllerId(), context.brainSessionId());
        ObjectNode observation = withoutDocument(requested);
        observation.put("trust", "GENERATED_VALIDATED");
        observation.put("requiresUserApproval", true);
        return new ToolResult(call.callId(), call.name(), true,
                requested.status().equals("ACTIVE") ? "SKILL_ALREADY_ACTIVE"
                        : "SKILL_PROMOTION_PENDING_USER_APPROVAL", observation, true);
    }

    private ToolResult requestTrial(ToolContext context, ToolCall call) throws IOException, SQLException {
        rejectUnexpected(call.arguments(), Set.of("skillId", "format", "durationSeconds"));
        rejectBuiltinId(skillId(call.arguments()));
        ValidatedDraft draft = validatedDraft(context, call.arguments());
        if (!draft.validation().valid()) {
            return ToolResult.rejected(call, "SKILL_INVALID",
                    "Skill must pass current validation before a trial lease");
        }
        long durationSeconds = integer(call.arguments(), "durationSeconds", 60, 900);
        var limits = draft.validation().limits();
        if (limits.maxNodes() > 32 || limits.maxDepth() > 8 || limits.maxLoopIterations() > 8
                || limits.maxRetriesPerNode() > 1 || limits.maxParallelNodes() > 2
                || limits.maxToolCalls() > 16 || limits.maxWallTimeSeconds() > durationSeconds
                || limits.maxSerializedStateBytes() > 262_144 || limits.maxEvidenceEntries() > 64
                || limits.maxEvidenceBytes() > 65_536) {
            return ToolResult.rejected(call, "SKILL_TRIAL_LIMITS_TOO_BROAD",
                    "Trial graph limits exceed the low-risk trial policy");
        }
        Set<String> permissions = new java.util.TreeSet<>();
        draft.graph().path("permissions").forEach(value -> permissions.add(value.asText()));
        if (!TRIAL_PERMISSIONS.containsAll(permissions)) {
            return ToolResult.rejected(call, "SKILL_TRIAL_PERMISSION_DENIED",
                    "Trial permissions must stay within READ_WORLD, MEMORY, and CONTROL_TASK");
        }
        Map<String, ToolDefinition> definitions = availableTools.apply(context).stream()
                .filter(value -> !value.name().startsWith("skill."))
                .collect(Collectors.toMap(ToolDefinition::name, value -> value, (left, right) -> left));
        Set<String> toolNames = new java.util.TreeSet<>();
        collectGraphTools(draft.graph().path("root"), toolNames);
        for (String toolName : toolNames) {
            ToolDefinition definition = definitions.get(toolName);
            if (definition == null || !"LOW".equals(definition.risk())
                    || !TRIAL_PERMISSIONS.contains(definition.permission())) {
                return ToolResult.rejected(call, "SKILL_TRIAL_TOOL_DENIED",
                        "Trial contains a Tool outside the explicit low-risk policy: " + toolName);
            }
        }
        SkillTrialLease lease = skills.requestTrial(profileId, context.companionId(),
                context.controllerId(), context.brainSessionId(), draft.skillId(), draft.format(),
                draft.document().content(), draft.document().resource().sha256(),
                Json.MAPPER.valueToTree(toolNames), Json.MAPPER.valueToTree(permissions),
                limits.toJson(), Duration.ofSeconds(durationSeconds));
        ObjectNode observation = withoutTrialDocument(lease);
        observation.put("trust", "TRIAL_ONLY").put("singleUse", true)
                .put("requiresUserApprovalForPermanentUse", true);
        return new ToolResult(call.callId(), call.name(), true, "SKILL_TRIAL_LEASE_CREATED",
                observation, true);
    }

    private ToolResult disable(ToolContext context, ToolCall call) throws SQLException {
        rejectUnexpected(call.arguments(), Set.of("skillId", "reason"));
        String skillId = skillId(call.arguments());
        rejectBuiltinId(skillId);
        SkillVersion disabled = skills.disable(profileId, context.companionId(), skillId,
                context.controllerId(), text(call.arguments(), "reason", 1, 256));
        cancelRevoked(context.companionId(), skillId, null, "SKILL_DISABLED");
        return new ToolResult(call.callId(), call.name(), true, "SKILL_DISABLED",
                withoutDocument(disabled), true);
    }

    private ToolResult rollback(ToolContext context, ToolCall call) throws SQLException {
        rejectUnexpected(call.arguments(), Set.of("skillId", "version", "reason"));
        String skillId = skillId(call.arguments());
        rejectBuiltinId(skillId);
        SkillVersion active = skills.rollback(profileId, context.companionId(), skillId,
                integer(call.arguments(), "version", 1, Integer.MAX_VALUE), context.controllerId(),
                text(call.arguments(), "reason", 1, 256));
        cancelRevoked(context.companionId(), skillId, active.requestId(), "SKILL_VERSION_REVOKED");
        return new ToolResult(call.callId(), call.name(), true, "SKILL_ROLLED_BACK",
                withoutDocument(active), true);
    }

    private ToolResult executeApproved(ToolContext context, ToolCall call) throws SQLException {
        if (taskGraphRuntime == null) {
            return ToolResult.rejected(call, "SKILL_RUNTIME_UNAVAILABLE", "Task Graph Runtime is unavailable");
        }
        rejectUnexpected(call.arguments(), Set.of("skillId", "inputs"));
        String skillId = skillId(call.arguments());
        SkillVersion active = skills.active(profileId, context.companionId(), skillId).orElse(null);
        BuiltinSkillCatalog.BuiltinSkill builtin = active == null ? builtins.get(skillId).orElse(null) : null;
        if (active == null && builtin == null) {
            throw new IllegalArgumentException("skill has no active approved or built-in version");
        }
        String document = active == null ? builtin.document() : active.document();
        String sha256 = active == null ? builtin.sha256() : active.sha256();
        String format = active == null ? builtin.format() : active.format();
        if (!Digests.sha256(document).equals(sha256)) {
            return ToolResult.rejected(call, "SKILL_INTEGRITY_FAILED", "Approved Skill hash does not match");
        }
        JsonNode graph = TaskGraphCodec.parse(document, format.equals("json")
                ? TaskGraphCodec.Format.JSON : TaskGraphCodec.Format.YAML);
        if (containsSkillTool(graph)) {
            return ToolResult.rejected(call, "SKILL_RECURSION_FORBIDDEN",
                    "Generated Skills cannot call Skill lifecycle or execution Tools");
        }
        JsonNode inputs = call.arguments().path("inputs");
        if (inputs.isMissingNode()) inputs = Json.object();
        if (!inputs.isObject()) throw new IllegalArgumentException("inputs must be an object");
        ObjectNode provenance = Json.object().put("source",
                        active == null ? "BUILT_IN_SKILL" : "APPROVED_GENERATED_SKILL")
                .put("skillId", skillId).put("skillSha256", sha256);
        if (active != null) {
            provenance.put("skillVersion", active.version())
                    .put("promotionRequestId", active.requestId());
        }
        ToolResult started = taskGraphRuntime.start(context, call, graph, inputs, provenance);
        if (started.success() && !started.terminal()) {
            String key = executionKey(context, call.callId());
            ActiveSkillExecution execution = new ActiveSkillExecution(
                    context, call.callId(), skillId, active == null ? null : active.requestId(), null);
            activeExecutions.put(key, execution);
            if (active != null) {
                SkillVersion current = skills.active(profileId, context.companionId(), skillId).orElse(null);
                if (current == null || !current.requestId().equals(active.requestId())) {
                    if (activeExecutions.remove(key, execution)) {
                        taskGraphRuntime.cancel(context, call.callId(), "SKILL_VERSION_REVOKED");
                    }
                }
            }
        }
        return started;
    }

    private ToolResult executeTrial(ToolContext context, ToolCall call) throws SQLException {
        if (taskGraphRuntime == null) {
            return ToolResult.rejected(call, "SKILL_RUNTIME_UNAVAILABLE",
                    "Task Graph Runtime is unavailable");
        }
        rejectUnexpected(call.arguments(), Set.of("leaseId", "inputs"));
        String leaseId = text(call.arguments(), "leaseId", 1, 128);
        SkillTrialLease lease = skills.claimTrial(leaseId, profileId, context.companionId(),
                context.controllerId(), context.brainSessionId(), call.callId());
        if (!Digests.sha256(lease.document()).equals(lease.sha256())) {
            ToolResult failed = ToolResult.rejected(call, "SKILL_INTEGRITY_FAILED",
                    "Trial Skill hash does not match");
            skills.finishTrial(leaseId, call.callId(), trialEvidence(failed));
            return failed;
        }
        JsonNode graph = TaskGraphCodec.parse(lease.document(), lease.format().equals("json")
                ? TaskGraphCodec.Format.JSON : TaskGraphCodec.Format.YAML);
        if (containsSkillTool(graph)) {
            ToolResult failed = ToolResult.rejected(call, "SKILL_RECURSION_FORBIDDEN",
                    "Generated Skills cannot call Skill lifecycle or execution Tools");
            skills.finishTrial(leaseId, call.callId(), trialEvidence(failed));
            return failed;
        }
        JsonNode inputs = call.arguments().path("inputs");
        if (inputs.isMissingNode()) inputs = Json.object();
        if (!inputs.isObject()) throw new IllegalArgumentException("inputs must be an object");
        ObjectNode provenance = Json.object().put("source", "GENERATED_SKILL_TRIAL")
                .put("skillId", lease.skillId()).put("skillSha256", lease.sha256())
                .put("trialLeaseId", lease.leaseId()).put("singleUse", true);
        ToolResult started = taskGraphRuntime.start(context, call, graph, inputs, provenance);
        if (!started.success() || started.terminal()) {
            skills.finishTrial(leaseId, call.callId(), trialEvidence(started));
            return started;
        }
        ActiveSkillExecution execution = new ActiveSkillExecution(
                context, call.callId(), lease.skillId(), null, lease.leaseId());
        activeExecutions.put(executionKey(context, call.callId()), execution);
        SkillTrialLease current = skills.trial(leaseId).orElseThrow();
        if (!current.status().equals("RUNNING")) {
            if (activeExecutions.remove(executionKey(context, call.callId()), execution)) {
                taskGraphRuntime.cancel(context, call.callId(), "SKILL_TRIAL_REVOKED");
            }
        }
        return started;
    }

    private void cancelRevoked(String companionId, String skillId, String retainedRequestId, String reason) {
        if (taskGraphRuntime == null) return;
        taskGraphRuntime.cancelSkillExecutions(companionId, skillId, retainedRequestId, reason);
        activeExecutions.forEach((key, execution) -> {
            if (!execution.context().companionId().equals(companionId)
                    || !execution.skillId().equals(skillId)
                    || java.util.Objects.equals(execution.requestId(), retainedRequestId)) {
                return;
            }
            if (activeExecutions.remove(key, execution)) {
                taskGraphRuntime.cancel(execution.context(), execution.callId(), reason);
            }
        });
    }

    private ValidatedDraft validatedDraft(ToolContext context, JsonNode arguments) throws IOException {
        String skillId = skillId(arguments);
        String format = format(arguments);
        WorkspaceDocument document = workspace.read(context.companionId(), path(skillId, format));
        JsonNode graph = TaskGraphCodec.parse(document.content(), format.equals("json")
                ? TaskGraphCodec.Format.JSON : TaskGraphCodec.Format.YAML);
        Map<String, ToolDefinition> definitions = availableTools.apply(context).stream()
                .filter(value -> !value.name().startsWith("skill."))
                .collect(Collectors.toMap(ToolDefinition::name, value -> value, (left, right) -> left));
        var result = validator.validateExecutable(graph, definitions, TaskGraphExecutor.EXECUTABLE_NODE_TYPES);
        return new ValidatedDraft(skillId, format, document, graph, result);
    }

    private static ToolDefinition definition(String name, String description, JsonNode schema,
                                             boolean idempotent) {
        return new ToolDefinition(name, "1.0", description, schema, "LOW", "MANAGE_SKILLS",
                Duration.ofSeconds(5), idempotent);
    }

    private static ObjectNode listSchema() {
        return Json.object().put("type", "object").put("additionalProperties", false);
    }

    private static ObjectNode draftSchema() {
        ObjectNode schema = Json.object().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("skillId").put("type", "string").put("minLength", 3).put("maxLength", 64);
        properties.putObject("format").put("type", "string").putArray("enum").add("yaml").add("yml").add("json");
        schema.putArray("required").add("skillId").add("format");
        return schema;
    }

    private static ObjectNode readSchema() {
        ObjectNode schema = draftSchema();
        schema.withObject("properties").putObject("version").put("type", "integer").put("minimum", 1);
        return schema;
    }

    private static ObjectNode saveSchema() {
        ObjectNode schema = draftSchema();
        schema.withObject("properties").putObject("document").put("type", "string")
                .put("minLength", 1).put("maxLength", 65_536);
        schema.withArray("required").add("document");
        return schema;
    }

    private static ObjectNode restoreDraftSchema() {
        ObjectNode schema = draftSchema();
        schema.withObject("properties").putObject("version").put("type", "integer").put("minimum", 1);
        schema.withArray("required").add("version");
        return schema;
    }

    private static ObjectNode disableSchema() {
        ObjectNode schema = Json.object().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("skillId").put("type", "string").put("minLength", 3).put("maxLength", 64);
        properties.putObject("reason").put("type", "string").put("minLength", 1).put("maxLength", 256);
        schema.putArray("required").add("skillId").add("reason");
        return schema;
    }

    private static ObjectNode rollbackSchema() {
        ObjectNode schema = disableSchema();
        schema.withObject("properties").putObject("version").put("type", "integer").put("minimum", 1);
        schema.withArray("required").add("version");
        return schema;
    }

    private static ObjectNode executeSchema() {
        ObjectNode schema = Json.object().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("skillId").put("type", "string").put("minLength", 3).put("maxLength", 64);
        properties.putObject("inputs").put("type", "object");
        schema.putArray("required").add("skillId");
        return schema;
    }

    private static ObjectNode trialRequestSchema() {
        ObjectNode schema = draftSchema();
        schema.withObject("properties").putObject("durationSeconds")
                .put("type", "integer").put("minimum", 60).put("maximum", 900);
        schema.withArray("required").add("durationSeconds");
        return schema;
    }

    private static ObjectNode trialExecuteSchema() {
        ObjectNode schema = Json.object().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("leaseId").put("type", "string").put("minLength", 1).put("maxLength", 128);
        properties.putObject("inputs").put("type", "object");
        schema.putArray("required").add("leaseId");
        return schema;
    }

    private static String skillId(JsonNode arguments) {
        String value = text(arguments, "skillId", 3, 64);
        if (!SKILL_ID.matcher(value).matches()) throw new IllegalArgumentException("skillId is invalid");
        return value;
    }

    private static String format(JsonNode arguments) {
        String value = text(arguments, "format", 3, 4).toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("yaml", "yml", "json").contains(value)) {
            throw new IllegalArgumentException("format is invalid");
        }
        return value;
    }

    private static String path(String skillId, String format) {
        return "skills/" + skillId + "/draft." + format;
    }

    private static String text(JsonNode arguments, String name, int minimum, int maximum) {
        JsonNode value = arguments.path(name);
        if (!value.isTextual()) throw new IllegalArgumentException(name + " must be text");
        String text = value.asText();
        if (text.length() < minimum || text.length() > maximum) {
            throw new IllegalArgumentException(name + " length is invalid");
        }
        return text;
    }

    private static long integer(JsonNode arguments, String name, long minimum, long maximum) {
        JsonNode value = arguments.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        long number = value.asLong();
        if (number < minimum || number > maximum) throw new IllegalArgumentException(name + " is out of range");
        return number;
    }

    private static String requiredIdentifier(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value.strip();
    }

    private static ObjectNode withoutDocument(SkillVersion value) {
        ObjectNode node = Json.MAPPER.valueToTree(value);
        node.remove("document");
        return node;
    }

    private static ObjectNode withoutBuiltinDocument(BuiltinSkillCatalog.BuiltinSkill value) {
        ObjectNode node = Json.MAPPER.valueToTree(value);
        node.remove("document");
        return node;
    }

    private static ObjectNode withoutTrialDocument(SkillTrialLease value) {
        ObjectNode node = Json.MAPPER.valueToTree(value);
        node.remove("document");
        return node;
    }

    private static ObjectNode trialEvidence(ToolResult result) {
        JsonNode observation = result.observation();
        return Json.object().put("executionId", result.callId()).put("success", result.success())
                .put("code", result.code()).put("state", observation.path("state").asText(""))
                .put("completedNodeCount", observation.path("completedNodes").size())
                .put("evidenceEntryCount", observation.path("evidence").size())
                .put("recordedAt", java.time.Instant.now().toString());
    }

    private static void collectGraphTools(JsonNode value, Set<String> tools) {
        if (value == null || value.isMissingNode()) return;
        if (value.isObject()) {
            String type = value.path("type").asText();
            if (type.equals("call_tool")) tools.add(value.path("tool").asText());
            if (type.equals("read_memory")) tools.add("memory.search");
            if (type.equals("suggest_memory")) tools.add("memory.suggest");
            var fields = value.fields();
            while (fields.hasNext()) collectGraphTools(fields.next().getValue(), tools);
        } else if (value.isArray()) {
            value.forEach(child -> collectGraphTools(child, tools));
        }
    }

    private void rejectBuiltinId(String skillId) {
        if (builtins.get(skillId).isPresent()) {
            throw new IllegalArgumentException("built-in Skill IDs are read-only");
        }
    }

    private static boolean containsSkillTool(JsonNode value) {
        if (value == null) return false;
        if (value.isObject()) {
            if (value.path("type").asText().equals("call_tool")
                    && value.path("tool").asText().startsWith("skill.")) {
                return true;
            }
            var fields = value.fields();
            while (fields.hasNext()) {
                if (containsSkillTool(fields.next().getValue())) return true;
            }
        } else if (value.isArray()) {
            for (JsonNode child : value) if (containsSkillTool(child)) return true;
        }
        return false;
    }

    private static String executionKey(ToolContext context, String callId) {
        return context.controllerId() + '\u0000' + context.brainSessionId() + '\u0000'
                + context.companionId() + '\u0000' + callId;
    }

    private static void rejectUnexpected(JsonNode arguments, Set<String> allowed) {
        arguments.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw new IllegalArgumentException("unexpected field: " + name);
        });
    }

    private static ToolResult ok(ToolCall call, JsonNode observation) {
        return new ToolResult(call.callId(), call.name(), true, "OK", observation, true);
    }

    private record ValidatedDraft(String skillId, String format, WorkspaceDocument document, JsonNode graph,
                                  com.mccompanion.runtime.taskgraph.TaskGraphValidationResult validation) {
    }

    private record ActiveSkillExecution(ToolContext context, String callId, String skillId,
                                        String requestId, String leaseId) {
    }
}

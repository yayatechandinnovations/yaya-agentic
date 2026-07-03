package com.yayatechandinnovations.yayaagentic.startflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.Session;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityKind;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityMemory;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityRef;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityScope;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolExecutor;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.EntityBinder;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.JsonPointerExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SF-0 start-flow state machine. Straight-line steps with branch routing,
 * running each tool through the SAME {@code ToolExecutor} the LLM uses so
 * schema validation + the Authorizer chain apply (non-negotiables #1, #2).
 * Never throws: any failure degrades to an aborted flow + fallback greeting so
 * a broken flow can never dead-end a session. See
 * {@code docs/design/session-start-flows.md} §7, §9, §13.
 */
@Component
public class DefaultStartFlowRunner implements StartFlowRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultStartFlowRunner.class);
    private static final Pattern TOKEN = Pattern.compile("\\{([a-zA-Z0-9_.]+)}");

    private final Authorizer authorizer;
    private final ToolExecutor toolExecutor;
    private final M0Catalog catalog;
    private final EntityMemory entityMemory;
    private final JsonPointerExtractor extractor;

    private final StartFlowStateStore store;
    private final StartFlowRegistry registry;

    public DefaultStartFlowRunner(Authorizer authorizer,
                                  ToolExecutor toolExecutor,
                                  M0Catalog catalog,
                                  EntityMemory entityMemory,
                                  ObjectMapper sharedObjectMapper,
                                  StartFlowStateStore store,
                                  StartFlowRegistry registry) {
        this.authorizer = authorizer;
        this.toolExecutor = toolExecutor;
        this.catalog = catalog;
        this.entityMemory = entityMemory;
        this.extractor = new JsonPointerExtractor(sharedObjectMapper);
        this.store = store;
        this.registry = registry;
    }

    // ---- drive outcomes (internal) --------------------------------------

    private sealed interface DriveOutcome permits Completed, Aborted, Paused {}
    private record Completed(String outcome) implements DriveOutcome {}
    private record Aborted() implements DriveOutcome {}
    private record Paused(StartStep.Elicit step) implements DriveOutcome {}

    @Override
    public Result begin(Session session, StartFlow flow, ExecutionContext exec, Map<String, Object> channelFacts) {
        Map<String, Object> facts = channelFacts == null ? Map.of() : channelFacts;
        Map<String, Object> stepVars = new LinkedHashMap<>();
        Map<String, Object> stepOutputs = new LinkedHashMap<>();
        try {
            DriveOutcome out = drive(flow, session, exec, facts, stepVars, stepOutputs, 0);
            return finish(flow, session, stepVars, stepOutputs, out);
        } catch (RuntimeException e) {
            log.warn("Start flow {} threw; falling back to greeting (session {})",
                    flow.id().value(), session.id().value(), e);
            store.delete(session.id());
            return complete(flow, "fallback", session, stepVars);
        }
    }

    @Override
    public Optional<Result> resumeIfActive(Session session, ExecutionContext exec, String replyText) {
        Optional<StartFlowCursor> cursor = store.load(session.id());
        if (cursor.isEmpty()) return Optional.empty();
        Optional<StartFlow> flow = registry.find(session.tenant(), cursor.get().flow());
        if (flow.isEmpty()) {
            // Flow was deleted/unpinned out from under a paused session.
            store.delete(session.id());
            return Optional.empty();
        }
        return Optional.of(resume(session, flow.get(), exec, cursor.get(), replyText));
    }

    private Result resume(Session session, StartFlow flow, ExecutionContext exec,
                          StartFlowCursor cursor, String replyText) {
        Map<String, Object> stepVars = new LinkedHashMap<>(cursor.scratch());
        Map<String, Object> stepOutputs = new LinkedHashMap<>(cursor.stepOutputs());
        try {
            List<StartStep> steps = flow.steps();
            StartStep paused = steps.stream()
                    .filter(s -> s.key().equals(cursor.currentStepKey())).findFirst().orElse(null);
            if (!(paused instanceof StartStep.Elicit elicit)) {
                store.delete(session.id());
                return complete(flow, "fallback", session, stepVars);
            }

            // Write the user's reply to the declared target, then route onward.
            captureReply(elicit.captureTarget(), replyText, exec, session, stepVars);

            BranchTarget target = route(elicit.next(), null, session, exec, Map.of(), stepVars, stepOutputs);
            DriveOutcome out;
            if (target instanceof BranchTarget.End end) {
                out = new Completed(end.outcomeLabel() == null ? "success" : end.outcomeLabel());
            } else if (target instanceof BranchTarget.Abort) {
                out = new Aborted();
            } else {
                int next = target instanceof BranchTarget.Step jump
                        ? indexOfKey(steps, jump.key())
                        : steps.indexOf(elicit) + 1;   // null target ⇒ fall through
                out = next < 0 ? new Aborted()
                        : drive(flow, session, exec, Map.of(), stepVars, stepOutputs, next);
            }
            return finish(flow, session, stepVars, stepOutputs, out);
        } catch (RuntimeException e) {
            log.warn("Start flow {} resume threw; falling back (session {})",
                    flow.id().value(), session.id().value(), e);
            store.delete(session.id());
            return complete(flow, "fallback", session, stepVars);
        }
    }

    /** The core stepping loop. Pauses (returns {@link Paused}) when it reaches an Elicit. */
    private DriveOutcome drive(StartFlow flow, Session session, ExecutionContext exec,
                               Map<String, Object> facts, Map<String, Object> stepVars,
                               Map<String, Object> stepOutputs, int startIndex) {
        List<StartStep> steps = flow.steps();
        if (steps.isEmpty()) return new Completed("success");

        long deadlineNanos = System.nanoTime() + flow.policy().totalBudget().toNanos();
        int maxSteps = flow.policy().maxSteps();

        int index = startIndex;
        int executed = 0;
        while (index >= 0 && index < steps.size()) {
            if (++executed > maxSteps) {
                log.warn("Start flow {} exceeded maxSteps={} (session {})",
                        flow.id().value(), maxSteps, session.id().value());
                return new Aborted();
            }
            if (System.nanoTime() > deadlineNanos) {
                log.warn("Start flow {} exceeded totalBudget (session {})",
                        flow.id().value(), session.id().value());
                return new Aborted();
            }

            StartStep step = steps.get(index);
            if (step instanceof StartStep.Elicit elicit) {
                return new Paused(elicit);
            }
            BranchTarget target = switch (step) {
                case StartStep.RunTool rt -> runTool(rt, session, exec, flow, facts, stepVars, stepOutputs);
                case StartStep.Decision d -> route(d.branches(), d.defaultTarget(),
                        session, exec, facts, stepVars, stepOutputs);
                case StartStep.Elicit ignored -> null;   // unreachable — handled above
            };

            if (target == null) {                        // fall through to next authored step
                index++;
                continue;
            }
            switch (target) {
                case BranchTarget.End end -> {
                    return new Completed(end.outcomeLabel() == null ? "success" : end.outcomeLabel());
                }
                case BranchTarget.Abort abort -> {
                    log.info("Start flow {} aborted: {} (session {})",
                            flow.id().value(), abort.reason(), session.id().value());
                    return new Aborted();
                }
                case BranchTarget.Step jump -> {
                    int next = indexOfKey(steps, jump.key());
                    if (next < 0) {
                        log.warn("Start flow {} branch to unknown step '{}' (session {})",
                                flow.id().value(), jump.key(), session.id().value());
                        return new Aborted();
                    }
                    index = next;
                }
            }
        }
        return new Completed("success");
    }

    /** Maps a drive outcome to a Result, persisting or clearing the cursor. */
    private Result finish(StartFlow flow, Session session, Map<String, Object> stepVars,
                          Map<String, Object> stepOutputs, DriveOutcome out) {
        return switch (out) {
            case Completed c -> {
                store.delete(session.id());
                yield complete(flow, c.outcome(), session, stepVars);
            }
            case Aborted ignored -> {
                store.delete(session.id());
                yield complete(flow, "fallback", session, stepVars);
            }
            case Paused p -> {
                store.save(new StartFlowCursor(session.id(), flow.id(), p.step().key(),
                        stepVars, stepOutputs, Instant.now()));
                yield pausedResult(p.step(), session, stepVars);
            }
        };
    }

    private Result pausedResult(StartStep.Elicit elicit, Session session, Map<String, Object> stepVars) {
        Map<String, Object> blackboard = blackboard(session, stepVars);
        String prompt = render(elicit.promptTemplate(), blackboard);
        List<String> quickReplies = new ArrayList<>();
        for (String t : elicit.quickReplyTemplates()) {
            String r = render(t, blackboard);
            if (r != null && !r.isBlank()) quickReplies.add(r);
        }
        StartFlowRunner.Elicitation pending = new StartFlowRunner.Elicitation(
                prompt, quickReplies, elicit.uiHintKind(), elicit.uiHintPayload());
        return new Result(Status.AWAITING_INPUT, null, prompt, quickReplies, null, pending);
    }

    private void captureReply(CaptureTarget target, String replyText, ExecutionContext exec,
                              Session session, Map<String, Object> stepVars) {
        switch (target) {
            case CaptureTarget.Entity e -> entityMemory.capture(session.id(), new EntityRef(
                    e.name(), replyText, EntityScope.SESSION, EntityKind.SINGLETON,
                    e.visibleToLlm(), new Ids.ToolId("startflow:elicit"), Instant.now(), Map.of()));
            case CaptureTarget.StepVar v -> stepVars.put(v.name(), replyText);
        }
    }

    private static int indexOfKey(List<StartStep> steps, String key) {
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).key().equals(key)) return i;
        return -1;
    }

    // ---- step execution -------------------------------------------------

    /** Runs one tool step; returns the routing target, or {@code null} to fall through. */
    private BranchTarget runTool(StartStep.RunTool rt, Session session, ExecutionContext exec,
                                 StartFlow flow, Map<String, Object> facts,
                                 Map<String, Object> stepVars, Map<String, Object> stepOutputs) {
        StepErrorPolicy onErr = rt.onError();

        ToolDescriptor descriptor = catalog.tool(rt.tool()).orElse(null);
        if (descriptor == null) {
            return onFailure(onErr, "unknown tool '" + rt.tool().value() + "'");
        }
        // Destructive/confirmable tools can't render a confirmation at start (§9.3).
        if (descriptor.policy() != null && descriptor.policy().confirmable()) {
            return onFailure(onErr, "confirmable tool '" + rt.tool().value() + "' not allowed at session start");
        }

        // Assemble args from the step's explicit bindings, then let the tool's
        // own declared entity bindings fill any gaps (mirrors the engine path).
        Map<String, Object> args = new LinkedHashMap<>();
        for (ArgBinding b : rt.inputs()) {
            Object v = resolve(b.source(), session, exec, facts, stepVars, stepOutputs);
            if (v != null) args.put(b.argName(), v);
        }
        Map<String, Object> effectiveArgs = EntityBinder.apply(
                args, descriptor.bindings(), entityMemory, session.id()).args();

        for (ArgBinding b : rt.inputs()) {
            if (b.mode() == com.yayatechandinnovations.yayaagentic.tool.BindingMode.REQUIRED
                    && !present(effectiveArgs, b.argName())) {
                return onFailure(onErr, "required arg '" + b.argName() + "' missing for tool '"
                        + rt.tool().value() + "'");
            }
        }

        AuthzDecision decision = authorizer.authorize(
                exec.principal(), descriptor.requires(), effectiveArgs,
                new AuthzContext(session.id(), exec.turnId(), exec.traceId(),
                        Map.of("toolId", descriptor.id().value(), "startFlow", flow.id().value())));
        if (decision.isDenied()) {
            // userSafeReason is never surfaced from the runner — the flow's
            // fallback greeting is what the user sees (§9.1).
            return onFailure(onErr, "authz denied for tool '" + rt.tool().value() + "'");
        }

        int attempts = onErr.onError() == StepErrorPolicy.OnError.RETRY ? onErr.maxRetries() + 1 : 1;
        Turn.ToolResult result = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            ToolExecutor.Outcome outcome = toolExecutor.execute(descriptor, effectiveArgs, exec, null);
            if (outcome instanceof ToolExecutor.Outcome.NeedsInput) {
                // Start-flow args come from config, not the model — an
                // unmet required field is a config failure, not elicitation.
                return onFailure(onErr, "tool '" + rt.tool().value() + "' needs input the flow didn't supply");
            }
            result = ((ToolExecutor.Outcome.Dispatched) outcome).result();
            if (result.status() == Turn.ToolResult.Status.OK) break;
        }
        if (result == null || result.status() != Turn.ToolResult.Status.OK) {
            String err = result == null ? "no result" : (result.error() == null ? "tool failed" : result.error());
            return onFailure(onErr, "tool '" + rt.tool().value() + "' " + err);
        }

        // Success — record the raw output for StepOutput bindings, then captures.
        stepOutputs.put(rt.key(), result.value());
        applyCaptures(rt.captures(), result.value(), descriptor.id(), session, stepVars);

        return route(rt.next(), null, session, exec, facts, stepVars, stepOutputs);
    }

    private void applyCaptures(List<OutputCapture> captures, Object resultValue,
                               Ids.ToolId producedBy, Session session, Map<String, Object> stepVars) {
        for (OutputCapture c : captures) {
            Object extracted = (c.jsonPointer() == null || c.jsonPointer().isBlank() || c.jsonPointer().equals("/"))
                    ? resultValue
                    : extractor.extract(resultValue, c.jsonPointer());
            if (extracted == null) continue;
            switch (c.target()) {
                case CaptureTarget.Entity e -> entityMemory.capture(session.id(), new EntityRef(
                        e.name(), extracted, EntityScope.SESSION, EntityKind.SINGLETON,
                        e.visibleToLlm(), producedBy, Instant.now(), Map.of()));
                case CaptureTarget.StepVar v -> stepVars.put(v.name(), extracted);
            }
        }
    }

    private BranchTarget onFailure(StepErrorPolicy onErr, String reason) {
        return switch (onErr.onError()) {
            case CONTINUE -> null;                       // fall through to the next step
            case ABORT, RETRY -> new BranchTarget.Abort(reason);
        };
    }

    // ---- routing + conditions ------------------------------------------

    private BranchTarget route(List<Branch> branches, BranchTarget defaultTarget,
                               Session session, ExecutionContext exec, Map<String, Object> facts,
                               Map<String, Object> stepVars, Map<String, Object> stepOutputs) {
        if (branches != null) {
            for (Branch b : branches) {
                if (evaluate(b.when(), session, exec, facts, stepVars, stepOutputs)) {
                    return b.target();
                }
            }
        }
        return defaultTarget;
    }

    private boolean evaluate(Condition cond, Session session, ExecutionContext exec,
                             Map<String, Object> facts, Map<String, Object> stepVars,
                             Map<String, Object> stepOutputs) {
        Object left = resolve(cond.left(), session, exec, facts, stepVars, stepOutputs);
        Object right = cond.right();
        return switch (cond.op()) {
            case EXISTS -> left != null;
            case ABSENT -> left == null;
            case TRUTHY -> truthy(left);
            case EQ -> looseEquals(left, right);
            case NEQ -> !looseEquals(left, right);
            case CONTAINS -> contains(left, right);
            case GT -> compare(left, right) > 0;
            case LT -> compare(left, right) < 0;
        };
    }

    // ---- value sources --------------------------------------------------

    private Object resolve(ValueSource source, Session session, ExecutionContext exec,
                           Map<String, Object> facts, Map<String, Object> stepVars,
                           Map<String, Object> stepOutputs) {
        return switch (source) {
            case ValueSource.Literal l -> l.value();
            case ValueSource.Channel c -> resolveChannel(c.field(), session, facts);
            case ValueSource.Principal p -> resolvePrincipal(p.claimPath(), exec.principal());
            case ValueSource.Entity e -> entityMemory.resolve(session.id(), e.entityName())
                    .map(EntityRef::value).orElseGet(() -> stepVars.get(e.entityName()));
            case ValueSource.StepOutput so -> {
                Object raw = stepOutputs.get(so.stepKey());
                if (raw == null) yield null;
                yield (so.jsonPointer() == null || so.jsonPointer().isBlank() || so.jsonPointer().equals("/"))
                        ? raw : extractor.extract(raw, so.jsonPointer());
            }
        };
    }

    private Object resolveChannel(String field, Session session, Map<String, Object> facts) {
        if (field == null) return null;
        Optional<EntityRef> fromMemory = entityMemory.resolve(session.id(), field);
        if (fromMemory.isPresent()) return fromMemory.get().value();
        if (facts.containsKey(field)) return facts.get(field);
        if (facts.containsKey("channel." + field)) return facts.get("channel." + field);
        if (("channel".equals(field) || "kind".equals(field)) && session.channel() != null) {
            return session.channel();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object resolvePrincipal(String claimPath, Principal principal) {
        if (principal == null || claimPath == null) return null;
        String path = claimPath.trim();
        if (path.equals("subject") || path.equals("sub")) return principal.subject();
        if (path.equals("tenant")) return principal.tenant() == null ? null : principal.tenant().value();
        if (path.startsWith("claims.")) path = path.substring("claims.".length());
        Object node = principal.claims();
        for (String seg : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> m)) return null;
            node = ((Map<String, Object>) m).get(seg);
            if (node == null) return null;
        }
        return node;
    }

    // ---- greeting -------------------------------------------------------

    private Result complete(StartFlow flow, String outcome, Session session, Map<String, Object> stepVars) {
        Status status = "fallback".equals(outcome) ? Status.ABORTED : Status.COMPLETED;
        GreetingPolicy gp = flow.greeting();

        // The static template is the opening on STATIC_TEMPLATE, and the guaranteed
        // fallback if an LLM-composed opening fails (§13).
        String template = "fallback".equals(outcome)
                ? gp.fallbackGreeting()
                : gp.outcomeGreetingTemplates().getOrDefault(outcome, gp.fallbackGreeting());

        Map<String, Object> blackboard = blackboard(session, stepVars);
        String greeting = template == null ? null : render(template, blackboard);

        List<String> quickReplies = new ArrayList<>();
        for (String t : gp.quickReplyTemplates()) {
            String r = render(t, blackboard);
            if (r != null && !r.isBlank()) quickReplies.add(r);
        }

        // §5.5 — an LLM-composed / hybrid opening on a completed outcome: assemble
        // the briefing here (deterministic); the engine owns the model call. Only
        // SHARED (visibleToLlm) entities reach the briefing — level-1 gate.
        StartFlowRunner.OpeningPlan plan = null;
        boolean llmMode = gp.mode() == GreetingMode.LLM_COMPOSED || gp.mode() == GreetingMode.HYBRID;
        if (status == Status.COMPLETED && llmMode && gp.llmOpening() != null) {
            LlmOpening o = gp.llmOpening();
            Map<String, Object> briefing = new LinkedHashMap<>();
            for (String name : o.briefingEntities()) {
                entityMemory.resolve(session.id(), name)
                        .filter(EntityRef::visibleToLlm)
                        .ifPresent(ref -> briefing.put(name, ref.value()));
            }
            String scaffold = gp.mode() == GreetingMode.HYBRID ? greeting : null;
            plan = new StartFlowRunner.OpeningPlan(
                    o.instruction(), briefing, o.quickReplyHints(), scaffold, o.budget());
        }

        return new Result(status, outcome, greeting, quickReplies, plan);
    }

    private Map<String, Object> blackboard(Session session, Map<String, Object> stepVars) {
        Map<String, Object> bb = new LinkedHashMap<>();
        for (EntityRef ref : entityMemory.all(session.id())) {
            bb.put(ref.name(), ref.value());
        }
        bb.putAll(stepVars);
        return bb;
    }

    private String render(String template, Map<String, Object> blackboard) {
        if (template == null || template.isEmpty()) return template;
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object v = navigate(blackboard, m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private Object navigate(Map<String, Object> root, String dotted) {
        Object node = root;
        for (String seg : dotted.split("\\.")) {
            if (!(node instanceof Map<?, ?> m)) return null;
            node = ((Map<String, Object>) m).get(seg);
            if (node == null) return null;
        }
        return node;
    }

    // ---- coercion helpers ----------------------------------------------

    private static boolean present(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return false;
        return !(v instanceof CharSequence cs && cs.toString().isBlank());
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0d;
        if (v instanceof CharSequence cs) {
            String s = cs.toString().trim();
            return !s.isEmpty() && !s.equalsIgnoreCase("false") && !s.equals("0");
        }
        if (v instanceof java.util.Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    private static boolean looseEquals(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a.equals(b)) return true;
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));
    }

    private static boolean contains(Object left, Object right) {
        if (left == null || right == null) return false;
        if (left instanceof java.util.Collection<?> c) {
            for (Object o : c) if (looseEquals(o, right)) return true;
            return false;
        }
        return String.valueOf(left).contains(String.valueOf(right));
    }

    private static int compare(Object a, Object b) {
        Double da = asDouble(a);
        Double db = asDouble(b);
        if (da != null && db != null) return Double.compare(da, db);
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static Double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null ? null : Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

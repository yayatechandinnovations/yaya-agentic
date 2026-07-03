package com.yayatechandinnovations.yayaagentic.startflow;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Admin-facing lifecycle for {@link StartFlow}s. Versioning mirrors profiles:
 * {@code create} makes v1, {@code update} publishes a NEW immutable version
 * (starts DRAFT), {@code publish} flips the latest version ACTIVE. In-flight
 * sessions pin their version at start, so edits never mutate a running session.
 * See {@code docs/design/session-start-flows.md} §6, §14.
 */
@Service
public class StartFlowAdminService {

    public record CreateCommand(String id, String name, String description,
                                List<StartStep> steps, GreetingPolicy greeting,
                                FlowPolicy policy, boolean publish) {}

    public record UpdateCommand(String name, String description,
                                List<StartStep> steps, GreetingPolicy greeting, FlowPolicy policy) {}

    public static final class NotFound extends RuntimeException {
        public NotFound(String m) { super(m); }
    }
    public static final class Conflict extends RuntimeException {
        public Conflict(String m) { super(m); }
    }
    public static final class Invalid extends RuntimeException {
        public Invalid(String m) { super(m); }
    }

    private final StartFlowRegistry registry;

    public StartFlowAdminService(StartFlowRegistry registry) {
        this.registry = registry;
    }

    public List<StartFlow> list(Ids.TenantId tenant) {
        return registry.listFor(tenant);
    }

    public StartFlow get(Ids.TenantId tenant, String id, Integer version) {
        Optional<StartFlow> found = version == null
                ? registry.findLatest(tenant, Ids.StartFlowId.latest(id))
                : registry.find(tenant, new Ids.StartFlowId(id, version));
        return found.orElseThrow(() -> new NotFound("start flow not found: " + id
                + (version == null ? "" : "@" + version)));
    }

    public StartFlow create(Ids.TenantId tenant, CreateCommand cmd) {
        String id = requireId(cmd.id());
        requireName(cmd.name());
        if (registry.findLatest(tenant, Ids.StartFlowId.latest(id)).isPresent()) {
            throw new Conflict("start flow already exists: " + id + " (use update to version it)");
        }
        StartFlow flow = new StartFlow(
                new Ids.StartFlowId(id, 1), tenant, cmd.name(), cmd.description(),
                cmd.steps(), cmd.greeting(), cmd.policy(),
                cmd.publish() ? StartFlow.FlowStatus.ACTIVE : StartFlow.FlowStatus.DRAFT,
                Instant.now(), Instant.now());
        validateSteps(flow);
        registry.register(flow);
        return flow;
    }

    /** Publishes a new DRAFT version carrying the updated content. */
    public StartFlow update(Ids.TenantId tenant, String id, UpdateCommand cmd) {
        StartFlow latest = get(tenant, id, null);
        StartFlow next = new StartFlow(
                new Ids.StartFlowId(id, latest.version() + 1), tenant,
                cmd.name() == null ? latest.name() : cmd.name(),
                cmd.description() == null ? latest.description() : cmd.description(),
                cmd.steps() == null ? latest.steps() : cmd.steps(),
                cmd.greeting() == null ? latest.greeting() : cmd.greeting(),
                cmd.policy() == null ? latest.policy() : cmd.policy(),
                StartFlow.FlowStatus.DRAFT, Instant.now(), Instant.now());
        validateSteps(next);
        registry.register(next);
        return next;
    }

    public StartFlow publish(Ids.TenantId tenant, String id) {
        StartFlow latest = get(tenant, id, null);
        if (latest.status() == StartFlow.FlowStatus.ARCHIVED) {
            throw new Conflict("cannot publish an archived start flow: " + id);
        }
        StartFlow active = new StartFlow(latest.id(), tenant, latest.name(), latest.description(),
                latest.steps(), latest.greeting(), latest.policy(),
                StartFlow.FlowStatus.ACTIVE, latest.createdAt(), Instant.now());
        registry.register(active);
        return active;
    }

    // ---- validation -----------------------------------------------------

    private static String requireId(String id) {
        if (id == null || id.isBlank()) throw new Invalid("start flow id is required");
        String trimmed = id.trim();
        if (!trimmed.matches("[a-zA-Z0-9_-]{1,128}")) {
            throw new Invalid("start flow id must be 1-128 chars of [a-zA-Z0-9_-]");
        }
        return trimmed;
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) throw new Invalid("start flow name is required");
    }

    /** Branch targets must reference a real step key; step keys must be unique. */
    private static void validateSteps(StartFlow flow) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (StartStep s : flow.steps()) {
            if (!keys.add(s.key())) throw new Invalid("duplicate step key: " + s.key());
        }
        for (StartStep s : flow.steps()) {
            List<Branch> branches = switch (s) {
                case StartStep.RunTool rt -> rt.next();
                case StartStep.Decision d -> d.branches();
                case StartStep.Elicit e -> e.next();
            };
            for (Branch b : branches) {
                if (b.target() instanceof BranchTarget.Step jump && !keys.contains(jump.key())) {
                    throw new Invalid("step '" + s.key() + "' branches to unknown step '" + jump.key() + "'");
                }
            }
        }
    }
}

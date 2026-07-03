package com.yayatechandinnovations.yayaagentic.startflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.Session;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityRef;
import com.yayatechandinnovations.yayaagentic.core.entity.InMemoryEntityMemory;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.tool.BindingMode;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolExecutor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SF-0 core: recognition → capture → branch → shaped greeting, all deterministic
 * and running through the same Authorizer + ToolExecutor as a normal tool call.
 */
class DefaultStartFlowRunnerTest {

    private final InMemoryEntityMemory entityMemory = new InMemoryEntityMemory();
    private final M0Catalog catalog = new M0Catalog();
    private final Authorizer allowAll = (principal, requirement, args, ctx) -> AuthzDecision.Allow.none();
    private final Ids.TenantId tenant = new Ids.TenantId("acme");
    private final Ids.SessionId sessionId = new Ids.SessionId("sess-1");

    private final InMemoryStartFlowStateStore store = new InMemoryStartFlowStateStore();
    private final InMemoryStartFlowRegistry registry = new InMemoryStartFlowRegistry();

    private DefaultStartFlowRunner runner(Function<Ids.ToolId, Turn.ToolResult> executor) {
        ToolExecutor toolExecutor = (descriptor, args, ctx, suggestedCallId) ->
                new ToolExecutor.Outcome.Dispatched(executor.apply(descriptor.id()));
        return new DefaultStartFlowRunner(allowAll, toolExecutor, catalog, entityMemory,
                new ObjectMapper(), store, registry);
    }

    private void registerTool(String id) {
        catalog.registerTool(new ToolDescriptor(
                new Ids.ToolId(id), "{}", "{}",
                PermissionRequirement.none(), new ToolHandlerRef.Bean(id), ToolPolicy.defaults()));
    }

    private Session session() {
        Principal principal = new Principal("anon", tenant, Set.of(), Map.of(), Instant.now());
        return new Session(sessionId, tenant, principal, new Ids.ProfileId("p", 1),
                "chat", List.of(), null, Map.of(), Instant.now(), null);
    }

    private ExecutionContext exec(Session s) {
        return new ExecutionContext(s.principal(), s.id(), new Ids.TurnId("start"), "trace", Map.of());
    }

    private StartFlow returningCustomerFlow() {
        registerTool("lookup");
        registerTool("register");
        StartStep lookup = new StartStep.RunTool(
                "step1", new Ids.ToolId("lookup"),
                List.of(new ArgBinding("phone", new ValueSource.Channel("endUserPhone"), BindingMode.FILL_IF_MISSING)),
                List.of(
                        new OutputCapture("/name", new CaptureTarget.Entity("customerName")),
                        new OutputCapture("/verified", new CaptureTarget.StepVar("isVerified"))),
                List.of(
                        new Branch(new Condition(new ValueSource.StepOutput("step1", "/found"),
                                Condition.Op.EQ, true), new BranchTarget.End("success")),
                        new Branch(Condition.always(), new BranchTarget.Step("register"))),
                StepErrorPolicy.abort());

        StartStep register = new StartStep.RunTool(
                "register", new Ids.ToolId("register"),
                List.of(new ArgBinding("phone", new ValueSource.Channel("endUserPhone"), BindingMode.FILL_IF_MISSING)),
                List.of(new OutputCapture("/id", new CaptureTarget.Entity("accountId"))),
                List.of(), StepErrorPolicy.abort());

        GreetingPolicy greeting = new GreetingPolicy(GreetingMode.STATIC_TEMPLATE,
                Map.of("success", "Welcome back {customerName}!"),
                "Hi there!", List.of("Track my order"), null);

        return new StartFlow(Ids.StartFlowId.latest("returning-customer"), tenant,
                "Returning customer", "recognize + register",
                List.of(lookup, register), greeting, FlowPolicy.defaults(),
                StartFlow.FlowStatus.ACTIVE, Instant.now(), Instant.now());
    }

    @Test
    void recognizes_returning_customer_and_shapes_greeting() {
        DefaultStartFlowRunner runner = runner(id -> switch (id.value()) {
            case "lookup" -> new Turn.ToolResult("c1", Turn.ToolResult.Status.OK,
                    Map.of("found", true, "name", "Ana", "verified", true), null);
            default -> new Turn.ToolResult("c2", Turn.ToolResult.Status.OK, Map.of("id", "acc_1"), null);
        });
        Session s = session();

        StartFlowRunner.Result result = runner.begin(s, returningCustomerFlow(), exec(s),
                Map.of("endUserPhone", "+15551234821"));

        assertThat(result.status()).isEqualTo(StartFlowRunner.Status.COMPLETED);
        assertThat(result.outcomeLabel()).isEqualTo("success");
        assertThat(result.greeting()).isEqualTo("Welcome back Ana!");
        assertThat(result.quickReplies()).containsExactly("Track my order");
        assertThat(entityMemory.resolve(sessionId, "customerName")).map(EntityRef::value).hasValue("Ana");
        // 'register' must NOT have run — we branched to End on the verified path.
        assertThat(entityMemory.resolve(sessionId, "accountId")).isEmpty();
    }

    @Test
    void branches_to_register_when_not_found() {
        DefaultStartFlowRunner runner = runner(id -> switch (id.value()) {
            case "lookup" -> new Turn.ToolResult("c1", Turn.ToolResult.Status.OK, Map.of("found", false), null);
            default -> new Turn.ToolResult("c2", Turn.ToolResult.Status.OK, Map.of("id", "acc_1"), null);
        });
        Session s = session();

        StartFlowRunner.Result result = runner.begin(s, returningCustomerFlow(), exec(s),
                Map.of("endUserPhone", "+15550000000"));

        assertThat(result.status()).isEqualTo(StartFlowRunner.Status.COMPLETED);
        assertThat(entityMemory.resolve(sessionId, "accountId")).map(EntityRef::value).hasValue("acc_1");
    }

    @Test
    void aborts_to_fallback_greeting_on_tool_failure() {
        DefaultStartFlowRunner runner = runner(id ->
                new Turn.ToolResult("c1", Turn.ToolResult.Status.FAILED, null, "boom"));
        Session s = session();

        StartFlowRunner.Result result = runner.begin(s, returningCustomerFlow(), exec(s),
                Map.of("endUserPhone", "+15550000000"));

        assertThat(result.status()).isEqualTo(StartFlowRunner.Status.ABORTED);
        assertThat(result.greeting()).isEqualTo("Hi there!");
    }

    @Test
    void confirmable_tool_is_refused_at_start() {
        catalog.registerTool(new ToolDescriptor(
                new Ids.ToolId("danger"), "{}", "{}", PermissionRequirement.none(),
                new ToolHandlerRef.Bean("danger"),
                new ToolPolicy(java.time.Duration.ofSeconds(10), 0, false, true, false)));
        StartStep step = new StartStep.RunTool("s", new Ids.ToolId("danger"),
                List.of(), List.of(), List.of(), StepErrorPolicy.abort());
        StartFlow flow = new StartFlow(Ids.StartFlowId.latest("danger-flow"), tenant, "d", "d",
                List.of(step), GreetingPolicy.staticFallback("Hello!"), FlowPolicy.defaults(),
                StartFlow.FlowStatus.ACTIVE, Instant.now(), Instant.now());
        DefaultStartFlowRunner runner = runner(id ->
                new Turn.ToolResult("c", Turn.ToolResult.Status.OK, Map.of(), null));
        Session s = session();

        StartFlowRunner.Result result = runner.begin(s, flow, exec(s), Map.of());

        assertThat(result.status()).isEqualTo(StartFlowRunner.Status.ABORTED);
        assertThat(result.greeting()).isEqualTo("Hello!");
    }

    @Test
    void registers_tool_used_by_flow() {
        registerTool("lookup");
        registerTool("register");
        assertThat(catalog.tool(new Ids.ToolId("lookup"))).isPresent();
    }

    @Test
    void elicit_pauses_then_resumes_and_completes() {
        registerTool("lookup");
        registerTool("verify");
        DefaultStartFlowRunner runner = runner(id -> switch (id.value()) {
            case "lookup" -> new Turn.ToolResult("c1", Turn.ToolResult.Status.OK,
                    Map.of("found", true, "name", "Ana"), null);
            default -> new Turn.ToolResult("c2", Turn.ToolResult.Status.OK,
                    Map.of("verified", true), null);
        });

        StartStep lookup = new StartStep.RunTool("lookup", new Ids.ToolId("lookup"),
                List.of(), List.of(new OutputCapture("/name", new CaptureTarget.Entity("customerName"))),
                List.of(), StepErrorPolicy.abort());
        StartStep otp = new StartStep.Elicit("otp",
                "Enter the code we texted you, {customerName}",
                List.of(), "otp_entry", Map.of(),
                new CaptureTarget.StepVar("code"), List.of());
        StartStep verify = new StartStep.RunTool("verify", new Ids.ToolId("verify"),
                List.of(new ArgBinding("code", new ValueSource.Entity("code"), BindingMode.REQUIRED)),
                List.of(new OutputCapture("/verified", new CaptureTarget.Entity("phoneVerified"))),
                List.of(), StepErrorPolicy.abort());

        GreetingPolicy greeting = new GreetingPolicy(GreetingMode.STATIC_TEMPLATE,
                Map.of("success", "You're verified, {customerName}!"), "Hi", List.of(), null);
        StartFlow flow = new StartFlow(new Ids.StartFlowId("otp-flow", 1), tenant, "OTP", "d",
                List.of(lookup, otp, verify), greeting, FlowPolicy.defaults(),
                StartFlow.FlowStatus.ACTIVE, Instant.now(), Instant.now());
        registry.register(flow);

        Session s = session();

        // Turn 1: begin → runs lookup, pauses on the OTP elicit.
        StartFlowRunner.Result r1 = runner.begin(s, flow, exec(s), Map.of());
        assertThat(r1.status()).isEqualTo(StartFlowRunner.Status.AWAITING_INPUT);
        assertThat(r1.pending().promptText()).isEqualTo("Enter the code we texted you, Ana");
        assertThat(r1.pending().uiHintKind()).isEqualTo("otp_entry");
        assertThat(store.load(sessionId)).isPresent();

        // Turn 2: the user's reply resumes → verify runs → completes.
        StartFlowRunner.Result r2 = runner.resumeIfActive(s, exec(s), "483920").orElseThrow();
        assertThat(r2.status()).isEqualTo(StartFlowRunner.Status.COMPLETED);
        assertThat(r2.greeting()).isEqualTo("You're verified, Ana!");
        assertThat(entityMemory.resolve(sessionId, "phoneVerified")).map(EntityRef::value).hasValue(true);
        assertThat(store.load(sessionId)).isEmpty();   // cursor cleared on completion
    }

    @Test
    void resume_is_empty_when_no_flow_is_paused() {
        DefaultStartFlowRunner runner = runner(id ->
                new Turn.ToolResult("c", Turn.ToolResult.Status.OK, Map.of(), null));
        Session s = session();
        assertThat(runner.resumeIfActive(s, exec(s), "hi")).isEmpty();
    }

    @Test
    void llm_composed_outcome_builds_a_briefing_of_shared_entities_only() {
        registerTool("lookup");
        DefaultStartFlowRunner runner = runner(id -> new Turn.ToolResult("c1", Turn.ToolResult.Status.OK,
                Map.of("found", true, "name", "Ana", "secret", "xyz", "verified", true), null));

        StartStep lookup = new StartStep.RunTool("step1", new Ids.ToolId("lookup"),
                List.of(),
                List.of(
                        new OutputCapture("/name", new CaptureTarget.Entity("customerName")),        // shared (default true)
                        new OutputCapture("/secret", new CaptureTarget.Entity("secretId", false)),    // NOT shared
                        new OutputCapture("/verified", new CaptureTarget.StepVar("isVerified"))),      // scratch, never in prompt
                List.of(), StepErrorPolicy.abort());

        GreetingPolicy greeting = new GreetingPolicy(GreetingMode.LLM_COMPOSED,
                Map.of("success", "template fallback"), "Hi", List.of(),
                new LlmOpening("Greet {customerName} warmly",
                        List.of("customerName", "secretId", "isVerified"), List.of(), false, null));

        StartFlow flow = new StartFlow(Ids.StartFlowId.latest("llm"), tenant, "LLM", "d",
                List.of(lookup), greeting, FlowPolicy.defaults(),
                StartFlow.FlowStatus.ACTIVE, Instant.now(), Instant.now());

        Session s = session();
        StartFlowRunner.Result result = runner.begin(s, flow, exec(s), Map.of());

        assertThat(result.status()).isEqualTo(StartFlowRunner.Status.COMPLETED);
        assertThat(result.openingPlan()).isNotNull();
        assertThat(result.openingPlan().instruction()).isEqualTo("Greet {customerName} warmly");
        // Only the shared entity reaches the briefing — the non-shared entity and
        // the step scratch var are excluded (§5.5 level-1 gate).
        assertThat(result.openingPlan().briefing()).containsOnlyKeys("customerName");
        assertThat(result.openingPlan().briefing()).containsEntry("customerName", "Ana");
    }
}

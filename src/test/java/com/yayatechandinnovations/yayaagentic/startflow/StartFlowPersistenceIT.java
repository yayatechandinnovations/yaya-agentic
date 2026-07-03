package com.yayatechandinnovations.yayaagentic.startflow;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.tool.BindingMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code session_start_flows} migration + the JSONB round-trip of
 * nested sealed types (StartStep / ValueSource / CaptureTarget / BranchTarget)
 * through {@code PostgresStartFlowRegistry} against a real Postgres.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class StartFlowPersistenceIT {

    @Autowired
    StartFlowRegistry registry;

    private final Ids.TenantId tenant = new Ids.TenantId("acme-sf");

    @Test
    void persists_and_reloads_nested_sealed_steps() {
        registry.register(sampleFlow());

        StartFlow loaded = registry.findLatest(tenant, Ids.StartFlowId.latest("returning-customer"))
                .orElseThrow();

        assertThat(loaded.name()).isEqualTo("Returning customer");
        assertThat(loaded.steps()).hasSize(2);

        StartStep.RunTool lookup = (StartStep.RunTool) loaded.steps().get(0);
        assertThat(lookup.tool().value()).isEqualTo("lookup");
        assertThat(lookup.inputs().get(0).source())
                .isInstanceOf(ValueSource.Channel.class)
                .extracting(s -> ((ValueSource.Channel) s).field()).isEqualTo("endUserPhone");

        // Capture survived with visibleToLlm ON (the default).
        CaptureTarget.Entity cap = (CaptureTarget.Entity) lookup.captures().get(0).target();
        assertThat(cap.name()).isEqualTo("customerName");
        assertThat(cap.visibleToLlm()).isTrue();

        // Branch condition (StepOutput EQ) + End target survived.
        Branch first = lookup.next().get(0);
        assertThat(first.when().left()).isInstanceOf(ValueSource.StepOutput.class);
        assertThat(first.target()).isInstanceOf(BranchTarget.End.class);
        assertThat(((BranchTarget.End) first.target()).outcomeLabel()).isEqualTo("success");
        assertThat(lookup.next().get(1).target()).isInstanceOf(BranchTarget.Step.class);

        // Greeting policy survived.
        assertThat(loaded.greeting().mode()).isEqualTo(GreetingMode.STATIC_TEMPLATE);
        assertThat(loaded.greeting().outcomeGreetingTemplates())
                .containsEntry("success", "Welcome back {customerName}!");

        // Pinned find works too.
        assertThat(registry.find(tenant, new Ids.StartFlowId("returning-customer", 1))).isPresent();
    }

    private StartFlow sampleFlow() {
        StartStep lookup = new StartStep.RunTool(
                "step1", new Ids.ToolId("lookup"),
                List.of(new ArgBinding("phone", new ValueSource.Channel("endUserPhone"), BindingMode.FILL_IF_MISSING)),
                List.of(new OutputCapture("/name", new CaptureTarget.Entity("customerName")),
                        new OutputCapture("/verified", new CaptureTarget.StepVar("isVerified"))),
                List.of(new Branch(new Condition(new ValueSource.StepOutput("step1", "/found"),
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

        return new StartFlow(new Ids.StartFlowId("returning-customer", 1), tenant,
                "Returning customer", "recognize + register",
                List.of(lookup, register), greeting, FlowPolicy.defaults(),
                StartFlow.FlowStatus.ACTIVE, Instant.now(), Instant.now());
    }
}

package com.yayatechandinnovations.yayaagentic.startflow;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Versioning + publish lifecycle + step validation for the admin service. */
class StartFlowAdminServiceTest {

    private final InMemoryStartFlowRegistry registry = new InMemoryStartFlowRegistry();
    private final StartFlowAdminService service = new StartFlowAdminService(registry);
    private final Ids.TenantId tenant = new Ids.TenantId("acme");

    private StartStep okStep() {
        return new StartStep.RunTool("s1", new Ids.ToolId("lookup"),
                List.of(), List.of(), List.of(), StepErrorPolicy.abort());
    }

    private StartFlowAdminService.CreateCommand createCmd(boolean publish) {
        return new StartFlowAdminService.CreateCommand("welcome", "Welcome", "desc",
                List.of(okStep()), GreetingPolicy.staticFallback("Hi!"), FlowPolicy.defaults(), publish);
    }

    @Test
    void create_makes_v1_draft_by_default() {
        StartFlow f = service.create(tenant, createCmd(false));
        assertThat(f.version()).isEqualTo(1);
        assertThat(f.status()).isEqualTo(StartFlow.FlowStatus.DRAFT);
    }

    @Test
    void create_with_publish_is_active() {
        assertThat(service.create(tenant, createCmd(true)).status())
                .isEqualTo(StartFlow.FlowStatus.ACTIVE);
    }

    @Test
    void create_duplicate_id_conflicts() {
        service.create(tenant, createCmd(false));
        assertThatThrownBy(() -> service.create(tenant, createCmd(false)))
                .isInstanceOf(StartFlowAdminService.Conflict.class);
    }

    @Test
    void update_publishes_a_new_draft_version() {
        service.create(tenant, createCmd(true));
        StartFlow v2 = service.update(tenant, "welcome", new StartFlowAdminService.UpdateCommand(
                "Welcome v2", null, null, null, null));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.status()).isEqualTo(StartFlow.FlowStatus.DRAFT);
        assertThat(v2.name()).isEqualTo("Welcome v2");
        // findLatest now returns v2.
        assertThat(service.get(tenant, "welcome", null).version()).isEqualTo(2);
    }

    @Test
    void publish_flips_latest_active() {
        service.create(tenant, createCmd(false));
        StartFlow published = service.publish(tenant, "welcome");
        assertThat(published.status()).isEqualTo(StartFlow.FlowStatus.ACTIVE);
    }

    @Test
    void get_missing_throws_not_found() {
        assertThatThrownBy(() -> service.get(tenant, "nope", null))
                .isInstanceOf(StartFlowAdminService.NotFound.class);
    }

    @Test
    void rejects_branch_to_unknown_step() {
        StartStep bad = new StartStep.RunTool("s1", new Ids.ToolId("lookup"),
                List.of(), List.of(),
                List.of(new Branch(Condition.always(), new BranchTarget.Step("ghost"))),
                StepErrorPolicy.abort());
        var cmd = new StartFlowAdminService.CreateCommand("bad", "Bad", null,
                List.of(bad), GreetingPolicy.staticFallback("Hi"), FlowPolicy.defaults(), false);
        assertThatThrownBy(() -> service.create(tenant, cmd))
                .isInstanceOf(StartFlowAdminService.Invalid.class);
    }

    @Test
    void rejects_bad_id() {
        var cmd = new StartFlowAdminService.CreateCommand("has spaces!", "X", null,
                List.of(okStep()), GreetingPolicy.staticFallback("Hi"), FlowPolicy.defaults(), false);
        assertThatThrownBy(() -> service.create(tenant, cmd))
                .isInstanceOf(StartFlowAdminService.Invalid.class);
    }
}

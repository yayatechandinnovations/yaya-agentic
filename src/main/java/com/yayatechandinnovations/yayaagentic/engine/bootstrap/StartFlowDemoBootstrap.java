package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.startflow.ArgBinding;
import com.yayatechandinnovations.yayaagentic.startflow.Branch;
import com.yayatechandinnovations.yayaagentic.startflow.BranchTarget;
import com.yayatechandinnovations.yayaagentic.startflow.CaptureTarget;
import com.yayatechandinnovations.yayaagentic.startflow.Condition;
import com.yayatechandinnovations.yayaagentic.startflow.FlowPolicy;
import com.yayatechandinnovations.yayaagentic.startflow.GreetingMode;
import com.yayatechandinnovations.yayaagentic.startflow.GreetingPolicy;
import com.yayatechandinnovations.yayaagentic.startflow.LlmOpening;
import com.yayatechandinnovations.yayaagentic.startflow.OutputCapture;
import com.yayatechandinnovations.yayaagentic.startflow.StartFlow;
import com.yayatechandinnovations.yayaagentic.startflow.StartFlowRegistry;
import com.yayatechandinnovations.yayaagentic.startflow.StartStep;
import com.yayatechandinnovations.yayaagentic.startflow.ValueSource;
import com.yayatechandinnovations.yayaagentic.tool.BindingMode;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Seeds a ready-to-try "welcome-back" Session Start Flow (+ its demo lookup tool)
 * for the default tenant, so an operator can bind it to a chat entry point / sender
 * and immediately see recognition → an LLM-composed greeting. Idempotent; disable
 * with {@code yaya.agentic.startflow.seed-demo=false}. Runs after the core
 * bootstraps so the default tenant exists.
 */
@Component
@Order(80)
@ConditionalOnProperty(name = "yaya.agentic.startflow.seed-demo", havingValue = "true", matchIfMissing = true)
public class StartFlowDemoBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartFlowDemoBootstrap.class);

    private static final Ids.TenantId TENANT = new Ids.TenantId("default");
    private static final Ids.ToolId DEMO_TOOL = new Ids.ToolId("startflow-demo-lookup");
    private static final Ids.StartFlowId DEMO_FLOW = new Ids.StartFlowId("welcome-back-demo", 1);

    private final M0Catalog catalog;
    private final StartFlowRegistry startFlows;

    public StartFlowDemoBootstrap(M0Catalog catalog, StartFlowRegistry startFlows) {
        this.catalog = catalog;
        this.startFlows = startFlows;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedInto(TENANT);
    }

    /** Seed the welcome-back Session Start Flow (+ its demo lookup tool) into
     *  {@code tenant}. Idempotent; reused by the per-workspace demo provisioner. */
    public void seedInto(Ids.TenantId tenant) {
        // Register the demo lookup tool into the runtime catalog (idempotent).
        catalog.registerTool(new ToolDescriptor(
                DEMO_TOOL, "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                PermissionRequirement.none(),
                new ToolHandlerRef.Bean("startflowDemoLookupTool"), ToolPolicy.defaults()));

        if (startFlows.findLatest(tenant, Ids.StartFlowId.latest(DEMO_FLOW.value())).isPresent()) {
            return;   // already seeded
        }

        StartStep lookup = new StartStep.RunTool(
                "lookup", DEMO_TOOL,
                List.of(new ArgBinding("phone", new ValueSource.Channel("endUserPhone"), BindingMode.FILL_IF_MISSING)),
                List.of(new OutputCapture("/name", new CaptureTarget.Entity("customerName")),
                        new OutputCapture("/tier", new CaptureTarget.Entity("tier"))),
                List.of(new Branch(new Condition(new ValueSource.StepOutput("lookup", "/found"),
                        Condition.Op.EQ, true), new BranchTarget.End("success"))),
                null);

        GreetingPolicy greeting = new GreetingPolicy(
                GreetingMode.LLM_COMPOSED,
                Map.of("success", "Welcome back, {customerName}! 👋"),
                "Hi! How can I help you today?",
                List.of("Track my order", "Talk to support"),
                new LlmOpening(
                        "Greet the returning customer warmly by first name, acknowledge their loyalty tier, "
                                + "and offer to help — one friendly sentence.",
                        List.of("customerName", "tier"), List.of(), false, null));

        startFlows.register(new StartFlow(
                DEMO_FLOW, tenant, "Welcome back (demo)",
                "Recognizes a returning customer and greets them with an LLM-composed opening.",
                List.of(lookup), greeting, FlowPolicy.defaults(),
                StartFlow.FlowStatus.ACTIVE, Instant.now(), Instant.now()));

        log.info("Seeded demo Session Start Flow '{}' for tenant '{}'", DEMO_FLOW.value(), tenant.value());
    }
}

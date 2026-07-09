package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceCreatedEvent;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Seeds the full demo set into a tenant, so every account (not just the built-in
 * {@code default} tenant) gets the demos out of the box. It reuses the same
 * {@code seedInto(tenant)} the startup bootstraps use — hello-world profile +
 * Yaya-FAQ knowledge and the welcome-back Session Start Flow. (The demo
 * storefronts moved out of the core with the commerce subsystem — stores are
 * provisioned in yaya-sales now.)
 *
 * <p>Two triggers, both gated by {@code yaya.agentic.demo.provision-per-workspace}
 * (default on):
 * <ul>
 *   <li><b>New workspace</b> — {@link #onWorkspaceCreated} runs after the
 *       workspace's tenant row commits, so a fresh account is demo-ready.</li>
 *   <li><b>Backfill</b> — {@link #run} at startup provisions every existing
 *       tenant (except {@code default}, already seeded by the ordered startup
 *       bootstraps), so accounts created before this feature also get demos.</li>
 * </ul>
 *
 * <p>Each demo is provisioned in its own transaction and independently guarded,
 * so one failing demo (e.g. knowledge ingestion without an embedding key) never
 * blocks the others.</p>
 */
@Component
@Order(200)   // after RuntimeCatalogLoader (100) so the runtime catalog is hydrated
public class TenantDemoProvisioner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantDemoProvisioner.class);

    private final HelloWorldProfileBootstrap helloWorld;
    private final KnowledgeBootstrap knowledge;
    private final Optional<StartFlowDemoBootstrap> startFlowDemo;   // @ConditionalOnProperty — may be absent
    private final TenantRepository tenants;
    private final boolean enabled;

    public TenantDemoProvisioner(HelloWorldProfileBootstrap helloWorld,
                                 KnowledgeBootstrap knowledge,
                                 Optional<StartFlowDemoBootstrap> startFlowDemo,
                                 TenantRepository tenants,
                                 @Value("${yaya.agentic.demo.provision-per-workspace:true}") boolean enabled) {
        this.helloWorld = helloWorld;
        this.knowledge = knowledge;
        this.startFlowDemo = startFlowDemo;
        this.tenants = tenants;
        this.enabled = enabled;
    }

    /** Seed all demos into {@code tenant}. Order matters: knowledge attaches to
     *  the hello-world profile. */
    public void provision(Ids.TenantId tenant) {
        step(tenant, "hello-world", helloWorld::seedInto);
        step(tenant, "knowledge", knowledge::seedInto);
        startFlowDemo.ifPresent(sf -> step(tenant, "start-flow", sf::seedInto));
    }

    private void step(Ids.TenantId tenant, String demo, Consumer<Ids.TenantId> seed) {
        try {
            seed.accept(tenant);
        } catch (RuntimeException ex) {
            // One demo failing (e.g. knowledge ingestion) must not block the rest.
            log.warn("Demo '{}' failed to provision for tenant '{}': {}", demo, tenant.value(), ex.toString());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onWorkspaceCreated(WorkspaceCreatedEvent event) {
        if (!enabled) return;
        log.info("Provisioning demos for new workspace '{}'", event.tenant().value());
        provision(event.tenant());
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        String defaultTenant = HelloWorldProfileBootstrap.DEFAULT_TENANT.value();
        int provisioned = 0;
        for (var tenant : tenants.findAll()) {
            if (defaultTenant.equals(tenant.getId())) continue;   // seeded by the ordered startup bootstraps
            provision(new Ids.TenantId(tenant.getId()));
            provisioned++;
        }
        if (provisioned > 0) {
            log.info("Backfilled demos into {} existing workspace(s)", provisioned);
        }
    }
}

package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.yayatechandinnovations.yayaagentic.persistence.CapabilityRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ToolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hydrates {@link M0Catalog} from Postgres at startup. Runs AFTER
 * {@link HelloWorldProfileBootstrap} so any seed rows it persists are
 * included; the bootstrap's in-memory registration is now redundant but
 * harmless (catalog.register* is idempotent — last write wins).
 * <p>
 * Per-admin-POST updates are pushed in by {@code AdminController} directly,
 * keeping the catalog in sync with PG without requiring a restart.
 */
@Component
@Order(100)
public class RuntimeCatalogLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RuntimeCatalogLoader.class);

    private final ToolRepository tools;
    private final CapabilityRepository capabilities;
    private final M0Catalog catalog;
    private final CatalogMapper mapper;

    public RuntimeCatalogLoader(ToolRepository tools,
                                CapabilityRepository capabilities,
                                M0Catalog catalog,
                                CatalogMapper mapper) {
        this.tools = tools;
        this.capabilities = capabilities;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public void run(ApplicationArguments args) {
        int toolCount = 0;
        for (var entity : tools.findAll()) {
            catalog.registerTool(mapper.toDescriptor(entity));
            toolCount++;
        }
        int capCount = 0;
        for (var entity : capabilities.findAll()) {
            catalog.registerCapability(mapper.toCapability(entity));
            capCount++;
        }
        log.info("Hydrated runtime catalog from Postgres: {} tools, {} capabilities", toolCount, capCount);
    }
}

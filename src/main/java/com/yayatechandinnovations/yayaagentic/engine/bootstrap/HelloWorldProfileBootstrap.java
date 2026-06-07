package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.persistence.AuthBindingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuthBindingRepository;
import com.yayatechandinnovations.yayaagentic.persistence.CapabilityEntity;
import com.yayatechandinnovations.yayaagentic.persistence.CapabilityRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ToolEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ToolRepository;
import com.yayatechandinnovations.yayaagentic.profile.Capability;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * M1 bootstrap. Idempotently seeds the default tenant + hello-world
 * profile + echo tool + say-hello capability into Postgres on startup,
 * then mirrors them into the runtime {@link M0Catalog} so the engine's
 * lookups keep working unchanged. M1 phase B will replace the in-memory
 * catalog with PG-backed registries; this bootstrap then becomes
 * pure-config.
 */
@Component
@Order(0)
public class HelloWorldProfileBootstrap implements ApplicationRunner {

    public static final Ids.TenantId DEFAULT_TENANT = new Ids.TenantId("default");
    public static final Ids.ProfileId HELLO_WORLD_PROFILE = new Ids.ProfileId("hello-world", 1);
    public static final Ids.CapabilityId SAY_HELLO = new Ids.CapabilityId("say-hello");
    public static final Ids.ToolId ECHO = new Ids.ToolId("echo");
    public static final Ids.AuthBindingId DEV_NOOP_AUTH = new Ids.AuthBindingId("dev-noop");

    private static final String ECHO_INPUT_SCHEMA =
            "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}}}";
    private static final String ECHO_OUTPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"echo\":{\"type\":\"string\"}}}";

    private final TenantRepository tenants;
    private final AuthBindingRepository authBindings;
    private final ToolRepository tools;
    private final CapabilityRepository capabilities;
    private final ProfileRegistry profileRegistry;
    private final M0Catalog catalog;
    private final ObjectMapper json;

    public HelloWorldProfileBootstrap(TenantRepository tenants,
                                      AuthBindingRepository authBindings,
                                      ToolRepository tools,
                                      CapabilityRepository capabilities,
                                      ProfileRegistry profileRegistry,
                                      M0Catalog catalog,
                                      ObjectMapper json) {
        this.tenants = tenants;
        this.authBindings = authBindings;
        this.tools = tools;
        this.capabilities = capabilities;
        this.profileRegistry = profileRegistry;
        this.catalog = catalog;
        this.json = json;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureTenant();
        ensureAuthBinding();
        ensureEchoTool();
        ensureSayHelloCapability();
        ensureHelloWorldProfile();
        mirrorIntoRuntimeCatalog();
    }

    // ---- seeding ---------------------------------------------------------

    private void ensureTenant() {
        if (tenants.existsById(DEFAULT_TENANT.value())) return;
        tenants.save(new TenantEntity(DEFAULT_TENANT.value(), "Default"));
    }

    private void ensureAuthBinding() {
        AuthBindingEntity.PK pk = new AuthBindingEntity.PK(DEFAULT_TENANT.value(), DEV_NOOP_AUTH.value());
        if (authBindings.existsById(pk)) return;
        authBindings.save(new AuthBindingEntity(DEFAULT_TENANT.value(), DEV_NOOP_AUTH.value(), "noop"));
    }

    private void ensureEchoTool() {
        ToolEntity.PK pk = new ToolEntity.PK(DEFAULT_TENANT.value(), ECHO.value(), 1);
        if (tools.existsById(pk)) return;
        ToolEntity entity = new ToolEntity(
                DEFAULT_TENANT.value(), ECHO.value(), 1,
                ECHO_INPUT_SCHEMA, ECHO_OUTPUT_SCHEMA, "BEAN");
        entity.setHandlerBeanName("echoTool");
        entity.setPolicyJson(writeJson(ToolPolicy.defaults()));
        entity.setRequiresJson(writeJson(PermissionRequirement.none()));
        tools.save(entity);
    }

    private void ensureSayHelloCapability() {
        CapabilityEntity.PK pk = new CapabilityEntity.PK(DEFAULT_TENANT.value(), SAY_HELLO.value(), 1);
        if (capabilities.existsById(pk)) return;
        CapabilityEntity entity = new CapabilityEntity(
                DEFAULT_TENANT.value(), SAY_HELLO.value(), 1, "Echo something back");
        entity.setDescription("I'll repeat what you say.");
        entity.setLlmGuidance("Use the echo tool when the user asks to repeat or echo a phrase.");
        entity.setToolIdsJson(writeJson(List.of(ECHO.value())));
        entity.setFollowUpHintsJson(writeJson(List.of("echo something else", "what else can you do?")));
        capabilities.save(entity);
    }

    private void ensureHelloWorldProfile() {
        if (profileRegistry.find(DEFAULT_TENANT, HELLO_WORLD_PROFILE).isPresent()) return;
        profileRegistry.register(new Profile(
                HELLO_WORLD_PROFILE,
                DEFAULT_TENANT,
                "Yaya (Hello World)",
                "I'm Yaya. I can echo what you say, or answer questions about myself.",
                "You are Yaya, a helpful assistant in demo mode. Keep replies short. "
                        + "When a user asks a factual question about Yaya, ground your "
                        + "answer in the retrieved context and cite the chunk.",
                List.of(SAY_HELLO, KnowledgeBootstrap.SEARCH_KNOWLEDGE_CAP),
                List.of(KnowledgeBootstrap.YAYA_FAQ_SOURCE),
                DEV_NOOP_AUTH,
                "en",
                Map.of("introQuickReplies",
                        List.of("echo hello", "what is Yaya?", "what can you do?"))));
    }

    // ---- mirror PG → in-memory runtime catalog (engine lookup path) ------

    private void mirrorIntoRuntimeCatalog() {
        catalog.registerTool(new ToolDescriptor(
                ECHO, ECHO_INPUT_SCHEMA, ECHO_OUTPUT_SCHEMA,
                PermissionRequirement.none(),
                new ToolHandlerRef.Bean("echoTool"),
                ToolPolicy.defaults()));

        catalog.registerCapability(new Capability(
                SAY_HELLO, "Echo something back",
                "I'll repeat what you say.",
                "Use the echo tool when the user asks to repeat or echo a phrase.",
                List.of(ECHO),
                List.of("echo something else", "what else can you do?"),
                PermissionRequirement.none()));
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to encode JSON", ex);
        }
    }
}

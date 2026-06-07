package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.demos.retail.FindOrderToolHandler;
import com.yayatechandinnovations.yayaagentic.demos.retail.StartReturnToolHandler;
import com.yayatechandinnovations.yayaagentic.demos.retail.TrackShipmentToolHandler;
import com.yayatechandinnovations.yayaagentic.knowledge.IngestionPolicy;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeSource;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalPolicy;
import com.yayatechandinnovations.yayaagentic.knowledge.SourceLocation;
import com.yayatechandinnovations.yayaagentic.knowledge.ingest.IngestionOrchestrator;
import com.yayatechandinnovations.yayaagentic.knowledge.tool.SearchKnowledgeToolHandler;
import com.yayatechandinnovations.yayaagentic.persistence.CapabilityEntity;
import com.yayatechandinnovations.yayaagentic.persistence.CapabilityRepository;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeSourceEntity;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeSourceRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ProfileKnowledgeBindingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ProfileKnowledgeBindingRepository;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Seeds the retail-customer demo profile end-to-end at startup:
 * <ul>
 *   <li>Three Bean tools (find_order, track_shipment, start_return —
 *       the last marked {@code confirmable:true}).</li>
 *   <li>Two capabilities (order-management + browse-policies).</li>
 *   <li>Two Inline knowledge sources (return policy + shipping FAQ),
 *       ingested in the foreground so the playground works immediately.</li>
 *   <li>The {@code retail-customer@1} profile, attached to the FAQ sources
 *       and reusing the {@code search_knowledge} tool from
 *       {@link KnowledgeBootstrap}.</li>
 * </ul>
 * AuthZ for the order tools is enforced by
 * {@link com.yayatechandinnovations.yayaagentic.demos.retail.OrderOwnershipAuthorizer},
 * which joins the chain via Spring's @Order — no per-tool wiring needed
 * here.
 */
@Component
@Order(60)
public class RetailCustomerBootstrap implements ApplicationRunner {

    public static final Ids.TenantId DEFAULT_TENANT =
            HelloWorldProfileBootstrap.DEFAULT_TENANT;

    public static final Ids.ProfileId RETAIL_CUSTOMER_PROFILE =
            new Ids.ProfileId("retail-customer", 1);

    public static final Ids.CapabilityId ORDER_MANAGEMENT =
            new Ids.CapabilityId("order-management");
    public static final Ids.CapabilityId BROWSE_POLICIES =
            new Ids.CapabilityId("browse-policies");

    public static final Ids.ToolId FIND_ORDER = new Ids.ToolId("find_order");
    public static final Ids.ToolId TRACK_SHIPMENT = new Ids.ToolId("track_shipment");
    public static final Ids.ToolId START_RETURN = new Ids.ToolId("start_return");

    public static final Ids.KnowledgeSourceId RETURN_POLICY_SOURCE =
            new Ids.KnowledgeSourceId("retail-return-policy");
    public static final Ids.KnowledgeSourceId SHIPPING_FAQ_SOURCE =
            new Ids.KnowledgeSourceId("retail-shipping-faq");

    private static final int SOURCE_VERSION = 1;

    private final M0Catalog catalog;
    private final ProfileRegistry profileRegistry;
    private final KnowledgeSourceRepository sourceRepo;
    private final ProfileKnowledgeBindingRepository bindingRepo;
    private final ToolRepository toolRepo;
    private final CapabilityRepository capabilityRepo;
    private final IngestionOrchestrator ingestion;
    private final ObjectMapper json;

    public RetailCustomerBootstrap(M0Catalog catalog,
                                   ProfileRegistry profileRegistry,
                                   KnowledgeSourceRepository sourceRepo,
                                   ProfileKnowledgeBindingRepository bindingRepo,
                                   ToolRepository toolRepo,
                                   CapabilityRepository capabilityRepo,
                                   IngestionOrchestrator ingestion,
                                   ObjectMapper json) {
        this.catalog = catalog;
        this.profileRegistry = profileRegistry;
        this.sourceRepo = sourceRepo;
        this.bindingRepo = bindingRepo;
        this.toolRepo = toolRepo;
        this.capabilityRepo = capabilityRepo;
        this.ingestion = ingestion;
        this.json = json;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        registerToolsAndCapabilities();
        var policySource = registerSource(RETURN_POLICY_SOURCE,
                "Retail return policy", returnPolicyDocs());
        var shippingSource = registerSource(SHIPPING_FAQ_SOURCE,
                "Retail shipping FAQ", shippingFaqDocs());
        registerProfile();
        bindSourceToProfile(RETURN_POLICY_SOURCE);
        bindSourceToProfile(SHIPPING_FAQ_SOURCE);
        ingestion.ingest(policySource);
        ingestion.ingest(shippingSource);
    }

    // ---- tools + capabilities ------------------------------------------

    private void registerToolsAndCapabilities() {
        // Tools — persist to PG so the admin Tools screen lists them, and
        // mirror into the runtime M0Catalog so the engine's lookup path
        // doesn't have to round-trip.
        registerTool(FIND_ORDER,
                FindOrderToolHandler.INPUT_SCHEMA, FindOrderToolHandler.OUTPUT_SCHEMA,
                "findOrderTool", ToolPolicy.defaults());

        registerTool(TRACK_SHIPMENT,
                TrackShipmentToolHandler.INPUT_SCHEMA, TrackShipmentToolHandler.OUTPUT_SCHEMA,
                "trackShipmentTool", ToolPolicy.defaults());

        registerTool(START_RETURN,
                StartReturnToolHandler.INPUT_SCHEMA, StartReturnToolHandler.OUTPUT_SCHEMA,
                "startReturnTool",
                new ToolPolicy(Duration.ofSeconds(10), 0, false, /* confirmable */ true));

        registerCapability(new Capability(
                ORDER_MANAGEMENT,
                "Manage my orders",
                "Find my orders, check shipment status, or start a return.",
                "Use find_order to look up the body of an order. Use "
                        + "track_shipment when the user asks about delivery / "
                        + "tracking / ETA. Use start_return when the user wants "
                        + "to return a delivered order; the engine will confirm "
                        + "with the user before dispatching that one.",
                List.of(FIND_ORDER, TRACK_SHIPMENT, START_RETURN),
                List.of("track another order", "start a return", "find an order"),
                PermissionRequirement.none()));

        registerCapability(new Capability(
                BROWSE_POLICIES,
                "Read our policies",
                "Look up return and shipping policy details and cite where the answer comes from.",
                "Use search_knowledge for questions about return windows, "
                        + "shipping times, exchanges, lost packages, refund "
                        + "methods, and any other policy questions. Ground your "
                        + "answer in the retrieved chunks and cite them.",
                List.of(new Ids.ToolId(SearchKnowledgeToolHandler.TOOL_ID)),
                List.of("what's the return window?", "do you ship internationally?"),
                PermissionRequirement.none()));
    }

    private void registerTool(Ids.ToolId id, String inputSchema, String outputSchema,
                              String beanName, ToolPolicy policy) {
        ToolEntity.PK pk = new ToolEntity.PK(DEFAULT_TENANT.value(), id.value(), 1);
        if (!toolRepo.existsById(pk)) {
            ToolEntity entity = new ToolEntity(
                    DEFAULT_TENANT.value(), id.value(), 1,
                    inputSchema, outputSchema, "BEAN");
            entity.setHandlerBeanName(beanName);
            entity.setPolicyJson(writeJson(policy));
            entity.setRequiresJson(writeJson(PermissionRequirement.none()));
            toolRepo.save(entity);
        }
        catalog.registerTool(new ToolDescriptor(
                id, inputSchema, outputSchema,
                PermissionRequirement.none(),
                new ToolHandlerRef.Bean(beanName),
                policy));
    }

    private void registerCapability(Capability cap) {
        CapabilityEntity.PK pk = new CapabilityEntity.PK(
                DEFAULT_TENANT.value(), cap.id().value(), 1);
        if (!capabilityRepo.existsById(pk)) {
            CapabilityEntity entity = new CapabilityEntity(
                    DEFAULT_TENANT.value(), cap.id().value(), 1,
                    cap.userFacingLabel());
            entity.setDescription(cap.userFacingDescription());
            entity.setLlmGuidance(cap.llmGuidance());
            entity.setToolIdsJson(writeJson(
                    cap.tools().stream().map(Ids.ToolId::value).toList()));
            entity.setFollowUpHintsJson(writeJson(
                    cap.followUpHints() == null ? List.of() : cap.followUpHints()));
            entity.setRequiresJson(writeJson(PermissionRequirement.none()));
            capabilityRepo.save(entity);
        }
        catalog.registerCapability(cap);
    }

    // ---- profile -------------------------------------------------------

    private void registerProfile() {
        if (profileRegistry.find(DEFAULT_TENANT, RETAIL_CUSTOMER_PROFILE).isPresent()) return;
        profileRegistry.register(new Profile(
                RETAIL_CUSTOMER_PROFILE,
                DEFAULT_TENANT,
                "Yaya for Customers",
                "Hi! I can track your orders, start a return, or answer "
                        + "questions about our policies.",
                "You are Yaya, a retail-customer assistant. Keep replies short "
                        + "and direct. When the user asks about a specific "
                        + "order, propose the matching tool (find_order, "
                        + "track_shipment, or start_return). Never invent order "
                        + "ids, prices, or tracking numbers — only quote what a "
                        + "tool returned. For policy questions, search the "
                        + "knowledge base and cite the chunk. If a tool is "
                        + "denied, paraphrase the user-safe reason; never expose "
                        + "internal error text or other customers' ids.",
                List.of(ORDER_MANAGEMENT, BROWSE_POLICIES,
                        KnowledgeBootstrap.SEARCH_KNOWLEDGE_CAP),
                List.of(RETURN_POLICY_SOURCE, SHIPPING_FAQ_SOURCE),
                HelloWorldProfileBootstrap.DEV_NOOP_AUTH,
                Map.of("introQuickReplies", List.of(
                        "track my order ORD-1041",
                        "start a return for ORD-1042",
                        "what's the return window?"))));
    }

    // ---- knowledge sources --------------------------------------------

    private KnowledgeSource registerSource(Ids.KnowledgeSourceId id, String name,
                                           List<SourceLocation.Inline.DocumentBlob> docs) {
        KnowledgeSource source = new KnowledgeSource(
                id, DEFAULT_TENANT, name,
                new SourceLocation.Inline(docs),
                IngestionPolicy.defaults(),
                RetrievalPolicy.defaults(),
                PermissionRequirement.none(),
                SOURCE_VERSION);

        KnowledgeSourceEntity.PK pk = new KnowledgeSourceEntity.PK(
                DEFAULT_TENANT.value(), id.value(), SOURCE_VERSION);
        if (!sourceRepo.existsById(pk)) {
            KnowledgeSourceEntity entity = new KnowledgeSourceEntity(
                    DEFAULT_TENANT.value(), id.value(), SOURCE_VERSION,
                    name, "INLINE",
                    writeJson(Map.of("kind", "INLINE", "docs", "(seeded in code)")),
                    writeJson(source.ingestion()),
                    writeJson(source.retrieval()));
            entity.setAccessRequirementJson("{}");
            sourceRepo.save(entity);
        }

        catalog.registerKnowledgeSource(source);
        return source;
    }

    private void bindSourceToProfile(Ids.KnowledgeSourceId source) {
        // Merge into the catalog: keep what the hello-world / earlier
        // bootstraps wrote in place.
        var existing = new java.util.ArrayList<>(
                catalog.sourcesForProfile(RETAIL_CUSTOMER_PROFILE));
        if (!existing.contains(source)) existing.add(source);
        catalog.bindProfileSources(RETAIL_CUSTOMER_PROFILE, existing);

        ProfileKnowledgeBindingEntity.PK pk = new ProfileKnowledgeBindingEntity.PK(
                DEFAULT_TENANT.value(),
                RETAIL_CUSTOMER_PROFILE.value(),
                RETAIL_CUSTOMER_PROFILE.version(),
                source.value(),
                SOURCE_VERSION);
        if (bindingRepo.existsById(pk)) return;
        bindingRepo.save(new ProfileKnowledgeBindingEntity(
                DEFAULT_TENANT.value(),
                RETAIL_CUSTOMER_PROFILE.value(),
                RETAIL_CUSTOMER_PROFILE.version(),
                source.value(),
                SOURCE_VERSION));
    }

    // ---- inline content -----------------------------------------------

    private static List<SourceLocation.Inline.DocumentBlob> returnPolicyDocs() {
        return List.of(
                new SourceLocation.Inline.DocumentBlob(
                        "return-window",
                        "text/markdown",
                        """
                        # Return window

                        You can start a return within **30 days** of the
                        delivery date. Items must be in original condition
                        with tags attached. Final-sale items (anything in our
                        outlet category) cannot be returned.
                        """),
                new SourceLocation.Inline.DocumentBlob(
                        "refund-methods",
                        "text/markdown",
                        """
                        # Refund methods

                        Refunds are processed back to the original payment
                        method within **5–7 business days** of the warehouse
                        receiving the return. Store credit can be issued
                        instantly if you'd prefer — ask the assistant to
                        choose store credit when starting the return.
                        """),
                new SourceLocation.Inline.DocumentBlob(
                        "damaged-or-wrong",
                        "text/markdown",
                        """
                        # Damaged or wrong item

                        If the item arrived damaged or wrong, you can return
                        it for a full refund including shipping, no matter how
                        long ago it was delivered. Include "damaged" or
                        "wrong item" as your reason when starting the return.
                        """)
        );
    }

    private static List<SourceLocation.Inline.DocumentBlob> shippingFaqDocs() {
        return List.of(
                new SourceLocation.Inline.DocumentBlob(
                        "shipping-options",
                        "text/markdown",
                        """
                        # Shipping options

                        Standard shipping is **3–5 business days** within the
                        continental US and is free on orders over $50.
                        Expedited (2-day) and overnight options are available
                        at checkout. International shipping is available to
                        Canada, the UK, and most of the EU.
                        """),
                new SourceLocation.Inline.DocumentBlob(
                        "lost-or-late",
                        "text/markdown",
                        """
                        # Lost or late packages

                        If your tracking hasn't updated for 7+ days, ask the
                        assistant to track the shipment — we'll flag a
                        carrier-side lookup. Lost packages are replaced at no
                        cost once the carrier confirms loss; we do not require
                        a police report.
                        """),
                new SourceLocation.Inline.DocumentBlob(
                        "international",
                        "text/markdown",
                        """
                        # International shipping

                        International orders typically arrive within
                        **7–14 business days**. Customs and import duties are
                        the recipient's responsibility and may delay delivery.
                        Refused international packages cannot be refunded for
                        outbound shipping cost.
                        """)
        );
    }

    private String writeJson(Object v) {
        try { return json.writeValueAsString(v); }
        catch (JsonProcessingException e) { return "{}"; }
    }
}

package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.knowledge.IngestionPolicy;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeSource;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalPolicy;
import com.yayatechandinnovations.yayaagentic.knowledge.SourceLocation;
import com.yayatechandinnovations.yayaagentic.knowledge.ingest.IngestionOrchestrator;
import com.yayatechandinnovations.yayaagentic.knowledge.tool.SearchKnowledgeToolHandler;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeSourceEntity;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeSourceRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ProfileKnowledgeBindingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ProfileKnowledgeBindingRepository;
import com.yayatechandinnovations.yayaagentic.profile.Capability;
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
 * Seeds a single Inline {@link KnowledgeSource} on the hello-world profile
 * so the playground can demonstrate grounded answers + citations the moment
 * the app starts — without operators having to wire up a real document
 * corpus first. The FAQ content is a deliberately small set of Q&A pairs
 * about Yaya itself, so the LLM has something concrete to ground on without
 * mentioning anything the user might actually need to verify.
 * <p>
 * Also registers the {@code search_knowledge} tool + a "search knowledge"
 * capability that the hello-world profile picks up automatically.
 */
@Component
@Order(50)
public class KnowledgeBootstrap implements ApplicationRunner {

    public static final Ids.KnowledgeSourceId YAYA_FAQ_SOURCE =
            new Ids.KnowledgeSourceId("yaya-faq");
    public static final Ids.CapabilityId SEARCH_KNOWLEDGE_CAP =
            new Ids.CapabilityId("search-knowledge");
    public static final Ids.ToolId SEARCH_KNOWLEDGE_TOOL =
            new Ids.ToolId(SearchKnowledgeToolHandler.TOOL_ID);

    private static final int SOURCE_VERSION = 1;

    private final M0Catalog catalog;
    private final KnowledgeSourceRepository sourceRepo;
    private final ProfileKnowledgeBindingRepository bindingRepo;
    private final IngestionOrchestrator ingestion;
    private final ObjectMapper json;

    public KnowledgeBootstrap(M0Catalog catalog,
                              KnowledgeSourceRepository sourceRepo,
                              ProfileKnowledgeBindingRepository bindingRepo,
                              IngestionOrchestrator ingestion,
                              ObjectMapper json) {
        this.catalog = catalog;
        this.sourceRepo = sourceRepo;
        this.bindingRepo = bindingRepo;
        this.ingestion = ingestion;
        this.json = json;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        KnowledgeSource source = inlineFaqSource();
        ensurePersisted(source);
        registerToolAndCapability();
        registerInCatalog(source);
        attachToHelloWorldProfile();
        // Ingest in the foreground at startup so the demo is ready as soon
        // as the app is reachable. For a real source list this would run
        // off the request thread via Spring @Scheduled in M3.
        ingestion.ingest(source);
    }

    private KnowledgeSource inlineFaqSource() {
        List<SourceLocation.Inline.DocumentBlob> docs = List.of(
                new SourceLocation.Inline.DocumentBlob(
                        "what-is-yaya",
                        "text/markdown",
                        """
                        # What is Yaya?

                        Yaya is a profile-driven agentic chatbot framework. Each
                        Yaya bot is composed from three orthogonal primitives:
                        Personality (cross-profile tone), Profile (role-specific
                        capabilities), and Authentication / Authorization
                        (every tool call is gated by an Authorizer chain).
                        """),
                new SourceLocation.Inline.DocumentBlob(
                        "what-yaya-can-do",
                        "text/markdown",
                        """
                        # What can Yaya do?

                        Yaya bots talk to users, propose tool calls that the
                        engine validates and executes, and ground answers in
                        retrieved knowledge with citations. The same
                        Authorizer chain that gates tools also gates which
                        knowledge sources a principal can read from.
                        """),
                new SourceLocation.Inline.DocumentBlob(
                        "yaya-grounding-policy",
                        "text/markdown",
                        """
                        # Grounding policy

                        When retrieval returns chunks for a question, Yaya will
                        answer only from those chunks. If no chunk supports an
                        answer, Yaya says so rather than guessing. Retrieved
                        content is treated as untrusted data, not instructions.
                        """)
        );

        return new KnowledgeSource(
                YAYA_FAQ_SOURCE,
                HelloWorldProfileBootstrap.DEFAULT_TENANT,
                "Yaya FAQ (demo)",
                new SourceLocation.Inline(docs),
                IngestionPolicy.defaults(),
                RetrievalPolicy.defaults(),
                PermissionRequirement.none(),
                SOURCE_VERSION);
    }

    private void ensurePersisted(KnowledgeSource source) {
        KnowledgeSourceEntity.PK pk = new KnowledgeSourceEntity.PK(
                source.tenant().value(), source.id().value(), source.version());
        if (sourceRepo.existsById(pk)) return;
        KnowledgeSourceEntity entity = new KnowledgeSourceEntity(
                source.tenant().value(), source.id().value(), source.version(),
                source.name(), "INLINE",
                writeJson(Map.of("kind", "INLINE", "docs", "(seeded in code)")),
                writeJson(source.ingestion()),
                writeJson(source.retrieval()));
        entity.setAccessRequirementJson("{}");
        sourceRepo.save(entity);
    }

    private void registerToolAndCapability() {
        catalog.registerTool(new ToolDescriptor(
                SEARCH_KNOWLEDGE_TOOL,
                SearchKnowledgeToolHandler.INPUT_SCHEMA,
                SearchKnowledgeToolHandler.OUTPUT_SCHEMA,
                PermissionRequirement.none(),
                new ToolHandlerRef.Bean("searchKnowledgeTool"),
                ToolPolicy.defaults()));

        catalog.registerCapability(new Capability(
                SEARCH_KNOWLEDGE_CAP,
                "Search knowledge",
                "I can search what I know and cite where the answer came from.",
                "Use search_knowledge for any factual question about Yaya itself "
                        + "or any topic covered by an attached knowledge source. "
                        + "Cite the chunk you grounded on.",
                List.of(SEARCH_KNOWLEDGE_TOOL),
                List.of("what is Yaya?", "what can you do?"),
                PermissionRequirement.none()));
    }

    private void registerInCatalog(KnowledgeSource source) {
        catalog.registerKnowledgeSource(source);
    }

    private void attachToHelloWorldProfile() {
        catalog.bindProfileSources(
                HelloWorldProfileBootstrap.HELLO_WORLD_PROFILE,
                List.of(YAYA_FAQ_SOURCE));

        ProfileKnowledgeBindingEntity.PK pk = new ProfileKnowledgeBindingEntity.PK(
                HelloWorldProfileBootstrap.DEFAULT_TENANT.value(),
                HelloWorldProfileBootstrap.HELLO_WORLD_PROFILE.value(),
                HelloWorldProfileBootstrap.HELLO_WORLD_PROFILE.version(),
                YAYA_FAQ_SOURCE.value(),
                SOURCE_VERSION);
        if (bindingRepo.existsById(pk)) return;
        bindingRepo.save(new ProfileKnowledgeBindingEntity(
                HelloWorldProfileBootstrap.DEFAULT_TENANT.value(),
                HelloWorldProfileBootstrap.HELLO_WORLD_PROFILE.value(),
                HelloWorldProfileBootstrap.HELLO_WORLD_PROFILE.version(),
                YAYA_FAQ_SOURCE.value(),
                SOURCE_VERSION));
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { return "{}"; }
    }
}

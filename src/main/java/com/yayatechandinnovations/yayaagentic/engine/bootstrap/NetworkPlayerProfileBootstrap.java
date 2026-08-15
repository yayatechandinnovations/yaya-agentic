package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Seeds the Corillo network profile ({@code player_network_v1}) —
 * yaya-agentic-network-chat-runtime-design.md §4/§5. Mirrors the
 * {@link HelloWorldProfileBootstrap} pattern: persist tools (kind BEAN) +
 * capabilities + profile, then mirror into {@link M0Catalog}.
 *
 * <p>Seeds only when {@code yaya.agentic.network.seed-tenant} is configured, so a
 * plain dev instance stays clean; a deployment names its tenant (e.g. Root Org).
 * The {@code play}/{@code claim} capabilities are included ONLY when their config
 * flags are on (default off) — so while off, those tools are neither offered to
 * the LLM nor dispatchable (out-of-profile), the two structural non-negotiables.
 */
@Component
@Order(10)
public class NetworkPlayerProfileBootstrap implements ApplicationRunner {

    public static final Ids.ProfileId PLAYER_NETWORK_PROFILE = new Ids.ProfileId("player_network_v1", 1);
    public static final Ids.AuthBindingId NETWORK_AUTH = new Ids.AuthBindingId("network-noop");

    // Capabilities
    public static final Ids.CapabilityId CAP_BANCAS = new Ids.CapabilityId("network_bancas");
    public static final Ids.CapabilityId CAP_READS = new Ids.CapabilityId("network_personal_reads");
    public static final Ids.CapabilityId CAP_PLAY = new Ids.CapabilityId("network_play");
    public static final Ids.CapabilityId CAP_CLAIM = new Ids.CapabilityId("network_claim");

    // Tools (ids == the wire tool names in design §4)
    public static final Ids.ToolId LINKED_BANCAS = new Ids.ToolId("player_linked_bancas");
    public static final Ids.ToolId ONLINE_BANCAS = new Ids.ToolId("directory_online_bancas");
    public static final Ids.ToolId BANCA_CONTEXT_SET = new Ids.ToolId("banca_context_set");
    public static final Ids.ToolId PLAYER_OVERVIEW = new Ids.ToolId("player_overview");
    public static final Ids.ToolId PLAYER_PRIZES = new Ids.ToolId("player_prizes");
    public static final Ids.ToolId PLAYER_RECENT_PLAYS = new Ids.ToolId("player_recent_plays");
    public static final Ids.ToolId PLAY_QUOTE = new Ids.ToolId("play_quote");
    public static final Ids.ToolId PLAY_CONFIRM = new Ids.ToolId("play_confirm");
    public static final Ids.ToolId BANCA_LINK_CONFIRM = new Ids.ToolId("banca_link_confirm");
    public static final Ids.ToolId PRIZE_CLAIM = new Ids.ToolId("prize_claim_network");

    private static final String OBJ = "{\"type\":\"object\"}";
    private static final String NO_ARGS = "{\"type\":\"object\",\"properties\":{}}";

    private static final String SYSTEM_PROMPT = """
            Eres el asistente de la red Corillo. Actúas para el jugador que ha \
            iniciado sesión, en toda la red; las acciones de dinero ocurren solo \
            dentro de una banca explícitamente seleccionada (el contexto de banca). \
            Prefiere las bancas vinculadas del jugador; cuando uses selección \
            rápida, di siempre la banca elegida antes de continuar; una sola frase \
            del jugador cambia de banca; nunca asumas una banca solo por el \
            historial. Nunca ejecutes play_confirm sin una cotización fresca \
            aprobada; nunca discutas ni aceptes datos de pago en el chat; para \
            depósitos, indica los métodos publicados por la banca. Cita los montos \
            exactamente como los devuelven las herramientas (cadenas ya \
            formateadas); no hagas aritmética. Si una consulta de varias bancas no \
            pudo cubrir alguna, dilo por nombre.""";

    private final TenantRepository tenants;
    private final AuthBindingRepository authBindings;
    private final ToolRepository tools;
    private final CapabilityRepository capabilities;
    private final ProfileRegistry profileRegistry;
    private final M0Catalog catalog;
    private final ObjectMapper json;
    private final YayaAgenticProperties props;

    public NetworkPlayerProfileBootstrap(TenantRepository tenants,
                                         AuthBindingRepository authBindings,
                                         ToolRepository tools,
                                         CapabilityRepository capabilities,
                                         ProfileRegistry profileRegistry,
                                         M0Catalog catalog,
                                         ObjectMapper json,
                                         YayaAgenticProperties props) {
        this.tenants = tenants;
        this.authBindings = authBindings;
        this.tools = tools;
        this.capabilities = capabilities;
        this.profileRegistry = profileRegistry;
        this.catalog = catalog;
        this.json = json;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String seedTenant = props.network().seedTenant();
        if (seedTenant == null || seedTenant.isBlank()) return; // opt-in per deployment
        seedInto(new Ids.TenantId(seedTenant.trim()));
    }

    /**
     * Idempotently seed the network profile into {@code tenant}. ALL capabilities
     * (incl. play/claim) are seeded unconditionally; visibility of play/claim is a
     * live, per-tenant runtime toggle enforced by {@code NetworkCapabilityGate}, so
     * flipping S3 needs no re-seed or restart.
     */
    @Transactional
    public void seedInto(Ids.TenantId tenant) {
        ensureTenant(tenant);
        ensureAuthBinding(tenant);

        // Read/select tools + personal reads (S2).
        ensureTool(tenant, LINKED_BANCAS, NO_ARGS, "networkPlayerLinkedBancasTool", readPolicy());
        ensureTool(tenant, ONLINE_BANCAS,
                "{\"type\":\"object\",\"properties\":{\"openNow\":{\"type\":\"boolean\"}}}",
                "networkDirectoryOnlineBancasTool", readPolicy());
        ensureTool(tenant, BANCA_CONTEXT_SET,
                "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}}}",
                "networkBancaContextSetTool", selectPolicy());
        ensureTool(tenant, PLAYER_OVERVIEW, NO_ARGS, "networkPlayerOverviewTool", readPolicy());
        ensureTool(tenant, PLAYER_PRIZES, NO_ARGS, "networkPlayerPrizesTool", readPolicy());
        ensureTool(tenant, PLAYER_RECENT_PLAYS,
                "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"}}}",
                "networkPlayerRecentPlaysTool", readPolicy());

        // Play/claim tools (S3) — seeded always, gated live by NetworkCapabilityGate.
        ensureTool(tenant, PLAY_QUOTE, OBJ, "networkPlayQuoteTool", playQuotePolicy());
        ensureTool(tenant, PLAY_CONFIRM,
                "{\"type\":\"object\",\"properties\":{\"quoteId\":{\"type\":\"string\"}}}",
                "networkPlayConfirmTool", playConfirmPolicy());
        ensureTool(tenant, BANCA_LINK_CONFIRM,
                "{\"type\":\"object\",\"properties\":{\"installCode\":{\"type\":\"string\"}}}",
                "networkBancaLinkConfirmTool", linkConfirmPolicy());
        ensureTool(tenant, PRIZE_CLAIM,
                "{\"type\":\"object\",\"required\":[\"ticketNumber\",\"installCode\"],"
                        + "\"properties\":{\"ticketNumber\":{\"type\":\"string\"},"
                        + "\"installCode\":{\"type\":\"string\"}}}",
                "networkPrizeClaimTool", claimPolicy());

        ensureCapability(tenant, CAP_BANCAS, "Bancas de la red",
                "Encuentra bancas y elige dónde jugar.",
                "Usa player_linked_bancas y directory_online_bancas para listar bancas; "
                        + "usa banca_context_set para fijar la banca elegida antes de jugar.",
                List.of(LINKED_BANCAS.value(), ONLINE_BANCAS.value(), BANCA_CONTEXT_SET.value()));
        ensureCapability(tenant, CAP_READS, "Tu cuenta en la red",
                "Consulta puntos, premios y jugadas recientes por banca.",
                "Usa player_overview, player_prizes y player_recent_plays. Cita los montos "
                        + "verbatim y revela siempre si alguna banca no pudo consultarse.",
                List.of(PLAYER_OVERVIEW.value(), PLAYER_PRIZES.value(), PLAYER_RECENT_PLAYS.value()));
        ensureCapability(tenant, CAP_PLAY, "Jugar en la red",
                "Cotiza y confirma jugadas, y vincúlate a una banca.",
                "Usa play_quote para cotizar y play_confirm para confirmar (requiere una "
                        + "cotización aprobada). banca_link_confirm vincula tras confirmación.",
                List.of(PLAY_QUOTE.value(), PLAY_CONFIRM.value(), BANCA_LINK_CONFIRM.value()));
        ensureCapability(tenant, CAP_CLAIM, "Reclamar premios",
                "Reclama un premio a tu monedero en la banca del premio.",
                "Usa prize_claim_network; requiere confirmación del jugador.",
                List.of(PRIZE_CLAIM.value()));

        ensureProfile(tenant, List.of(CAP_BANCAS, CAP_READS, CAP_PLAY, CAP_CLAIM));
        mirror();
    }

    // ---- policies -------------------------------------------------------

    private static ToolPolicy readPolicy() {
        return new ToolPolicy(Duration.ofSeconds(10), 0, true, false, false, null, false, false);
    }

    private static ToolPolicy selectPolicy() {
        // Sets the context; must NOT be banca-gated or confirmable (self-deadlock).
        return new ToolPolicy(Duration.ofSeconds(10), 0, false, false, false, null, false, false);
    }

    private static ToolPolicy playQuotePolicy() {
        // Requires an ACTIVE banca; not confirmable; writes a pending quote.
        return new ToolPolicy(Duration.ofSeconds(15), 0, false, false, false, null, true, false);
    }

    private static ToolPolicy playConfirmPolicy() {
        // Banca-gated + confirmable + approved-quote-gated.
        return new ToolPolicy(Duration.ofSeconds(15), 0, false, true, false,
                "¿Confirmas esta jugada?", true, true);
    }

    private static ToolPolicy linkConfirmPolicy() {
        // Confirmable; transitions PENDING→ACTIVE, so NOT banca-gated.
        return new ToolPolicy(Duration.ofSeconds(10), 0, false, true, false,
                "¿Confirmas vincularte a esta banca?", false, false);
    }

    private static ToolPolicy claimPolicy() {
        // Auto-derives its banca (not banca-gated), confirmable + approved-frame-gated.
        return new ToolPolicy(Duration.ofSeconds(15), 0, false, true, false,
                "¿Confirmas reclamar este premio?", false, true);
    }

    // ---- persistence + mirror ------------------------------------------

    private void ensureTenant(Ids.TenantId tenant) {
        if (tenants.existsById(tenant.value())) return;
        tenants.save(new TenantEntity(tenant.value(), tenant.value()));
    }

    private void ensureAuthBinding(Ids.TenantId tenant) {
        AuthBindingEntity.PK pk = new AuthBindingEntity.PK(tenant.value(), NETWORK_AUTH.value());
        if (authBindings.existsById(pk)) return;
        authBindings.save(new AuthBindingEntity(tenant.value(), NETWORK_AUTH.value(), "noop"));
    }

    private void ensureTool(Ids.TenantId tenant, Ids.ToolId id, String inputSchema,
                            String beanName, ToolPolicy policy) {
        ToolEntity.PK pk = new ToolEntity.PK(tenant.value(), id.value(), 1);
        if (tools.existsById(pk)) return;
        ToolEntity entity = new ToolEntity(tenant.value(), id.value(), 1, inputSchema, OBJ, "BEAN");
        entity.setHandlerBeanName(beanName);
        entity.setPolicyJson(writeJson(policy));
        entity.setRequiresJson(writeJson(PermissionRequirement.none()));
        tools.save(entity);
    }

    private void ensureCapability(Ids.TenantId tenant, Ids.CapabilityId id, String label,
                                  String description, String guidance, List<String> toolIds) {
        CapabilityEntity.PK pk = new CapabilityEntity.PK(tenant.value(), id.value(), 1);
        if (capabilities.existsById(pk)) {
            return;
        }
        CapabilityEntity entity = new CapabilityEntity(tenant.value(), id.value(), 1, label);
        entity.setDescription(description);
        entity.setLlmGuidance(guidance);
        entity.setToolIdsJson(writeJson(toolIds));
        entity.setFollowUpHintsJson(writeJson(List.of()));
        capabilities.save(entity);
    }

    private void ensureProfile(Ids.TenantId tenant, List<Ids.CapabilityId> caps) {
        if (profileRegistry.find(tenant, PLAYER_NETWORK_PROFILE).isPresent()) return;
        profileRegistry.register(new Profile(
                PLAYER_NETWORK_PROFILE,
                tenant,
                "Corillo",
                "Soy tu asistente de la red Corillo. Puedo mostrarte tus bancas, premios y jugadas.",
                SYSTEM_PROMPT,
                caps,
                List.of(),
                NETWORK_AUTH,
                "es",
                Map.of("introQuickReplies",
                        List.of("¿En cuál banca juego?", "Mis premios", "Mis jugadas recientes"))));
    }

    /** Mirror into the in-memory runtime catalog (engine lookup path). All caps
     *  are registered; NetworkCapabilityGate decides play/claim visibility live. */
    private void mirror() {
        mirrorTool(LINKED_BANCAS, "networkPlayerLinkedBancasTool", NO_ARGS, readPolicy());
        mirrorTool(ONLINE_BANCAS, "networkDirectoryOnlineBancasTool", OBJ, readPolicy());
        mirrorTool(BANCA_CONTEXT_SET, "networkBancaContextSetTool", OBJ, selectPolicy());
        mirrorTool(PLAYER_OVERVIEW, "networkPlayerOverviewTool", NO_ARGS, readPolicy());
        mirrorTool(PLAYER_PRIZES, "networkPlayerPrizesTool", NO_ARGS, readPolicy());
        mirrorTool(PLAYER_RECENT_PLAYS, "networkPlayerRecentPlaysTool", OBJ, readPolicy());
        mirrorTool(PLAY_QUOTE, "networkPlayQuoteTool", OBJ, playQuotePolicy());
        mirrorTool(PLAY_CONFIRM, "networkPlayConfirmTool", OBJ, playConfirmPolicy());
        mirrorTool(BANCA_LINK_CONFIRM, "networkBancaLinkConfirmTool", OBJ, linkConfirmPolicy());
        mirrorTool(PRIZE_CLAIM, "networkPrizeClaimTool", OBJ, claimPolicy());
        catalog.registerCapability(new Capability(CAP_BANCAS, "Bancas de la red",
                "Encuentra bancas y elige dónde jugar.", "",
                List.of(LINKED_BANCAS, ONLINE_BANCAS, BANCA_CONTEXT_SET), List.of(),
                PermissionRequirement.none()));
        catalog.registerCapability(new Capability(CAP_READS, "Tu cuenta en la red",
                "Consulta puntos, premios y jugadas recientes por banca.", "",
                List.of(PLAYER_OVERVIEW, PLAYER_PRIZES, PLAYER_RECENT_PLAYS), List.of(),
                PermissionRequirement.none()));
        catalog.registerCapability(new Capability(CAP_PLAY, "Jugar en la red",
                "Cotiza y confirma jugadas, y vincúlate a una banca.", "",
                List.of(PLAY_QUOTE, PLAY_CONFIRM, BANCA_LINK_CONFIRM), List.of(),
                PermissionRequirement.none()));
        catalog.registerCapability(new Capability(CAP_CLAIM, "Reclamar premios",
                "Reclama un premio a tu monedero en la banca del premio.", "",
                List.of(PRIZE_CLAIM), List.of(), PermissionRequirement.none()));
    }

    private void mirrorTool(Ids.ToolId id, String beanName, String inputSchema, ToolPolicy policy) {
        catalog.registerTool(new ToolDescriptor(
                id, inputSchema, OBJ, PermissionRequirement.none(),
                new ToolHandlerRef.Bean(beanName), policy));
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to encode JSON", ex);
        }
    }
}

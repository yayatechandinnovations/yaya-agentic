package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.persistence.CapabilityEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ToolEntity;
import com.yayatechandinnovations.yayaagentic.profile.Capability;
import com.yayatechandinnovations.yayaagentic.profile.MenuBlock;
import com.yayatechandinnovations.yayaagentic.tool.EntityBinding;
import com.yayatechandinnovations.yayaagentic.tool.EntityBindingSpec;
import com.yayatechandinnovations.yayaagentic.tool.EntityCapture;
import com.yayatechandinnovations.yayaagentic.tool.EntityCaptureSpec;
import com.yayatechandinnovations.yayaagentic.tool.HttpToolSpec;
import com.yayatechandinnovations.yayaagentic.tool.SuggestionRule;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Single place that knows how to turn {@link ToolEntity} / {@link CapabilityEntity}
 * rows from Postgres into the runtime records the engine consults via
 * {@link M0Catalog}. Used at startup by {@code RuntimeCatalogLoader} and
 * on every successful admin POST by {@code AdminController}.
 */
@Component
public class CatalogMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<PermissionRequirement> PERMISSION_REQUIREMENT = new TypeReference<>() {};
    private static final TypeReference<HttpToolSpec> HTTP_TOOL_SPEC = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> STRING_MAP = new TypeReference<>() {};
    private static final TypeReference<List<EntityCaptureSpec>> CAPTURE_LIST = new TypeReference<>() {};
    private static final TypeReference<List<EntityBindingSpec>> BINDING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<SuggestionRule>> SUGGESTION_LIST = new TypeReference<>() {};
    private static final TypeReference<MenuBlock> MENU_BLOCK = new TypeReference<>() {};

    /** The refusal code of a {@code policy.headless} the rule does not allow. */
    public static final String HEADLESS_NOT_ALLOWED = "headless_not_allowed";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CatalogMapper.class);

    private final ObjectMapper json;

    public CatalogMapper(ObjectMapper json) {
        this.json = json;
    }

    public ToolDescriptor toDescriptor(ToolEntity e) {
        Ids.ToolId id = new Ids.ToolId(e.getId());
        PermissionRequirement requires = readJson(e.getRequiresJson(), PERMISSION_REQUIREMENT, PermissionRequirement.none());

        ToolHandlerRef handler = "BEAN".equals(e.getHandlerKind())
                ? new ToolHandlerRef.Bean(e.getHandlerBeanName())
                : new ToolHandlerRef.Http(readJson(e.getHandlerHttpSpecJson(), HTTP_TOOL_SPEC, null));

        ToolPolicy policy = readPolicy(e.getPolicyJson());
        if (policy.headless()) {
            // A stored row that carries the flag against the rule (written
            // before the rule, or straight into the table) is registered
            // without it: the tool keeps working in conversations and no
            // headless run is offered it.
            String problem = headlessProblem(handler, policy);
            if (problem != null) {
                LOG.warn("tool '{}' of tenant '{}' is flagged headless but {}; registered without the flag ({})",
                        e.getId(), e.getTenantId(), problem, HEADLESS_NOT_ALLOWED);
                policy = withoutHeadless(policy);
            }
        }

        List<EntityCapture> captures = readJson(e.getCapturesJson(), CAPTURE_LIST, List.of())
                .stream().map(EntityCaptureSpec::toRuntime).toList();
        List<EntityBinding> bindings = readJson(e.getBindingsJson(), BINDING_LIST, List.of())
                .stream().map(EntityBindingSpec::toRuntime).toList();

        return new ToolDescriptor(id,
                e.getInputSchemaJson(),
                e.getOutputSchemaJson(),
                requires == null ? PermissionRequirement.none() : requires,
                handler,
                policy,
                captures,
                bindings,
                readJson(e.getSuggestionsJson(), SUGGESTION_LIST, List.of()));
    }

    public Capability toCapability(CapabilityEntity e) {
        return new Capability(
                new Ids.CapabilityId(e.getId()),
                e.getLabel(),
                e.getDescription(),
                e.getLlmGuidance(),
                readJson(e.getToolIdsJson(), STRING_LIST, List.of()).stream()
                        .map(Ids.ToolId::new)
                        .toList(),
                readJson(e.getFollowUpHintsJson(), STRING_LIST, List.of()),
                readJson(e.getRequiresJson(), PERMISSION_REQUIREMENT, PermissionRequirement.none()),
                readJson(e.getMenuJson(), MENU_BLOCK, null));
    }

    /**
     * Why {@code policy.headless} may not be set on a tool with this handler
     * and policy, or null when it may. A headless run answers with nobody
     * present, so it is offered reads only: an HTTP {@code GET} that never
     * waits for a person (docs/guides/admin-panel-sessions.md §17).
     */
    public static String headlessProblem(ToolHandlerRef handler, ToolPolicy policy) {
        if (policy == null || !policy.headless()) return null;
        if (!(handler instanceof ToolHandlerRef.Http http) || http.spec() == null
                || http.spec().method() != HttpToolSpec.HttpMethod.GET) {
            return "its handler is not an HTTP GET";
        }
        if (policy.confirmable()) return "it is confirmable";
        if (policy.secretInput()) return "it may ask for a secret (secretInput)";
        if (policy.clientInvocable()) return "it is clientInvocable";
        if (policy.plannable()) return "it is plannable";
        if (policy.preconfirmableByAction()) return "it is preconfirmableByAction";
        return null;
    }

    /**
     * The check the catalogue API runs before it stores a tool: a row whose
     * policy asks for {@code headless} against the rule is refused.
     *
     * @throws HeadlessNotAllowedException code {@link #HEADLESS_NOT_ALLOWED}
     */
    public void requireHeadlessAllowed(ToolEntity e) {
        ToolHandlerRef handler = "BEAN".equals(e.getHandlerKind())
                ? new ToolHandlerRef.Bean(e.getHandlerBeanName())
                : new ToolHandlerRef.Http(readJson(e.getHandlerHttpSpecJson(), HTTP_TOOL_SPEC, null));
        String problem = headlessProblem(handler, readPolicy(e.getPolicyJson()));
        if (problem != null) throw new HeadlessNotAllowedException(e.getId(), problem);
    }

    /** {@code policy.headless} was asked for a tool that may not carry it. */
    public static final class HeadlessNotAllowedException extends RuntimeException {
        public HeadlessNotAllowedException(String toolId, String problem) {
            super("tool '" + toolId + "' cannot be headless: " + problem);
        }

        public String code() { return HEADLESS_NOT_ALLOWED; }
    }

    // ---- internals ------------------------------------------------------

    private static ToolPolicy withoutHeadless(ToolPolicy p) {
        return new ToolPolicy(p.timeout(), p.maxRetries(), p.idempotent(), p.confirmable(), p.clientInvocable(),
                p.confirmPrompt(), p.requiresBancaContext(), p.requiresApprovedQuote(),
                p.preconfirmableByAction(), p.secretInput(), p.plannable(), false);
    }

    private ToolPolicy readPolicy(String policyJson) {
        if (policyJson == null || policyJson.isBlank() || "{}".equals(policyJson.trim())) {
            return ToolPolicy.defaults();
        }
        Map<String, Object> raw = readJson(policyJson, STRING_MAP, Map.of());
        Duration timeout = parseDuration(raw.get("timeout"), Duration.ofSeconds(10));
        int maxRetries = toInt(raw.get("maxRetries"), 0);
        boolean idempotent = toBool(raw.get("idempotent"), true);
        boolean confirmable = toBool(raw.get("confirmable"), false);
        boolean clientInvocable = toBool(raw.get("clientInvocable"), false);
        Object prompt = raw.get("confirmPrompt");
        String confirmPrompt = prompt == null || String.valueOf(prompt).isBlank()
                ? null : String.valueOf(prompt).trim();
        boolean preconfirmableByAction = toBool(raw.get("preconfirmableByAction"), false);
        boolean secretInput = toBool(raw.get("secretInput"), false);
        boolean plannable = toBool(raw.get("plannable"), false);
        boolean headless = toBool(raw.get("headless"), false);
        return new ToolPolicy(timeout, maxRetries, idempotent, confirmable, clientInvocable,
                confirmPrompt, false, false, preconfirmableByAction, secretInput, plannable, headless);
    }

    private <T> T readJson(String raw, TypeReference<T> type, T fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            T parsed = json.readValue(raw, type);
            return parsed == null ? fallback : parsed;
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }

    private static Duration parseDuration(Object value, Duration fallback) {
        if (value == null) return fallback;
        try {
            return Duration.parse(Objects.toString(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }

    private static boolean toBool(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }
}

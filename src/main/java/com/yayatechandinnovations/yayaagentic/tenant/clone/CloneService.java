package com.yayatechandinnovations.yayaagentic.tenant.clone;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.persistence.*;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import com.yayatechandinnovations.yayaagentic.tenant.clone.CloneRequest.ConflictPolicy;
import com.yayatechandinnovations.yayaagentic.tenant.clone.CloneRequest.KnowledgeLocationStrategy;
import com.yayatechandinnovations.yayaagentic.tenant.clone.CloneRequest.PersonalityPolicy;
import com.yayatechandinnovations.yayaagentic.tenant.clone.ClonePlan.KnowledgeAction;
import com.yayatechandinnovations.yayaagentic.tenant.clone.ClonePlan.PersonalityAction;
import com.yayatechandinnovations.yayaagentic.tenant.clone.ClonePlan.ResourceAction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Cross-tenant profile clone per docs/design/tenant-registry-design.md §7.
 *
 * <p>The clone walks the profile's transitive dependency closure (capabilities,
 * tools, knowledge sources, auth bindings, recording strategies, personality
 * fragments), builds a plan, and applies it atomically. Path-only HTTP tools
 * (§6) make the clone a deterministic id-rewrite — no URL editing needed.</p>
 *
 * <p>{@code dryRun=true} writes only to {@code tenant_clone_jobs} with
 * status=DRY_RUN. {@code dryRun=false} applies the plan inside a single
 * transaction; failure rolls back every resource write.</p>
 */
@Service
public class CloneService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    private final TenantGuard tenantGuard;
    private final ProfileRepository profiles;
    private final CapabilityRepository capabilities;
    private final ToolRepository tools;
    private final KnowledgeSourceRepository knowledgeSources;
    private final ProfileKnowledgeBindingRepository knowledgeBindings;
    private final AuthBindingRepository authBindings;
    private final RecordingStrategyRepository recordingStrategies;
    private final PersonalityFragmentRepository personality;
    private final TenantCloneJobRepository cloneJobs;
    private final ObjectMapper json;

    public CloneService(TenantGuard tenantGuard,
                        ProfileRepository profiles,
                        CapabilityRepository capabilities,
                        ToolRepository tools,
                        KnowledgeSourceRepository knowledgeSources,
                        ProfileKnowledgeBindingRepository knowledgeBindings,
                        AuthBindingRepository authBindings,
                        RecordingStrategyRepository recordingStrategies,
                        PersonalityFragmentRepository personality,
                        TenantCloneJobRepository cloneJobs,
                        ObjectMapper json) {
        this.tenantGuard = tenantGuard;
        this.profiles = profiles;
        this.capabilities = capabilities;
        this.tools = tools;
        this.knowledgeSources = knowledgeSources;
        this.knowledgeBindings = knowledgeBindings;
        this.authBindings = authBindings;
        this.recordingStrategies = recordingStrategies;
        this.personality = personality;
        this.cloneJobs = cloneJobs;
        this.json = json;
    }

    @Transactional
    public CloneResult cloneProfile(String sourceTenant, String profileId, int profileVersion,
                                    CloneRequest rawReq, String operator) {
        CloneRequest req = rawReq.withDefaults();
        tenantGuard.requireExists(sourceTenant);                     // source may be SUSPENDED — reads OK
        tenantGuard.requireWritable(req.destinationTenant());        // destination must be writable

        if (sourceTenant.equals(req.destinationTenant())) {
            throw AdminApiException.badRequest("clone_same_tenant",
                    "source and destination tenant are the same — use the regular versioning flow");
        }

        ProfileEntity sourceProfile = profiles.findById(
                new ProfileEntity.PK(sourceTenant, profileId, profileVersion))
                .orElseThrow(() -> AdminApiException.notFound("unknown_profile",
                        "no such profile: " + sourceTenant + "/" + profileId + "@v" + profileVersion));

        String destProfileId = req.destinationProfileId() == null || req.destinationProfileId().isBlank()
                ? sourceProfile.getId()
                : req.destinationProfileId();

        TenantCloneJobEntity job = new TenantCloneJobEntity(
                UUID.randomUUID(), sourceTenant, req.destinationTenant(),
                sourceProfile.getId(), sourceProfile.getVersion(),
                TenantCloneJobEntity.Status.DRY_RUN);
        job.setDestinationProfileId(destProfileId);

        ClonePlan plan;
        try {
            plan = buildPlan(sourceTenant, sourceProfile, destProfileId, req);
        } catch (AdminApiException e) {
            job.setStatus(TenantCloneJobEntity.Status.FAILED);
            job.setErrorJson(writeJson(Map.of("code", e.code(), "message", String.valueOf(e.getReason()))));
            cloneJobs.save(job);
            throw e;
        }
        job.setPlanJson(writeJson(plan));

        if (req.dryRun()) {
            cloneJobs.save(job);
            return new CloneResult(job.getId(), job.getStatus(), plan, null, null);
        }

        try {
            applyPlan(sourceTenant, sourceProfile, req, plan);
        } catch (AdminApiException e) {
            job.setStatus(TenantCloneJobEntity.Status.FAILED);
            job.setErrorJson(writeJson(Map.of("code", e.code(), "message", String.valueOf(e.getReason()))));
            cloneJobs.save(job);
            throw e;
        }
        job.setStatus(TenantCloneJobEntity.Status.APPLIED);
        job.setAppliedAt(OffsetDateTime.now());
        job.setAppliedBy(operator);
        cloneJobs.save(job);

        return new CloneResult(job.getId(), job.getStatus(), plan, null, null);
    }

    // -----------------------------------------------------------------
    // Plan
    // -----------------------------------------------------------------

    private ClonePlan buildPlan(String src, ProfileEntity sourceProfile,
                                String destProfileId, CloneRequest req) {
        String dst = req.destinationTenant();
        List<String> warnings = new ArrayList<>();

        // -- Profile action
        Integer destProfileVersion = nextVersion(profiles.findByTenantIdAndIdOrderByVersionDesc(dst, destProfileId)
                .stream().mapToInt(ProfileEntity::getVersion));
        ResourceAction profileAction = resolveAction(
                destProfileId,
                sourceProfile.getVersion(),
                destProfileVersion,
                profiles.findByTenantIdAndIdOrderByVersionDesc(dst, destProfileId).isEmpty(),
                req.conflictPolicy(),
                "profile");

        // -- Capabilities + tools
        List<String> capabilityIds = readStringList(sourceProfile.getCapabilitiesJson());
        List<ResourceAction> capabilityActions = new ArrayList<>();
        Set<String> requiredToolIds = new LinkedHashSet<>();
        for (String capId : capabilityIds) {
            CapabilityEntity latestCap = latestCapability(src, capId);
            if (latestCap == null) {
                throw AdminApiException.badRequest("unknown_capability",
                        "source profile references missing capability: " + capId);
            }
            requiredToolIds.addAll(readStringList(latestCap.getToolIdsJson()));
            capabilityActions.add(resolveAction(
                    capId,
                    latestCap.getVersion(),
                    nextVersion(capabilities.findByTenantIdAndIdOrderByVersionDesc(dst, capId)
                            .stream().mapToInt(CapabilityEntity::getVersion)),
                    capabilities.findByTenantIdAndIdOrderByVersionDesc(dst, capId).isEmpty(),
                    req.conflictPolicy(),
                    "capability"));
        }

        List<ResourceAction> toolActions = new ArrayList<>();
        for (String toolId : requiredToolIds) {
            ToolEntity latestTool = latestTool(src, toolId);
            if (latestTool == null) {
                throw AdminApiException.badRequest("unknown_tool",
                        "capability references missing tool: " + toolId);
            }
            toolActions.add(resolveAction(
                    toolId,
                    latestTool.getVersion(),
                    nextVersion(tools.findByTenantIdAndIdOrderByVersionDesc(dst, toolId)
                            .stream().mapToInt(ToolEntity::getVersion)),
                    tools.findByTenantIdAndIdOrderByVersionDesc(dst, toolId).isEmpty(),
                    req.conflictPolicy(),
                    "tool"));
        }

        // -- Knowledge sources (via profile_knowledge_bindings)
        List<KnowledgeAction> knowledgeActions = new ArrayList<>();
        for (ProfileKnowledgeBindingEntity b : knowledgeBindings.findByTenantIdAndProfileIdAndProfileVersion(
                src, sourceProfile.getId(), sourceProfile.getVersion())) {
            KnowledgeSourceEntity src_ks = knowledgeSources.findById(
                    new KnowledgeSourceEntity.PK(src, b.getSourceId(), b.getSourceVersion()))
                    .orElseThrow(() -> AdminApiException.badRequest("unknown_knowledge_source",
                            "profile binding references missing source: " + b.getSourceId()));
            knowledgeActions.add(planKnowledgeSource(src, dst, src_ks, req, warnings));
        }

        // -- Auth binding
        List<ResourceAction> authBindingActions = new ArrayList<>();
        String authBindingId = sourceProfile.getAuthBindingId();
        if (authBindingId != null && !authBindingId.isBlank()) {
            boolean existsAtDest = authBindings.existsById(new AuthBindingEntity.PK(dst, authBindingId));
            authBindingActions.add(new ResourceAction(
                    authBindingId,
                    existsAtDest ? "REUSE_EXISTING" : "CREATE",
                    null, null,
                    existsAtDest
                            ? List.of("destination tenant already has '" + authBindingId
                                    + "'; profile will reference it as-is")
                            : List.of("auth binding cloned by reference only — secret material is "
                                    + "not copied; provision tenant_secrets at destination before use")));
        }

        // -- Recording strategies (profile-scoped)
        List<ResourceAction> recordingActions = planRecordingStrategies(
                src, dst, sourceProfile, destProfileId, req, warnings);

        // -- Personality (per locale)
        List<PersonalityAction> personalityActions = planPersonality(src, dst, req, warnings);

        return new ClonePlan(src, dst, destProfileId,
                profileAction, capabilityActions, toolActions, knowledgeActions,
                authBindingActions, recordingActions, personalityActions, warnings);
    }

    private ResourceAction resolveAction(String id, int sourceVersion, int destNextVersion,
                                         boolean noExistingAtDest,
                                         ConflictPolicy policy,
                                         String resourceKind) {
        if (noExistingAtDest) {
            return new ResourceAction(id, "CREATE_NEW_VERSION", sourceVersion, destNextVersion, List.of());
        }
        return switch (policy) {
            case FAIL -> throw AdminApiException.conflict("destination_resource_exists",
                    resourceKind + " '" + id + "' already exists at the destination; "
                            + "use conflictPolicy=SKIP or NEW_VERSION");
            case SKIP -> new ResourceAction(id, "REUSE_EXISTING", null, destNextVersion - 1, List.of());
            case NEW_VERSION -> new ResourceAction(id, "CREATE_NEW_VERSION", sourceVersion, destNextVersion, List.of());
        };
    }

    private KnowledgeAction planKnowledgeSource(String src, String dst,
                                                KnowledgeSourceEntity row, CloneRequest req,
                                                List<String> warnings) {
        String id = row.getId();
        boolean existsAtDest = !knowledgeSources.findByTenantId(dst).stream()
                .filter(e -> id.equals(e.getId()))
                .toList().isEmpty();
        int nextVersion = nextVersion(knowledgeSources.findByTenantId(dst).stream()
                .filter(e -> id.equals(e.getId()))
                .mapToInt(KnowledgeSourceEntity::getVersion));

        String action;
        if (!existsAtDest) {
            action = "CREATE_NEW_VERSION";
        } else {
            switch (req.conflictPolicy()) {
                case FAIL -> throw AdminApiException.conflict("destination_resource_exists",
                        "knowledge source '" + id + "' already exists at the destination");
                case SKIP -> { action = "REUSE_EXISTING"; }
                case NEW_VERSION -> { action = "CREATE_NEW_VERSION"; }
                default -> action = "CREATE_NEW_VERSION";
            }
        }

        Map<String, Object> originalLocation = readMap(row.getLocationJson());
        Map<String, Object> resolvedLocation = new LinkedHashMap<>(originalLocation);
        List<String> notes = new ArrayList<>();
        switch (req.knowledgeLocationStrategy()) {
            case RETAIN -> {
                if (locationReferencesTenant(originalLocation, src)) {
                    notes.add("LOCATION_REFERENCES_SOURCE_TENANT — operator must edit before ingestion");
                    warnings.add("knowledgeSource " + id
                            + " retains a location string scoped to tenant " + src
                            + " — re-ingest after editing");
                }
            }
            case TEMPLATE -> {
                int substituted = templateSubstitute(resolvedLocation, src, dst);
                if (substituted > 0) {
                    notes.add("LOCATION_TEMPLATED — substituted '" + src + "' → '" + dst + "' in "
                            + substituted + " field(s)");
                }
            }
            case OMIT -> {
                resolvedLocation.clear();
                notes.add("LOCATION_OMITTED — operator must populate before re-ingestion");
                warnings.add("knowledgeSource " + id
                        + " was cloned without a location; provide one before re-ingestion");
            }
        }
        notes.add("destination must re-ingest under its own egress / API keys");

        return new KnowledgeAction(
                id, action, row.getVersion(), nextVersion,
                row.getLocationKind(), resolvedLocation, notes);
    }

    private List<ResourceAction> planRecordingStrategies(String src, String dst,
                                                         ProfileEntity sourceProfile,
                                                         String destProfileId,
                                                         CloneRequest req,
                                                         List<String> warnings) {
        List<ResourceAction> out = new ArrayList<>();
        List<RecordingStrategyEntity> srcRows = recordingStrategies
                .findByTenantIdAndScopeKindAndScopeIdOrderByVersionDesc(src, "PROFILE", sourceProfile.getId());
        if (srcRows.isEmpty()) return out;
        RecordingStrategyEntity latest = srcRows.get(0);
        boolean existsAtDest = !recordingStrategies
                .findByTenantIdAndScopeKindAndScopeIdOrderByVersionDesc(dst, "PROFILE", destProfileId)
                .isEmpty();
        int nextVersion = recordingStrategies
                .findByTenantIdAndScopeKindAndScopeIdOrderByVersionDesc(dst, "PROFILE", destProfileId)
                .stream().mapToInt(RecordingStrategyEntity::getVersion).max().orElse(0) + 1;

        String action;
        if (!existsAtDest) action = "CREATE";
        else action = switch (req.conflictPolicy()) {
            case FAIL -> throw AdminApiException.conflict("destination_resource_exists",
                    "recording strategy for profile '" + destProfileId
                            + "' already exists at the destination");
            case SKIP -> "REUSE_EXISTING";
            case NEW_VERSION -> "CREATE_NEW_VERSION";
        };

        List<String> notes = new ArrayList<>();
        Map<String, Object> strategy = readMap(latest.getStrategyJson());
        if (templateSubstitute(strategy, src, dst) > 0) {
            notes.add("strategyJson referenced tenant '" + src + "' — substituted to '" + dst + "'");
        }
        out.add(new ResourceAction(destProfileId, action, latest.getVersion(), nextVersion, notes));
        return out;
    }

    private List<PersonalityAction> planPersonality(String src, String dst, CloneRequest req,
                                                    List<String> warnings) {
        List<PersonalityAction> out = new ArrayList<>();
        List<PersonalityFragmentEntity> srcFragments = personality.findByTenantId(src);
        Set<String> srcLocales = new LinkedHashSet<>();
        for (PersonalityFragmentEntity f : srcFragments) srcLocales.add(f.getLocale());

        if (req.personalityPolicy() == PersonalityPolicy.NEVER) {
            if (personality.findByTenantId(dst).isEmpty()) {
                throw AdminApiException.badRequest("destination_missing_personality",
                        "personalityPolicy=NEVER but destination tenant has no personality fragments");
            }
            out.add(new PersonalityAction(null, "SKIP", null, null,
                    List.of("policy=NEVER — destination keeps its existing voice")));
            return out;
        }

        for (String locale : srcLocales) {
            PersonalityFragmentEntity srcLatest = personality.findLatestForLocale(src, locale).orElse(null);
            if (srcLatest == null) continue;
            Optional<PersonalityFragmentEntity> destLatest = personality.findLatestForLocale(dst, locale);
            int destNextVersion = destLatest.map(f -> f.getVersion() + 1).orElse(1);

            String action;
            if (req.personalityPolicy() == PersonalityPolicy.ALWAYS) {
                action = "CREATE";
            } else { // AUTO
                action = destLatest.isPresent() ? "REUSE_EXISTING" : "CREATE";
            }
            out.add(new PersonalityAction(locale, action, srcLatest.getVersion(),
                    "REUSE_EXISTING".equals(action) ? destLatest.get().getVersion() : destNextVersion,
                    "REUSE_EXISTING".equals(action)
                            ? List.of("destination already has a fragment for locale '" + locale
                                    + "'; not overwriting")
                            : List.of()));
        }
        return out;
    }

    // -----------------------------------------------------------------
    // Apply
    // -----------------------------------------------------------------

    private void applyPlan(String src, ProfileEntity sourceProfile, CloneRequest req, ClonePlan plan) {
        String dst = req.destinationTenant();

        Map<String, Integer> writtenCapabilityVersions = new HashMap<>();
        Map<String, Integer> writtenToolVersions = new HashMap<>();
        Map<String, Integer> writtenKnowledgeVersions = new HashMap<>();

        // Tools first (capabilities reference them)
        for (ResourceAction a : plan.tools()) {
            if ("REUSE_EXISTING".equals(a.action())) {
                writtenToolVersions.put(a.id(), a.toVersion());
                continue;
            }
            ToolEntity latest = latestTool(src, a.id());
            ToolEntity cloned = new ToolEntity(dst, a.id(), a.toVersion(),
                    latest.getInputSchemaJson(), latest.getOutputSchemaJson(), latest.getHandlerKind());
            cloned.setRequiresJson(latest.getRequiresJson());
            cloned.setPolicyJson(latest.getPolicyJson());
            cloned.setHandlerBeanName(latest.getHandlerBeanName());
            // Path-only invariant: urlTemplate is a path; no rewrite needed.
            cloned.setHandlerHttpSpecJson(latest.getHandlerHttpSpecJson());
            tools.save(cloned);
            writtenToolVersions.put(a.id(), a.toVersion());
        }

        for (ResourceAction a : plan.capabilities()) {
            if ("REUSE_EXISTING".equals(a.action())) {
                writtenCapabilityVersions.put(a.id(), a.toVersion());
                continue;
            }
            CapabilityEntity latest = latestCapability(src, a.id());
            CapabilityEntity cloned = new CapabilityEntity(dst, a.id(), a.toVersion(), latest.getLabel());
            cloned.setDescription(latest.getDescription());
            cloned.setLlmGuidance(latest.getLlmGuidance());
            cloned.setToolIdsJson(latest.getToolIdsJson());
            cloned.setRequiresJson(latest.getRequiresJson());
            cloned.setFollowUpHintsJson(latest.getFollowUpHintsJson());
            capabilities.save(cloned);
            writtenCapabilityVersions.put(a.id(), a.toVersion());
        }

        for (KnowledgeAction a : plan.knowledgeSources()) {
            if ("REUSE_EXISTING".equals(a.action())) {
                writtenKnowledgeVersions.put(a.id(), a.toVersion());
                continue;
            }
            KnowledgeSourceEntity latest = knowledgeSources.findByTenantId(src).stream()
                    .filter(e -> a.id().equals(e.getId()))
                    .max(Comparator.comparingInt(KnowledgeSourceEntity::getVersion))
                    .orElseThrow();
            KnowledgeSourceEntity cloned = new KnowledgeSourceEntity(
                    dst, a.id(), a.toVersion(), latest.getName(), a.locationKind(),
                    writeJson(a.location()), latest.getIngestionPolicyJson(),
                    latest.getRetrievalPolicyJson());
            cloned.setAccessRequirementJson(latest.getAccessRequirementJson());
            cloned.setStatus("UNINDEXED");                           // re-ingestion required
            knowledgeSources.save(cloned);
            writtenKnowledgeVersions.put(a.id(), a.toVersion());
        }

        for (ResourceAction a : plan.authBindings()) {
            if ("REUSE_EXISTING".equals(a.action())) continue;
            AuthBindingEntity src_ab = authBindings.findById(new AuthBindingEntity.PK(src, a.id()))
                    .orElseThrow();
            AuthBindingEntity cloned = new AuthBindingEntity(dst, a.id(), src_ab.getAuthenticatorRef());
            cloned.setAuthorizerChainJson(src_ab.getAuthorizerChainJson());
            authBindings.save(cloned);
        }

        // Profile last — its capabilities_json + auth_binding_id reference the rows above.
        ProfileEntity destProfile = new ProfileEntity(dst, plan.destinationProfileId(),
                plan.profile().toVersion(),
                sourceProfile.getDisplayName(),
                sourceProfile.getIntro(),
                sourceProfile.getSystemPrompt());
        destProfile.setCapabilitiesJson(sourceProfile.getCapabilitiesJson());
        destProfile.setMetadataJson(sourceProfile.getMetadataJson());
        destProfile.setAuthBindingId(sourceProfile.getAuthBindingId());
        destProfile.setLanguage(sourceProfile.getLanguage());
        if (!"REUSE_EXISTING".equals(plan.profile().action())) {
            profiles.save(destProfile);

            // Re-bind knowledge sources to the new profile version.
            for (KnowledgeAction ka : plan.knowledgeSources()) {
                int sourceVersion = writtenKnowledgeVersions.getOrDefault(ka.id(), ka.toVersion());
                knowledgeBindings.save(new ProfileKnowledgeBindingEntity(
                        dst, plan.destinationProfileId(), plan.profile().toVersion(),
                        ka.id(), sourceVersion));
            }
        }

        for (ResourceAction a : plan.recordingStrategies()) {
            if ("REUSE_EXISTING".equals(a.action())) continue;
            RecordingStrategyEntity src_rs = recordingStrategies
                    .findByTenantIdAndScopeKindAndScopeIdOrderByVersionDesc(src, "PROFILE", sourceProfile.getId())
                    .get(0);
            // strategyJson is templated in the plan; re-encode from latest +
            // substitution per the same rules.
            Map<String, Object> strategy = readMap(src_rs.getStrategyJson());
            templateSubstitute(strategy, src, dst);
            recordingStrategies.save(new RecordingStrategyEntity(
                    dst, "PROFILE", plan.destinationProfileId(),
                    writeJson(strategy), a.toVersion()));
        }

        for (PersonalityAction a : plan.personality()) {
            if (a.locale() == null) continue;                        // SKIP marker
            if ("REUSE_EXISTING".equals(a.action())) continue;
            PersonalityFragmentEntity srcLatest = personality.findLatestForLocale(src, a.locale())
                    .orElseThrow();
            PersonalityFragmentEntity cloned = new PersonalityFragmentEntity(
                    dst, a.locale(), srcLatest.getVoiceTone(), a.toVersion());
            cloned.setRulesJson(srcLatest.getRulesJson());
            cloned.setRefusalsJson(srcLatest.getRefusalsJson());
            personality.save(cloned);
        }
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private CapabilityEntity latestCapability(String tenantId, String id) {
        return capabilities.findByTenantIdAndIdOrderByVersionDesc(tenantId, id)
                .stream().findFirst().orElse(null);
    }

    private ToolEntity latestTool(String tenantId, String id) {
        return tools.findByTenantIdAndIdOrderByVersionDesc(tenantId, id)
                .stream().findFirst().orElse(null);
    }

    private int nextVersion(java.util.stream.IntStream existing) {
        return existing.max().orElse(0) + 1;
    }

    private boolean locationReferencesTenant(Map<String, Object> location, String tenantId) {
        for (Object v : location.values()) {
            if (v instanceof String s && s.contains(tenantId)) return true;
        }
        return false;
    }

    /** In-place substring substitute of {@code src} → {@code dst} inside any
     *  string value. Returns the count of substituted fields. */
    private int templateSubstitute(Map<String, Object> map, String src, String dst) {
        int count = 0;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s && s.contains(src)) {
                e.setValue(s.replace(src, dst));
                count++;
            }
        }
        return count;
    }

    private List<String> readStringList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try { return json.readValue(raw, STRING_LIST); }
        catch (Exception e) { return List.of(); }
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
        try { return new LinkedHashMap<>(json.readValue(raw, MAP_REF)); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private String writeJson(Object v) {
        try { return json.writeValueAsString(v); }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("clone: failed to encode JSON", e);
        }
    }
}

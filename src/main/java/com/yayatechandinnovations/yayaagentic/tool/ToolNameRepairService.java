package com.yayatechandinnovations.yayaagentic.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.persistence.CapabilityEntity;
import com.yayatechandinnovations.yayaagentic.persistence.CapabilityRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ToolEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ToolRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renames tool ids that don't satisfy {@link ToolIdPattern} (the Anthropic
 * constraint) AND cascades the rename to every capability that referenced
 * the old id. Historical conversation turns and authz audit rows are NOT
 * rewritten — they record what actually happened and renaming them would
 * destroy provenance.
 *
 * <p>Idempotent: if every tool id is already valid, the report is empty.
 * Transactional: tool rename + capability cascade succeed or fail together.
 * Fail-loudly on collisions: if two pre-existing ids would sanitise to the
 * same target, none are renamed; the operator gets the full conflict list
 * and decides which one to rename to something else manually.
 */
@Service
public class ToolNameRepairService {

    private final ToolRepository tools;
    private final CapabilityRepository capabilities;
    private final ObjectMapper json;

    public ToolNameRepairService(ToolRepository tools,
                                 CapabilityRepository capabilities,
                                 ObjectMapper json) {
        this.tools = tools;
        this.capabilities = capabilities;
        this.json = json;
    }

    /**
     * Dry-run: compute the renames the operator would see, without writing.
     */
    public Report plan(String tenantId) {
        return compute(tenantId);
    }

    /**
     * Apply the renames. Throws {@link CollisionException} (with the full
     * collision map) when any sanitised target would clash with an existing
     * tool id.
     */
    @Transactional
    public Report apply(String tenantId) {
        Report plan = compute(tenantId);
        if (!plan.collisions().isEmpty()) {
            throw new CollisionException(plan.collisions());
        }
        if (plan.renames().isEmpty()) return plan;

        for (Rename r : plan.renames()) {
            tools.renameAcrossVersions(tenantId, r.from(), r.to());
        }

        int caps = cascadeCapabilities(tenantId, plan.renames());
        return new Report(plan.renames(), Map.of(), caps);
    }

    /**
     * Cleans up capability {@code tool_ids_json} arrays so they point at the
     * new ids. Returns the number of capability rows touched.
     */
    private int cascadeCapabilities(String tenantId, List<Rename> renames) {
        Map<String, String> renameMap = new LinkedHashMap<>();
        for (Rename r : renames) renameMap.put(r.from(), r.to());

        int touched = 0;
        for (CapabilityEntity cap : capabilities.findByTenantId(tenantId)) {
            List<String> toolIds = parseList(cap.getToolIdsJson());
            boolean changed = false;
            List<String> rewritten = new ArrayList<>(toolIds.size());
            for (String id : toolIds) {
                String mapped = renameMap.get(id);
                if (mapped != null) {
                    rewritten.add(mapped);
                    changed = true;
                } else {
                    rewritten.add(id);
                }
            }
            if (changed) {
                try {
                    cap.setToolIdsJson(json.writeValueAsString(rewritten));
                    capabilities.save(cap);
                    touched++;
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "failed to re-encode tool_ids_json for capability "
                                    + cap.getId() + "@" + cap.getVersion(), e);
                }
            }
        }
        return touched;
    }

    private Report compute(String tenantId) {
        Set<String> existingIds = new LinkedHashSet<>();
        Map<String, List<Integer>> versionsById = new LinkedHashMap<>();
        for (ToolEntity t : tools.findByTenantId(tenantId)) {
            existingIds.add(t.getId());
            versionsById.computeIfAbsent(t.getId(), k -> new ArrayList<>()).add(t.getVersion());
        }

        List<Rename> renames = new ArrayList<>();
        Map<String, List<String>> proposedTargets = new LinkedHashMap<>();

        for (String id : existingIds) {
            if (ToolIdPattern.isValid(id)) continue;
            String target = ToolIdPattern.sanitize(id);
            if (target == null) {
                proposedTargets
                        .computeIfAbsent("(sanitises to empty)", k -> new ArrayList<>())
                        .add(id);
                continue;
            }
            proposedTargets.computeIfAbsent(target, k -> new ArrayList<>()).add(id);
        }

        Map<String, List<String>> collisions = new LinkedHashMap<>();
        for (var e : proposedTargets.entrySet()) {
            String target = e.getKey();
            List<String> originals = e.getValue();
            boolean clashesWithExisting = existingIds.contains(target)
                    && !originals.contains(target);
            if (originals.size() > 1 || clashesWithExisting
                    || "(sanitises to empty)".equals(target)) {
                List<String> all = new ArrayList<>(originals);
                if (clashesWithExisting) all.add(target + "  (existing)");
                collisions.put(target, all);
            } else {
                renames.add(new Rename(originals.get(0), target,
                        Collections.unmodifiableList(versionsById.get(originals.get(0)))));
            }
        }

        return new Report(renames, collisions, 0);
    }

    private List<String> parseList(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return List.of();
        try {
            return json.readValue(jsonStr, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public record Rename(String from, String to, List<Integer> versions) {}

    /**
     * @param renames                applied (or planned) renames
     * @param collisions             sanitisedTarget → list of original ids that
     *                               would land on it; non-empty means no writes occurred
     * @param capabilitiesUpdated    number of capability rows whose
     *                               {@code tool_ids_json} was rewritten
     */
    public record Report(List<Rename> renames,
                         Map<String, List<String>> collisions,
                         int capabilitiesUpdated) {}

    public static final class CollisionException extends RuntimeException {
        private final Map<String, List<String>> collisions;
        public CollisionException(Map<String, List<String>> collisions) {
            super("tool id repair would collide on " + collisions.size() + " target(s)");
            this.collisions = collisions;
        }
        public Map<String, List<String>> collisions() { return collisions; }
    }
}

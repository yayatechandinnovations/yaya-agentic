package com.yayatechandinnovations.yayaagentic.channel.chat.receipts;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ReceiptTemplateEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ReceiptTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-authored receipt templates ({@code chat-experience-design.md} §7.4).
 * The element vocabulary is closed and domain-free by contract — validation
 * rejects unknown element types so the client renderer contract stays stable;
 * everything domain-specific enters as {@code {{path}}}-bound DATA at runtime.
 */
@Service
public class ReceiptTemplateService {

    /** The closed v1 vocabulary (§7.4). Growing it is a design-doc change. */
    public static final Set<String> ELEMENT_TYPES = Set.of(
            "header", "status", "meta", "divider", "title", "text", "kv",
            "section", "table", "cells", "total", "code", "barcode", "qr",
            "footer", "spacer");

    public static final String TRAIT_KIND = "chat.receipts";
    private static final int MAX_ELEMENTS = 80;
    private static final Set<String> PAPER_PROFILES = Set.of("58mm", "80mm");

    public static class InvalidTemplateException extends RuntimeException {
        public InvalidTemplateException(String message) {
            super(message);
        }
    }

    private final ReceiptTemplateRepository templates;

    public ReceiptTemplateService(ReceiptTemplateRepository templates) {
        this.templates = templates;
    }

    /* -------------------------------- CRUD --------------------------------- */

    @Transactional(readOnly = true)
    public List<ReceiptTemplateEntity> list(String tenantId) {
        return templates.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /**
     * Seed the starter set for a tenant that has no templates yet (§7.7), so
     * the console opens on "tweak this" instead of an empty JSON editor.
     * No-op once any template exists — including after an operator edits or
     * replaces the starters; only a tenant that deletes ALL templates gets a
     * fresh set on the next console visit.
     */
    @Transactional
    public List<ReceiptTemplateEntity> ensureDefaults(String tenantId) {
        List<ReceiptTemplateEntity> existing = templates.findByTenantIdOrderByCreatedAtDesc(tenantId);
        if (!existing.isEmpty()) return existing;
        for (ReceiptTemplateDefaults.Seed seed : ReceiptTemplateDefaults.all()) {
            create(tenantId, seed.name(), seed.kindLabel(),
                    seed.paperCols(), seed.paperProfile(), seed.elements(), null);
        }
        return templates.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional(readOnly = true)
    public Optional<ReceiptTemplateEntity> find(String tenantId, String id) {
        return templates.findByTenantIdAndId(tenantId, id);
    }

    @Transactional
    public ReceiptTemplateEntity create(String tenantId, String name, String kindLabel,
                                        int paperCols, String paperProfile,
                                        List<Map<String, Object>> elements,
                                        Map<String, Object> brand) {
        validate(name, paperCols, paperProfile, elements);
        String id = "rt_" + UUID.randomUUID().toString().replace("-", "");
        return templates.save(new ReceiptTemplateEntity(id, tenantId, name.trim(), clean(kindLabel),
                paperCols, paperProfile, elements, brand));
    }

    @Transactional
    public Optional<ReceiptTemplateEntity> update(String tenantId, String id, String name,
                                                  String kindLabel, int paperCols,
                                                  String paperProfile,
                                                  List<Map<String, Object>> elements,
                                                  Map<String, Object> brand) {
        validate(name, paperCols, paperProfile, elements);
        return templates.findByTenantIdAndId(tenantId, id).map(e -> {
            e.setName(name.trim());
            e.setKindLabel(clean(kindLabel));
            e.setPaperCols(paperCols);
            e.setPaperProfile(paperProfile);
            e.setElements(elements);
            e.setBrand(brand);
            return templates.save(e);
        });
    }

    @Transactional
    public boolean delete(String tenantId, String id) {
        return templates.findByTenantIdAndId(tenantId, id).map(e -> {
            templates.delete(e);
            return true;
        }).orElse(false);
    }

    /* --------------------------- trait resolution --------------------------- */

    /**
     * Resolve the profile's {@code chat.receipts} extension into the trait the
     * client receives at session start (§7.3): template ids become full
     * payloads (one round-trip, offline-tolerant rendering); the per-tool
     * {@code bindings} fallback map rides through untouched.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> resolveTrait(Ids.TenantId tenant, Object rawExtension) {
        if (!(rawExtension instanceof Map<?, ?> ext)) return null;
        List<Map<String, Object>> resolved = new ArrayList<>();
        Object ids = ext.get("templates");
        if (ids instanceof List<?> list) {
            for (Object id : list) {
                templates.findByTenantIdAndId(tenant.value(), String.valueOf(id))
                        .ifPresent(t -> resolved.add(payloadOf(t)));
            }
        }
        Map<String, Object> trait = new LinkedHashMap<>();
        trait.put("templates", resolved);
        Object bindings = ext.get("bindings");
        if (bindings instanceof Map<?, ?> b && !b.isEmpty()) trait.put("bindings", bindings);
        return trait;
    }

    /** The client-facing template payload — also the wallet snapshot shape. */
    public static Map<String, Object> payloadOf(ReceiptTemplateEntity t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("name", t.getName());
        if (t.getKindLabel() != null) out.put("kindLabel", t.getKindLabel());
        out.put("paper", Map.of("cols", t.getPaperCols(), "profile", t.getPaperProfile()));
        out.put("elements", t.getElements());
        if (t.getBrand() != null && !t.getBrand().isEmpty()) out.put("brand", t.getBrand());
        return out;
    }

    /* ------------------------------ validation ------------------------------ */

    private static void validate(String name, int paperCols, String paperProfile,
                                 List<Map<String, Object>> elements) {
        if (name == null || name.isBlank()) {
            throw new InvalidTemplateException("name is required");
        }
        if (paperCols < 20 || paperCols > 64) {
            throw new InvalidTemplateException("paperCols must be between 20 and 64");
        }
        if (!PAPER_PROFILES.contains(paperProfile)) {
            throw new InvalidTemplateException("paperProfile must be one of " + PAPER_PROFILES);
        }
        if (elements == null || elements.isEmpty()) {
            throw new InvalidTemplateException("elements is required");
        }
        if (elements.size() > MAX_ELEMENTS) {
            throw new InvalidTemplateException("too many elements (max " + MAX_ELEMENTS + ")");
        }
        for (Map<String, Object> el : elements) {
            validateElement(el, true);
        }
    }

    private static void validateElement(Map<String, Object> el, boolean allowSection) {
        Object type = el == null ? null : el.get("type");
        if (!(type instanceof String s) || !ELEMENT_TYPES.contains(s)) {
            throw new InvalidTemplateException("unknown element type '" + type
                    + "' — allowed: " + ELEMENT_TYPES);
        }
        if ("section".equals(s)) {
            // One level of nesting only (§7.4).
            if (!allowSection) {
                throw new InvalidTemplateException("sections cannot nest");
            }
            Object inner = el.get("elements");
            if (!(inner instanceof List<?> list) || list.isEmpty()) {
                throw new InvalidTemplateException("a section needs child elements");
            }
            for (Object child : list) {
                if (!(child instanceof Map<?, ?> m)) {
                    throw new InvalidTemplateException("section children must be elements");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> childEl = new HashMap<>((Map<String, Object>) m);
                validateElement(childEl, false);
            }
        }
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

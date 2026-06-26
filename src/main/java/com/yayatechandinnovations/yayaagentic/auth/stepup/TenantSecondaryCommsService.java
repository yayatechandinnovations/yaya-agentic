package com.yayatechandinnovations.yayaagentic.auth.stepup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Reads and writes {@code tenants.secondary_comms_json}. Keeps the JSON
 * shape narrow — the JPA entity stays string-typed and Jackson lives at the
 * service boundary so the persistence layer doesn't take a Jackson dep.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §9.5.
 */
@Service
public class TenantSecondaryCommsService {

    private final TenantRepository tenants;
    private final ObjectMapper json;

    public TenantSecondaryCommsService(TenantRepository tenants, ObjectMapper json) {
        this.tenants = tenants;
        this.json = json;
    }

    public TenantSecondaryComms forTenant(Ids.TenantId tenant) {
        return tenants.findById(tenant.value())
                .map(this::parse)
                .orElseGet(TenantSecondaryComms::empty);
    }

    public void update(Ids.TenantId tenant, TenantSecondaryComms comms) {
        TenantEntity row = tenants.findById(tenant.value())
                .orElseThrow(() -> new IllegalArgumentException(
                        "tenant not found: " + tenant.value()));
        try {
            row.setSecondaryCommsJson(json.writeValueAsString(comms));
            tenants.save(row);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode TenantSecondaryComms", e);
        }
    }

    private TenantSecondaryComms parse(TenantEntity row) {
        String raw = row.getSecondaryCommsJson();
        if (raw == null || raw.isBlank() || "{}".equals(raw)) {
            return TenantSecondaryComms.empty();
        }
        try {
            JsonNode node = json.readTree(raw);
            Optional<TenantSecondaryComms.EmailRelay> emailRelay = Optional.ofNullable(node.get("emailRelay"))
                    .filter(n -> !n.isNull())
                    .map(n -> new TenantSecondaryComms.EmailRelay(
                            text(n, "fromAddress"), text(n, "providerId")));
            Optional<TenantSecondaryComms.SmsBackup> smsBackup = Optional.ofNullable(node.get("smsBackup"))
                    .filter(n -> !n.isNull())
                    .map(n -> new TenantSecondaryComms.SmsBackup(
                            text(n, "fromNumber"), text(n, "providerId")));
            return new TenantSecondaryComms(emailRelay, smsBackup);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not decode TenantSecondaryComms", e);
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }
}

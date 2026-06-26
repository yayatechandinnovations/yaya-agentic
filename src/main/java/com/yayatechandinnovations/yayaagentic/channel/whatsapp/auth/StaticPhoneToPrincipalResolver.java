package com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Sample {@link PhoneToPrincipalResolver} driven by YAML. Used in demos,
 * tests, and playground scenarios where there is no external user store.
 * Real tenants ship their own resolver bean (typically JDBC against their
 * {@code users} table); this implementation is intentionally simple — the
 * point is to show the SPI, not to be production storage.
 *
 * <p>Reads its mappings from {@code yaya.agentic.channel.whatsapp.auth.static-resolver}.
 * The bean is registered unconditionally; when no mappings are configured it
 * silently returns empty results — that is also a useful "the resolver is
 * wired but the tenant has no users yet" state.
 *
 * <p>Example configuration:
 * <pre>
 * yaya:
 *   agentic:
 *     channel:
 *       whatsapp:
 *         auth:
 *           static-resolver:
 *             id: retail-customer-lookup
 *             description: "Maps phones to retail customer IDs"
 *             mappings:
 *               - tenant: acme
 *                 phone: "+15551234567"
 *                 subject: u_42
 *                 scopes: [order:read, shipment:read]
 *                 claims: { tier: gold }
 * </pre>
 *
 * <p>Reserved channel claims are not allowed in {@code claims} (the registry
 * rejects them at runtime); the example deliberately uses tenant-specific keys.
 */
@Component
@ConfigurationProperties("yaya.agentic.channel.whatsapp.auth.static-resolver")
public class StaticPhoneToPrincipalResolver implements PhoneToPrincipalResolver {

    private String id = "static-phone-lookup";
    private String description = "Sample yaml-backed phone-to-principal resolver";
    private List<Mapping> mappings = List.of();
    private Map<MappingKey, Mapping> byKey = Map.of();

    @Override
    public String id() { return id; }

    @Override
    public String description() { return description; }

    public void setId(String id) {
        if (id != null && !id.isBlank()) this.id = id;
    }

    public void setDescription(String description) {
        if (description != null && !description.isBlank()) this.description = description;
    }

    public void setMappings(List<Mapping> mappings) {
        this.mappings = mappings == null ? List.of() : List.copyOf(mappings);
        Map<MappingKey, Mapping> idx = new HashMap<>();
        for (Mapping m : this.mappings) {
            idx.put(new MappingKey(m.tenant(), m.phone()), m);
        }
        this.byKey = Map.copyOf(idx);
    }

    @Override
    public Optional<Principal> resolve(Ids.TenantId tenant,
                                       SenderId sender,
                                       String e164Phone,
                                       ResolutionContext ctx) {
        Mapping m = byKey.get(new MappingKey(tenant.value(), e164Phone));
        if (m == null) return Optional.empty();
        return Optional.of(new Principal(
                m.subject(),
                tenant,
                m.scopes() == null ? Set.of() : Set.copyOf(m.scopes()),
                m.claims() == null ? Map.of() : Map.copyOf(m.claims()),
                Instant.now()));
    }

    /** Operator-supplied phone→principal mapping row. */
    public record Mapping(
            String              tenant,
            String              phone,    // E.164
            String              subject,
            @DefaultValue       List<String>      scopes,
            @DefaultValue       Map<String, Object> claims
    ) {}

    private record MappingKey(String tenantId, String phone) {}
}

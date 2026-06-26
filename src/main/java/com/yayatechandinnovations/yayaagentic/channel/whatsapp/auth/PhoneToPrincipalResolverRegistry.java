package com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bean-name lookup for {@link PhoneToPrincipalResolver} implementations plus
 * a {@code (tenant, sender, phone)} resolution cache.
 *
 * <p>Two responsibilities:
 *
 * <ol>
 *   <li><b>Lookup.</b> The materializer hands the registry a {@link
 *       DefaultPrincipalPolicy.LookupPrincipal#resolverId()}; the registry
 *       returns the matching bean or {@link Optional#empty()}. Unknown ids
 *       cause the materializer to throw — there is no silent fallback to
 *       {@code Anonymous} when the operator pointed the binding at a non-
 *       existent resolver.</li>
 *   <li><b>Caching.</b> Resolutions are deterministic for a given triple
 *       within a 5-minute window (configurable via {@code
 *       yaya.agentic.channel.whatsapp.auth.lookup-cache-ttl}). The cache
 *       short-circuits the resolver for repeat traffic — typical messaging
 *       sessions send dozens of messages from the same phone.</li>
 * </ol>
 *
 * <p>Reserved channel claims (see {@link
 * PrincipalPolicyMaterializer#RESERVED_CLAIM_KEYS}) on a resolver's output
 * cause the resolution to be rejected with a clear audit message. Tenants who
 * accidentally leak channel claims into their Principal-builder code get an
 * obvious error instead of mysterious silent overwrites at materialization
 * time.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §5.2 + §5.3.
 */
@Component
public class PhoneToPrincipalResolverRegistry {

    private static final Logger log =
            LoggerFactory.getLogger(PhoneToPrincipalResolverRegistry.class);

    private final Map<String, PhoneToPrincipalResolver> resolversById;
    private final Map<CacheKey, CachedResolution> cache = new ConcurrentHashMap<>();
    private final Duration cacheTtl;

    public PhoneToPrincipalResolverRegistry(
            List<PhoneToPrincipalResolver> resolvers,
            @Value("${yaya.agentic.channel.whatsapp.auth.lookup-cache-ttl:PT5M}")
                    Duration cacheTtl) {
        this.cacheTtl = cacheTtl == null ? Duration.ofMinutes(5) : cacheTtl;
        Map<String, PhoneToPrincipalResolver> byId = new HashMap<>();
        for (PhoneToPrincipalResolver r : resolvers) {
            if (r.id() == null || r.id().isBlank()) {
                throw new IllegalStateException(
                        "PhoneToPrincipalResolver bean " + r.getClass().getName()
                                + " returned a blank id() — pick a stable kebab-case identifier");
            }
            PhoneToPrincipalResolver prior = byId.put(r.id(), r);
            if (prior != null) {
                throw new IllegalStateException(
                        "Two PhoneToPrincipalResolver beans claim id='" + r.id()
                                + "': " + prior.getClass().getName() + " and "
                                + r.getClass().getName());
            }
        }
        this.resolversById = Map.copyOf(byId);
        log.info("PhoneToPrincipalResolverRegistry initialized with {} resolver(s): {}",
                resolversById.size(), resolversById.keySet());
    }

    /** Resolvers registered at startup, for the admin console dropdown. */
    public Collection<PhoneToPrincipalResolver> all() {
        return resolversById.values();
    }

    /**
     * Resolve a verified phone via the named resolver.
     *
     * @throws UnknownResolverException when no bean claims {@code resolverId};
     *         the materializer surfaces this as an
     *         {@code IllegalStateException} to the authenticator (operator
     *         misconfiguration, not a runtime denial).
     * @throws InvalidResolutionException when the resolver returns a Principal
     *         containing channel-reserved claim keys.
     */
    public Optional<Principal> resolve(String resolverId,
                                       Ids.TenantId tenant,
                                       SenderId sender,
                                       String e164Phone,
                                       ResolutionContext ctx) {
        PhoneToPrincipalResolver resolver = resolversById.get(resolverId);
        if (resolver == null) {
            throw new UnknownResolverException(resolverId, resolversById.keySet());
        }
        CacheKey key = new CacheKey(tenant.value(), sender.value(), e164Phone, resolverId);
        Instant now = Instant.now();
        CachedResolution cached = cache.get(key);
        if (cached != null && cached.expiresAt.isAfter(now)) {
            return cached.principal;
        }
        Optional<Principal> resolved = resolver.resolve(tenant, sender, e164Phone, ctx);
        resolved.ifPresent(p -> rejectReservedClaimLeaks(resolver, p));
        cache.put(key, new CachedResolution(resolved, now.plus(cacheTtl)));
        return resolved;
    }

    /** Drop everything — wired for tests and incident response. */
    public void invalidateAll() {
        cache.clear();
    }

    private static void rejectReservedClaimLeaks(PhoneToPrincipalResolver resolver,
                                                 Principal p) {
        Set<String> leaks = new HashSet<>();
        for (String key : p.claims().keySet()) {
            if (PrincipalPolicyMaterializer.RESERVED_CLAIM_KEYS.contains(key)) {
                leaks.add(key);
            }
        }
        if (!leaks.isEmpty()) {
            throw new InvalidResolutionException(
                    "PhoneToPrincipalResolver '" + resolver.id()
                            + "' returned Principal with reserved channel claims: " + leaks
                            + " (reserved: "
                            + PrincipalPolicyMaterializer.RESERVED_CLAIM_KEYS + ")");
        }
    }

    /** Operator misconfigured a binding: resolverId doesn't match any bean. */
    public static class UnknownResolverException extends RuntimeException {
        public UnknownResolverException(String wanted, Set<String> available) {
            super("no PhoneToPrincipalResolver bean with id '" + wanted
                    + "' is registered (available: " + available + ")");
        }
    }

    /** Tenant resolver returned a non-conformant Principal. */
    public static class InvalidResolutionException extends RuntimeException {
        public InvalidResolutionException(String message) {
            super(message);
        }
    }

    private record CacheKey(String tenantId, String senderId,
                            String phone, String resolverId) {}
    private record CachedResolution(Optional<Principal> principal, Instant expiresAt) {}
}

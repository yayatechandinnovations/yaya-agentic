package com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth;

import com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBindingService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a sender's {@link DefaultPrincipalPolicy} from its binding row.
 * Runs ahead of routing (the Principal mint depends on the policy, but routing
 * may depend on the Principal) so it picks the lowest-priority ACTIVE binding
 * for the endpoint and reads {@code sessionPolicy.defaultPrincipalPolicy} from
 * it.
 *
 * <p>If two bindings for the same endpoint disagree on policy that's an
 * operator misconfiguration — v1 silently uses the lowest-priority one; the
 * admin form discourages mixing policies across sibling bindings. (A later
 * iteration can promote the policy to the sender row to make this impossible.)
 *
 * <p>Cached by {@code (tenant, sender)} with a 1-minute TTL. Bindings change
 * rarely; a stale read is acceptable for the auth path. Re-population happens
 * lazily on TTL expiry — no explicit eviction wired up in W-A1 (the operator's
 * worst case is "wait 60s after a binding edit").
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §5.3.
 */
@Component
public class BindingPolicyResolver {

    private static final Duration TTL = Duration.ofMinutes(1);

    private final ChannelBindingService bindingService;
    private final Map<CacheKey, CachedPolicy> cache = new ConcurrentHashMap<>();

    public BindingPolicyResolver(ChannelBindingService bindingService) {
        this.bindingService = bindingService;
    }

    /**
     * Returns the policy for the binding the dispatch worker will route to.
     * Falls back to {@link DefaultPrincipalPolicy.Anonymous} when no ACTIVE
     * binding exists for the sender — keeps authentication non-blocking; the
     * downstream resolver chain will still bounce the message as
     * {@code no_binding_matched}.
     */
    public DefaultPrincipalPolicy policyFor(Ids.TenantId tenant, SenderId sender) {
        CacheKey key = new CacheKey(tenant.value(), sender.value());
        CachedPolicy cached = cache.get(key);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt.isAfter(now)) {
            return cached.policy;
        }
        DefaultPrincipalPolicy fresh = loadPolicy(tenant, sender);
        cache.put(key, new CachedPolicy(fresh, now.plus(TTL)));
        return fresh;
    }

    /** Drop everything — wired in for tests; admin paths can call this after
     *  a binding update if 60s of staleness is unacceptable. */
    public void invalidateAll() {
        cache.clear();
    }

    private DefaultPrincipalPolicy loadPolicy(Ids.TenantId tenant, SenderId sender) {
        return bindingService
                .findLowestPriorityActiveForEndpoint(
                        tenant, new ChannelEndpointRef.WhatsAppSenderRef(sender))
                .map(b -> Objects.requireNonNullElseGet(
                        b.sessionPolicy().defaultPrincipalPolicy(),
                        DefaultPrincipalPolicy.Anonymous::new))
                .orElseGet(DefaultPrincipalPolicy.Anonymous::new);
    }

    private record CacheKey(String tenantId, String senderId) {}
    private record CachedPolicy(DefaultPrincipalPolicy policy, Instant expiresAt) {}
}

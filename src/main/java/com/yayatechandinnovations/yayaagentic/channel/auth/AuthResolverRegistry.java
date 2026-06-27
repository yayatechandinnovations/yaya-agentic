package com.yayatechandinnovations.yayaagentic.channel.auth;

import com.yayatechandinnovations.yayaagentic.persistence.ChannelAuthResolverEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelAuthResolverRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves {@code (tenantId, name)} to a configured {@link AuthResolver}.
 *
 * <p>This registry is intentionally thin. It does NOT cache resolved JWTs —
 * that is the JWT cache's job (Redis, see PR 4 in design §15). It also
 * does NOT cache resolver INSTANCES across calls; each {@link #lookup}
 * reads the row fresh so admin edits (timeout bumps, secret rotations)
 * take effect on the next session start without an evict step. The DB
 * read is single-key and indexed.
 *
 * <p>BEAN-kind rows dispatch to a Spring {@code @Component} that
 * implements {@link AuthResolver}; the registry indexes them by
 * {@link AuthResolver#name()} at startup. HTTP-kind rows produce a new
 * {@link HttpAuthResolver} via {@link HttpAuthResolverFactory#build}.
 *
 * <p>See {@code docs/design/channel-auth-resolver-design.md} §5.
 */
@Component
public class AuthResolverRegistry {

    private final ChannelAuthResolverRepository repo;
    private final HttpAuthResolverFactory httpFactory;
    private final Map<String, AuthResolver> beanByName;

    public AuthResolverRegistry(ChannelAuthResolverRepository repo,
                                HttpAuthResolverFactory httpFactory,
                                List<AuthResolver> beans) {
        this.repo = repo;
        this.httpFactory = httpFactory;
        Map<String, AuthResolver> idx = new HashMap<>();
        for (AuthResolver bean : beans) {
            // Skip the platform's own HTTP impl when it's incidentally
            // present in the bean list (it isn't @Component itself, but
            // defensive).
            if (bean instanceof HttpAuthResolver) continue;
            if (bean.name() == null || bean.name().isBlank()) {
                throw new IllegalStateException(
                        "AuthResolver bean " + bean.getClass().getName()
                                + " returned a blank name() — pick a stable identifier");
            }
            AuthResolver prior = idx.put(bean.name(), bean);
            if (prior != null && prior != bean) {
                throw new IllegalStateException(
                        "Two AuthResolver beans claim name='" + bean.name() + "': "
                                + prior.getClass().getName() + " and " + bean.getClass().getName());
            }
        }
        this.beanByName = Map.copyOf(idx);
    }

    /**
     * Look up an active resolver by {@code (tenantId, name)}.
     *
     * @return empty when no ACTIVE row matches the name, or when a BEAN
     *         row points at a {@code bean_name} that no registered bean
     *         claims (operator misconfiguration — surfaces as audit, not
     *         a silent denial, when the caller upgrades empty to an
     *         {@link AuthResolverException}).
     */
    public Optional<AuthResolver> lookup(String tenantId, String name) {
        return repo.findByTenantIdAndName(tenantId, name)
                .filter(row -> "ACTIVE".equals(row.getStatus()))
                .map(this::materialize);
    }

    /** Variant for callers that hold the row already (the admin test
     *  probe, the engine wiring that loaded the binding's row). */
    public AuthResolver materialize(ChannelAuthResolverEntity row) {
        return switch (row.getKind()) {
            case "BEAN" -> {
                AuthResolver bean = beanByName.get(row.getBeanName());
                if (bean == null) {
                    throw new AuthResolverException("config_error",
                            "no AuthResolver bean named '" + row.getBeanName()
                                    + "' is registered on this deployment "
                                    + "(available: " + beanByName.keySet() + ")");
                }
                yield bean;
            }
            case "HTTP" -> httpFactory.build(row);
            default -> throw new AuthResolverException("config_error",
                    "resolver '" + row.getName() + "' has unknown kind '" + row.getKind() + "'");
        };
    }

    /** Bean names registered on this deployment, sorted, for the admin
     *  form's {@code kind=BEAN} dropdown. */
    public List<String> registeredBeanNames() {
        return beanByName.keySet().stream().sorted().toList();
    }
}

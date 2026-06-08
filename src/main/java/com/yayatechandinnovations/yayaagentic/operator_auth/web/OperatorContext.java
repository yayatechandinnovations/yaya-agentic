package com.yayatechandinnovations.yayaagentic.operator_auth.web;

import com.yayatechandinnovations.yayaagentic.operator_auth.Operator;
import org.springframework.web.server.ServerWebExchange;

import java.util.Optional;

/**
 * Read-only accessor for the authenticated operator on a request.
 * {@link OperatorAuthWebFilter} stashes the {@link Operator} into the
 * exchange attribute under {@link #ATTR}; controllers and downstream code
 * read it via {@link #current(ServerWebExchange)}.
 *
 * <p>Reactive code paths can also resolve the operator from the Reactor
 * context — for v1 the exchange attribute is enough.</p>
 */
public final class OperatorContext {

    public static final String ATTR = OperatorContext.class.getName() + ".operator";

    private OperatorContext() {}

    public static Optional<Operator> current(ServerWebExchange exchange) {
        Object v = exchange.getAttribute(ATTR);
        return v instanceof Operator op ? Optional.of(op) : Optional.empty();
    }
}

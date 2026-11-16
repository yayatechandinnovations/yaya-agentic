package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;

import com.yayatechandinnovations.yayaagentic.operator_auth.Operator;
import com.yayatechandinnovations.yayaagentic.operator_auth.web.OperatorContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The gate of surfaces that are not workspace-scoped (the WhatsApp account
 * templates): platform admins and platform-scope keys pass, a workspace member
 * or a key pinned to one workspace does not.
 */
class PlatformAdminGateTest {

    private final WorkspaceAccessHelper helper = new WorkspaceAccessHelper(
            mock(WorkspaceAccessGuard.class), mock(WorkspaceService.class), true);

    @Test
    void platform_admins_and_platform_scope_keys_pass() {
        assertThatCode(() -> helper.requirePlatformAdmin(as(Operator.Source.USER_PASSWORD, true))).doesNotThrowAnyException();
        assertThatCode(() -> helper.requirePlatformAdmin(as(Operator.Source.API_KEY, true))).doesNotThrowAnyException();
        assertThatCode(() -> helper.requirePlatformAdmin(as(Operator.Source.BOOTSTRAP, null))).doesNotThrowAnyException();
        assertThat(helper.actorOf(as(Operator.Source.API_KEY, true))).isEqualTo("api_key:someone");
    }

    @Test
    void workspace_members_and_pinned_keys_are_forbidden_and_nobody_is_unauthorized() {
        for (Operator.Source source : new Operator.Source[]{Operator.Source.USER_PASSWORD, Operator.Source.API_KEY,
                Operator.Source.HTTP_DELEGATE}) {
            ServerWebExchange exchange = as(source, false);
            assertThat(helper.isPlatformAdmin(exchange)).isFalse();
            assertThatThrownBy(() -> helper.requirePlatformAdmin(exchange))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        ServerWebExchange anonymous = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        assertThatThrownBy(() -> helper.requirePlatformAdmin(anonymous))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(helper.actorOf(anonymous)).isEqualTo("unknown");
    }

    private static ServerWebExchange as(Operator.Source source, Boolean platformAdmin) {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        Map<String, Object> attributes = platformAdmin == null ? Map.of()
                : Map.of("is_platform_admin", platformAdmin);
        exchange.getAttributes().put(OperatorContext.ATTR,
                new Operator("someone", "Someone", source, attributes, Instant.now()));
        return exchange;
    }
}

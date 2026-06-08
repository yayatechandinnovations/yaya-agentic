package com.yayatechandinnovations.yayaagentic.operator_auth.web;

import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.yayatechandinnovations.yayaagentic.operator_auth.config.OperatorAuthConfigService;
import com.yayatechandinnovations.yayaagentic.operator_auth.config.SecretCipher;
import com.yayatechandinnovations.yayaagentic.operator_auth.delegate.DelegateInvoker;
import com.yayatechandinnovations.yayaagentic.operator_auth.delegate.FailureMapping;
import com.yayatechandinnovations.yayaagentic.operator_auth.delegate.HttpDelegateConfig;
import com.yayatechandinnovations.yayaagentic.operator_auth.delegate.IdentityMapping;
import com.yayatechandinnovations.yayaagentic.operator_auth.delegate.RequestShape;
import com.yayatechandinnovations.yayaagentic.operator_auth.delegate.SuccessCriteria;
import com.yayatechandinnovations.yayaagentic.persistence.AuditOperatorLoginRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Admin endpoints for editing the operator-auth strategy config and
 * exercising the Test button. Gated by {@link OperatorAuthWebFilter} via
 * the {@code /v1/admin/**} prefix.
 *
 * <p>The Test endpoint returns the same {@link DelegateInvoker.ProbeResult}
 * the runtime authenticator computes — so the operator's preview matches
 * production behaviour exactly.</p>
 */
@RestController
@RequestMapping("/v1/admin/auth/strategies")
public class OperatorAuthStrategiesController {

    private final OperatorAuthConfigService configService;
    private final DelegateInvoker invoker;
    private final AuditOperatorLoginRepository auditRepo;

    public OperatorAuthStrategiesController(OperatorAuthConfigService configService,
                                            DelegateInvoker invoker,
                                            AuditOperatorLoginRepository auditRepo) {
        this.configService = configService;
        this.invoker = invoker;
        this.auditRepo = auditRepo;
    }

    @GetMapping
    public Mono<StrategiesResponse> current() {
        return Mono.fromCallable(this::loadResponse).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/bootstrap")
    public Mono<StrategiesResponse> putBootstrap(@RequestBody BootstrapRequest body,
                                                 ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            String operator = OperatorContext.current(exchange)
                    .map(o -> o.subject()).orElse("system");
            configService.saveBootstrap(body.enabled(), body.newPassword(), operator);
            return loadResponse();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/http-delegate")
    public Mono<StrategiesResponse> putDelegate(@RequestBody DelegateRequest body,
                                                @RequestParam(defaultValue = "false") boolean confirmPermissive,
                                                ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            HttpDelegateConfig cfg = toConfig(body);
            validate(cfg, confirmPermissive);
            String operator = OperatorContext.current(exchange)
                    .map(o -> o.subject()).orElse("system");
            configService.saveDelegate(cfg, operator);
            return loadResponse();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/http-delegate/test")
    public Mono<DelegateInvoker.ProbeResult> test(@RequestBody TestRequest body) {
        return Mono.fromCallable(() -> {
            HttpDelegateConfig cfg = configService.delegateState();
            if (cfg == null || !cfg.enabled() || cfg.url() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "http-delegate not configured");
            }
            char[] pw = body.password() == null ? new char[0] : body.password().toCharArray();
            try {
                return invoker.invoke(cfg, body.username(), pw, UUID.randomUUID().toString());
            } finally {
                java.util.Arrays.fill(pw, '\0');
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ------------------------------------------------------------------

    private StrategiesResponse loadResponse() {
        OperatorAuthConfigService.Snapshot snap = configService.current();
        long delegateAllows = auditRepo.countByDecisionAndSource("ALLOW", "HTTP_DELEGATE");
        return new StrategiesResponse(
                new BootstrapView(snap.bootstrap().enabled(), snap.bootstrap().username()),
                new DelegateView(
                        snap.delegate().enabled(),
                        snap.delegate().url(),
                        SecretCipher.mask(snap.delegateSecretPresent() ? "secret" : null),
                        snap.delegateSecretPresent(),
                        snap.delegate().timeout().toMillis(),
                        snap.delegate().requireHttps(),
                        snap.delegate().request(),
                        snap.delegate().success(),
                        snap.delegate().identity(),
                        snap.delegate().failure()),
                snap.updatedAt() == null ? null : snap.updatedAt().toString(),
                snap.updatedBy(),
                // Disable-bootstrap guard: only safe to do once at least one
                // operator has successfully logged in via the delegate.
                delegateAllows > 0);
    }

    private HttpDelegateConfig toConfig(DelegateRequest body) {
        Duration timeout = body.timeoutMs() == null
                ? Duration.ofSeconds(5)
                : Duration.ofMillis(body.timeoutMs());
        return new HttpDelegateConfig(
                Boolean.TRUE.equals(body.enabled()),
                body.url(),
                body.sharedSecret(),    // null = preserve existing
                timeout,
                body.requireHttps() == null || body.requireHttps(),
                body.request() == null ? RequestShape.defaults() : body.request(),
                body.success() == null ? SuccessCriteria.defaults() : body.success(),
                body.identity() == null ? IdentityMapping.defaults() : body.identity(),
                body.failure() == null ? FailureMapping.defaults() : body.failure());
    }

    private void validate(HttpDelegateConfig cfg, boolean confirmPermissive) {
        if (cfg.enabled()) {
            if (cfg.url() == null || cfg.url().isBlank()) {
                throw badRequest("url is required when http-delegate is enabled");
            }
            if (cfg.requireHttps() && !cfg.url().toLowerCase().startsWith("https://")) {
                throw badRequest("url must be https when requireHttps=true");
            }
        }

        // The first save MUST include a secret. Subsequent saves may omit
        // it (null = keep existing); the service handles that.
        var existing = configService.current();
        if (cfg.enabled()
                && (cfg.sharedSecret() == null || cfg.sharedSecret().isEmpty())
                && !existing.delegateSecretPresent()) {
            throw badRequest("shared-secret is required");
        }

        // JSONPath compile validation — catches typos at save time.
        compileIfPresent(cfg.success().jsonPathExists(), "success.jsonPathExists");
        if (cfg.success().jsonPathEquals() != null) {
            for (int i = 0; i < cfg.success().jsonPathEquals().size(); i++) {
                compileIfPresent(cfg.success().jsonPathEquals().get(i).path(),
                        "success.jsonPathEquals[" + i + "].path");
            }
        }
        compileIfPresent(cfg.identity().subjectPath(), "identity.subjectPath");
        compileIfPresent(cfg.identity().displayNamePath(), "identity.displayNamePath");
        compileIfPresent(cfg.identity().attributesPath(), "identity.attributesPath");
        compileIfPresent(cfg.failure().reasonPath(), "failure.reasonPath");

        // statusIn must contain only 2xx codes unless the operator
        // confirmed they really mean to allow non-2xx as success.
        List<Integer> nonOk = new ArrayList<>();
        for (Integer s : cfg.success().statusIn()) {
            if (s == null || s < 200 || s >= 300) nonOk.add(s);
        }
        if (!nonOk.isEmpty() && !confirmPermissive) {
            throw badRequest("success.statusIn contains non-2xx codes " + nonOk
                    + " — re-submit with ?confirmPermissive=true to allow this");
        }
    }

    private void compileIfPresent(String path, String field) {
        if (path == null || path.isBlank()) return;
        try {
            JsonPath.compile(path);
        } catch (InvalidPathException e) {
            throw badRequest(field + ": invalid JSONPath '" + path + "' — " + e.getMessage());
        }
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ---- DTOs --------------------------------------------------------

    public record BootstrapRequest(Boolean enabled, String newPassword) {}

    public record DelegateRequest(
            Boolean enabled,
            String url,
            String sharedSecret,
            Long timeoutMs,
            Boolean requireHttps,
            RequestShape request,
            SuccessCriteria success,
            IdentityMapping identity,
            FailureMapping failure
    ) {}

    public record TestRequest(String username, String password) {}

    public record StrategiesResponse(
            BootstrapView bootstrap,
            DelegateView delegate,
            String updatedAt,
            String updatedBy,
            boolean canDisableBootstrap
    ) {}

    public record BootstrapView(boolean enabled, String username) {}

    public record DelegateView(
            boolean enabled,
            String url,
            String sharedSecretMask,
            boolean sharedSecretPresent,
            long timeoutMs,
            boolean requireHttps,
            RequestShape request,
            SuccessCriteria success,
            IdentityMapping identity,
            FailureMapping failure
    ) {}
}

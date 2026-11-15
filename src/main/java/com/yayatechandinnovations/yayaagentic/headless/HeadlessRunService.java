package com.yayatechandinnovations.yayaagentic.headless;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.auth.PrincipalKinds;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.config.SessionsApiProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import com.yayatechandinnovations.yayaagentic.llm.StubLlmClient;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import com.yayatechandinnovations.yayaagentic.usage.QuotaExceededException;
import com.yayatechandinnovations.yayaagentic.usage.UsageMeter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless runs ({@code docs/guides/admin-panel-sessions.md} §17): a tenant
 * service submits a stored question together with a short-lived read-only
 * token of the person it answers for, the profile's agent answers it with
 * nobody present, and the service polls for the answer.
 *
 * <p>What makes a run safe to leave alone is enforced by the engine
 * ({@link ConversationEngine#sendHeadless}): only tools flagged
 * {@code policy.headless} are offered or dispatched, and nothing waits for a
 * person. This class owns the rest — admission, the one message a run costs,
 * the queue, the deadline and the stored outcome.</p>
 *
 * <p>The principal token lives in the queued task and in the one turn's
 * request context. It is never stored and never logged, so a run this process
 * did not finish cannot be resumed: {@link #failUnfinished} closes it as
 * {@code interrupted} at the next start.</p>
 */
@Service
public class HeadlessRunService {

    private static final Logger LOG = LoggerFactory.getLogger(HeadlessRunService.class);

    public static final int QUESTION_MAX = 2_000;
    public static final int IDEM_KEY_MAX = 120;
    public static final int ANSWER_CHARS_MIN = 200;
    public static final int ANSWER_CHARS_MAX = 4_000;
    public static final int ANSWER_CHARS_DEFAULT = 1_800;
    public static final int DEADLINE_MIN = 30;
    public static final int DEADLINE_MAX = 300;
    public static final int DEADLINE_DEFAULT = 240;
    private static final int CONTEXT_MAX = 4_000;
    /**
     * How long past the longest deadline a run may still be writing its
     * outcome before {@link #housekeep} takes it for abandoned.
     */
    static final Duration STALE_MARGIN = Duration.ofMinutes(5);
    /** How long a key answers with the run it first named. */
    static final Duration IDEM_WINDOW = Duration.ofHours(24);

    public static final String FAIL_QUOTA_EXCEEDED = "quota_exceeded";
    public static final String FAIL_PRINCIPAL_REJECTED = "principal_rejected";
    public static final String FAIL_NO_ANSWER = "no_answer";
    public static final String FAIL_NEEDS_INTERACTION = HeadlessStop.NEEDS_INTERACTION;
    public static final String FAIL_TOOL_ROUNDS_EXHAUSTED = HeadlessStop.TOOL_ROUNDS_EXHAUSTED;
    public static final String FAIL_DEADLINE_EXCEEDED = "deadline_exceeded";
    public static final String FAIL_LLM_ERROR = "llm_error";
    public static final String FAIL_INTERRUPTED = "interrupted";
    /** The run token would not outlive the run's deadline: nothing was started, nothing counted. */
    public static final String FAIL_TOKEN_EXPIRING = "token_expiring";

    /** The claims that make a token a run token: an automation's, and read-only. */
    static final String CLAIM_VIA = "via";
    static final String VIA_AUTOMATION = "automation";
    static final String CLAIM_VIA_READ_ONLY = "viaReadOnly";

    /** A submit that was accepted, or that repeats one that was. */
    public record Accepted(String runId, String status) {}

    private final HeadlessRunStore store;
    private final ConversationEngine engine;
    private final Authenticator authenticator;
    private final ProfileRegistry profiles;
    private final PrincipalKinds principalKinds;
    private final UsageMeter usage;
    private final ChatRateLimiter rateLimiter;
    private final LlmClient llm;
    private final ObjectMapper json;
    private final Clock clock;
    private final HeadlessRunProperties props;
    private final boolean enforcePrincipalKinds;
    private final ThreadPoolExecutor workers;
    /** Serializes "how many are in flight?" with "add one" on this instance. */
    private final Object admission = new Object();

    public HeadlessRunService(HeadlessRunStore store,
                              ConversationEngine engine,
                              Authenticator authenticator,
                              ProfileRegistry profiles,
                              PrincipalKinds principalKinds,
                              UsageMeter usage,
                              ChatRateLimiter rateLimiter,
                              LlmClient llm,
                              ObjectMapper json,
                              Clock clock,
                              HeadlessRunProperties props,
                              SessionsApiProperties sessionsApi) {
        this.store = store;
        this.engine = engine;
        this.authenticator = authenticator;
        this.profiles = profiles;
        this.principalKinds = principalKinds;
        this.usage = usage;
        this.rateLimiter = rateLimiter;
        this.llm = llm;
        this.json = json;
        this.clock = clock;
        this.props = props == null ? HeadlessRunProperties.defaults() : props;
        this.enforcePrincipalKinds = sessionsApi == null || sessionsApi.enforcePrincipalKindsOrDefault();
        AtomicInteger n = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(this.props.workersOrDefault(), this.props.workersOrDefault(),
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(this.props.queueOrDefault()),
                r -> {
                    Thread t = new Thread(r, "headless-run-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * True from the moment the application starts to stop. A run cut by that
     * is {@code interrupted} — the caller hands it over once more, as after a
     * crash — and not {@code llm_error}, which a caller takes as the run's own
     * failure and does not repeat: an orderly restart (every deploy) would
     * otherwise cost each run in flight its answer.
     */
    private volatile boolean stopping;

    /**
     * The first thing an orderly stop does, before the web server, the HTTP
     * clients and the beans are closed: from here on a run that is cut was
     * cut by the stop.
     */
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    void onContextClosing() {
        stopping = true;
    }

    @PreDestroy
    void shutdown() {
        stopping = true;
        workers.shutdownNow();
        try {
            // Let the runs that were cut write how they ended while the store is still open.
            workers.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** For tests: what {@link #shutdown()} sets before it stops the workers. */
    void stopping(boolean value) {
        this.stopping = value;
    }

    // ---- submit -------------------------------------------------------------

    /**
     * Accepts a run for {@code tenant} (the tenant the service token proved),
     * or throws the {@link HeadlessRunException} of the refusal. Blocking.
     */
    public Accepted submit(String tenant, String idemKey, byte[] body) {
        if (idemKey == null || idemKey.isBlank()) {
            throw HeadlessRunException.invalidRequest("X-Idem-Key is required");
        }
        String key = idemKey.trim();
        if (key.length() > IDEM_KEY_MAX) {
            throw HeadlessRunException.invalidRequest("X-Idem-Key must be at most " + IDEM_KEY_MAX + " characters");
        }
        Request request = parse(body);

        // A key the tenant already sent names the run it started, whatever
        // has changed since: nothing below is checked or counted again.
        Optional<Accepted> repeated = repeatOf(tenant, key);
        if (repeated.isPresent()) return repeated.get();

        Ids.TenantId tenantId = new Ids.TenantId(tenant);
        Profile profile = profiles.findLatest(tenantId, Ids.ProfileId.latest(request.profileId()))
                .orElseThrow(HeadlessRunException::profileNotFound);
        Principal principal = verifyRunToken(tenantId, request.principalToken());
        if (enforcePrincipalKinds && !principalKinds.admits(profile, principal)) {
            LOG.warn("headless run refused: profile {} admits kinds {} but principal '{}' of tenant '{}' has {}",
                    profile.id().value(), PrincipalKinds.declaredBy(profile), principal.subject(), tenant,
                    principalKinds.kindsOf(tenantId, principal));
            throw HeadlessRunException.profileNotPermitted();
        }
        // With no tool it may call the model would answer from nothing: refused
        // here, by the engine's own selection, so no message is taken for it.
        if (!engine.offersHeadlessTools(profile)) throw HeadlessRunException.noHeadlessTools();
        // The run token must outlive the run. Checked here so a hopeless run is
        // never queued, and again when a worker takes it (the wait is unknown).
        Instant notAfter = earlier(request.principalNotAfter(), expiryOf(principal));
        if (expiresBeforeDeadline(notAfter, clock.instant(), request.deadlineSeconds())) {
            throw HeadlessRunException.tokenExpiring("the principal token expires before the run's deadline of "
                    + request.deadlineSeconds() + " seconds");
        }
        if (llm instanceof StubLlmClient) throw HeadlessRunException.llmUnavailable();
        requireWithinHourlyLimit(tenant);

        String contextJson = request.context() == null ? null : request.context().toString();
        synchronized (admission) {
            Optional<Accepted> raced = repeatOf(tenant, key);
            if (raced.isPresent()) return raced.get();
            if (store.countInFlight(tenant) >= props.maxPerTenantOrDefault()) {
                throw HeadlessRunException.busy("this tenant already has "
                        + props.maxPerTenantOrDefault() + " runs queued or running");
            }
            // Last, so a refusal above never takes a message: the run's one
            // message is taken here, or the run is refused with nothing queued.
            UsageMeter.Reservation reservation;
            try {
                reservation = usage.admit(tenant, profile.id().value(), profile.runtime().metered());
            } catch (QuotaExceededException e) {
                throw HeadlessRunException.quotaExceeded(e.month(), e.monthlyMessages(), e.messages());
            }
            String runId = "hr_" + Ulids.next(clock.millis());
            boolean queued = false;
            try {
                if (!store.insertQueued(runId, tenant, profile.id().value(), profile.id().version(), key,
                        principal.subject(), request.question(), contextJson, clock.instant())) {
                    // Another instance took the key between the look and the insert.
                    return repeatOf(tenant, key).orElseThrow(
                            () -> HeadlessRunException.busy("try again"));
                }
                workers.execute(new Task(runId, tenantId, profile, principal, request, notAfter, reservation));
                queued = true;
            } catch (RejectedExecutionException e) {
                store.deleteQueued(tenant, runId);
                throw HeadlessRunException.busy("the run queue is full");
            } finally {
                if (!queued) usage.settle(reservation, false, 0, 0);
            }
            LOG.info("headless run {} queued for tenant {} profile {}", runId, tenant, profile.id().value());
            return new Accepted(runId, HeadlessRunStore.QUEUED);
        }
    }

    /** The run a key already names, inside the window; an older one gives its key up. */
    private Optional<Accepted> repeatOf(String tenant, String key) {
        Optional<HeadlessRunStore.Run> existing = store.findByIdemKey(tenant, key);
        if (existing.isEmpty()) return Optional.empty();
        HeadlessRunStore.Run run = existing.get();
        if (run.createdAt() != null && run.createdAt().isBefore(clock.instant().minus(IDEM_WINDOW))) {
            store.releaseIdemKey(tenant, run.id());
            return Optional.empty();
        }
        // The body of the first answer, as it was given then.
        return Optional.of(new Accepted(run.id(), HeadlessRunStore.QUEUED));
    }

    public HeadlessRunStore.Run get(String tenant, String runId) {
        if (runId == null || runId.isBlank()) throw HeadlessRunException.runNotFound();
        return store.find(tenant, runId).orElseThrow(HeadlessRunException::runNotFound);
    }

    /**
     * The token must pass the tenant's ordinary chain — the trusted issuer's
     * signature, expiry and claim pins — and be a run token: an automation's
     * and read-only. Anything else, an ordinary staff token included, is
     * refused: a headless run never acts with a credential that could write.
     */
    private Principal verifyRunToken(Ids.TenantId tenant, String token) {
        Principal principal;
        try {
            principal = authenticator.authenticate(new AuthContext(tenant,
                    Map.of("Authorization", "Bearer " + token), Optional.empty()));
        } catch (AuthenticationException e) {
            LOG.debug("headless run: principal token rejected for tenant {}: {}", tenant.value(), e.getMessage());
            throw HeadlessRunException.principalRejected("the principal token was not accepted");
        }
        String subject = principal == null ? null : principal.subject();
        if (subject == null || subject.isBlank() || subject.startsWith("anonymous-") || subject.contains(":anon:")
                || principal.tenant() == null || !tenant.equals(principal.tenant())) {
            throw HeadlessRunException.principalRejected("the principal token was not accepted");
        }
        Map<String, Object> claims = principal.claims() == null ? Map.of() : principal.claims();
        boolean readOnly = Boolean.TRUE.equals(claims.get(CLAIM_VIA_READ_ONLY))
                || "true".equals(String.valueOf(claims.get(CLAIM_VIA_READ_ONLY)));
        if (!VIA_AUTOMATION.equals(String.valueOf(claims.get(CLAIM_VIA))) || !readOnly) {
            throw HeadlessRunException.principalRejected(
                    "the principal token is not a read-only automation token");
        }
        return principal;
    }

    /** The token's own {@code exp}, when the chain kept it among the claims. */
    private static Instant expiryOf(Principal principal) {
        Object exp = principal.claims() == null ? null : principal.claims().get("exp");
        if (exp instanceof java.util.Date date) return date.toInstant();
        if (exp instanceof Instant instant) return instant;
        if (exp instanceof Number seconds) return Instant.ofEpochSecond(seconds.longValue());
        return null;
    }

    private static Instant earlier(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    /** True when a run started at {@code now} could still be calling tools after the token ends. */
    static boolean expiresBeforeDeadline(Instant notAfter, Instant now, int deadlineSeconds) {
        return notAfter != null && now.plusSeconds(deadlineSeconds).isAfter(notAfter);
    }

    private void requireWithinHourlyLimit(String tenant) {
        ChatRateLimiter.Decision decision;
        try {
            decision = rateLimiter.hit("headless", tenant, props.perHourOrDefault(), Duration.ofHours(1));
        } catch (RuntimeException e) {
            // The limiter's store being down must not take summaries down with it.
            LOG.warn("headless run rate limiter unavailable ({}); allowing", e.getClass().getSimpleName());
            return;
        }
        if (!decision.allowed()) throw HeadlessRunException.rateLimited(decision.retryAfterSeconds());
    }

    // ---- request ------------------------------------------------------------

    record Request(String profileId, String principalToken, String question, String locale, ZoneId timeZone,
                   JsonNode context, int maxAnswerChars, int deadlineSeconds, Instant principalNotAfter) {}

    private Request parse(byte[] body) {
        JsonNode root;
        try {
            root = body == null || body.length == 0 ? null : json.readTree(body);
        } catch (java.io.IOException e) {
            throw HeadlessRunException.invalidRequest("the body is not JSON");
        }
        if (root == null || !root.isObject()) throw HeadlessRunException.invalidRequest("the body must be a JSON object");

        String profileId = text(root, "profileId");
        if (profileId == null) throw HeadlessRunException.invalidRequest("profileId is required");
        String token = text(root, "principalToken");
        if (token == null) throw HeadlessRunException.invalidRequest("principalToken is required");
        String question = text(root, "question");
        if (question == null) throw HeadlessRunException.invalidRequest("question is required");
        if (question.length() > QUESTION_MAX) {
            throw HeadlessRunException.invalidRequest("question must be at most " + QUESTION_MAX + " characters");
        }
        String locale = text(root, "locale");
        if (locale != null && locale.length() > 16) throw HeadlessRunException.invalidRequest("locale is not valid");
        ZoneId zone = ZoneId.of("UTC");
        String zoneId = text(root, "timeZone");
        if (zoneId != null) {
            try {
                zone = ZoneId.of(zoneId);
            } catch (DateTimeException e) {
                throw HeadlessRunException.invalidRequest("timeZone is not an IANA time zone");
            }
        }
        JsonNode context = root.get("context");
        if (context != null && context.isNull()) context = null;
        if (context != null && (!context.isObject() || context.toString().length() > CONTEXT_MAX)) {
            throw HeadlessRunException.invalidRequest("context must be an object of at most "
                    + CONTEXT_MAX + " characters");
        }
        JsonNode limits = root.get("limits");
        if (limits != null && !limits.isNull() && !limits.isObject()) {
            throw HeadlessRunException.invalidRequest("limits must be an object");
        }
        int maxAnswerChars = limit(limits, "maxAnswerChars", ANSWER_CHARS_DEFAULT, ANSWER_CHARS_MIN, ANSWER_CHARS_MAX);
        int deadlineSeconds = limit(limits, "deadlineSeconds", DEADLINE_DEFAULT, DEADLINE_MIN, DEADLINE_MAX);
        Instant notAfter = null;
        String notAfterText = text(root, "principalNotAfter");
        if (notAfterText != null) {
            try {
                notAfter = Instant.parse(notAfterText);
            } catch (DateTimeException e) {
                throw HeadlessRunException.invalidRequest("principalNotAfter must be an ISO-8601 UTC instant");
            }
        }
        return new Request(profileId, token, question, locale, zone, context, maxAnswerChars, deadlineSeconds,
                notAfter);
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw HeadlessRunException.invalidRequest(field + " must be a string");
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static int limit(JsonNode limits, String field, int fallback, int min, int max) {
        JsonNode node = limits == null || limits.isNull() ? null : limits.get(field);
        if (node == null || node.isNull()) return fallback;
        if (!node.isIntegralNumber() || node.asLong() < min || node.asLong() > max) {
            throw HeadlessRunException.invalidRequest(field + " must be a whole number from " + min + " to " + max);
        }
        return node.asInt();
    }

    // ---- the run ------------------------------------------------------------

    /** What the model is told on top of the profile's own prompt, in the per-turn part. */
    static String directive(int maxAnswerChars, LocalDate date, ZoneId zone) {
        return "Esta es una ejecución automática: nadie está presente. Responde la pregunta con los datos de las "
                + "herramientas, en español, en texto simple (sin tablas ni markdown), en " + maxAnswerChars
                + " caracteres como máximo. No preguntes nada, no propongas cambios y no ofrezcas hacer nada. "
                + "Si un dato no está disponible, dilo en una línea. Hoy es " + date + " (" + zone.getId()
                + "). Empieza por assistant_me.";
    }

    /**
     * One queued run. It is the only holder of the principal token; nothing
     * here hands the token to a log, a row or an exception message.
     */
    private final class Task implements Runnable {
        private final String runId;
        private final Ids.TenantId tenant;
        private final Profile profile;
        private final Principal principal;
        private final Request request;
        /** When the principal token ends, if known; the token itself is in {@code request}. */
        private final Instant notAfter;
        private final UsageMeter.Reservation reservation;

        Task(String runId, Ids.TenantId tenant, Profile profile, Principal principal, Request request,
             Instant notAfter, UsageMeter.Reservation reservation) {
            this.runId = runId;
            this.tenant = tenant;
            this.profile = profile;
            this.principal = principal;
            this.request = request;
            this.notAfter = notAfter;
            this.reservation = reservation;
        }

        @Override
        public void run() {
            try {
                execute();
            } catch (RuntimeException e) {
                // Never let a worker die on one run.
                LOG.error("headless run {} failed unexpectedly ({})", runId, e.getClass().getSimpleName());
                store.fail(tenant.value(), runId, stopping ? FAIL_INTERRUPTED : FAIL_LLM_ERROR,
                        stopping ? "the platform stopped during the run" : "the run could not be completed",
                        null, null, clock.instant());
            }
        }

        private void execute() {
            Instant startedAt = clock.instant();
            if (expiresBeforeDeadline(notAfter, startedAt, request.deadlineSeconds())) {
                // It waited too long: the token would end before the deadline does. Not started —
                // no session, no model call, no tool call — and the message goes back. The caller
                // submits again with a fresh token.
                usage.settle(reservation, false, 0, 0);
                store.fail(tenant.value(), runId, FAIL_TOKEN_EXPIRING,
                        "the principal token would expire before the run's deadline; submit again with a fresh token",
                        null, null, startedAt);
                LOG.info("headless run {} of tenant {} not started: {}", runId, tenant.value(), FAIL_TOKEN_EXPIRING);
                return;
            }
            if (!store.markRunning(tenant.value(), runId, startedAt)) {
                // Closed while it waited (a start-up sweep): nothing runs, the message goes back.
                usage.settle(reservation, false, 0, 0);
                return;
            }
            HeadlessTurn turn = new HeadlessTurn(
                    directive(request.maxAnswerChars(), LocalDate.ofInstant(startedAt, request.timeZone()),
                            request.timeZone()),
                    reservation);
            StringBuilder answer = new StringBuilder();
            Set<String> toolsUsed = new LinkedHashSet<>();
            Map<String, String> toolOfCall = new LinkedHashMap<>();
            AtomicInteger toolCalls = new AtomicInteger();

            Ids.SessionId sessionId = null;
            boolean handedToEngine = false;
            String failure = null;
            String failureMessage = null;
            AuthContext auth = new AuthContext(tenant,
                    Map.of("Authorization", "Bearer " + request.principalToken()), Optional.of(principal));
            try {
                sessionId = engine.startHeadless(tenant, profile.id(), principal, turn).session().id();
                store.setSession(tenant.value(), runId, sessionId.value());
                handedToEngine = true;
                engine.sendHeadless(sessionId, new UserMessage(request.question(), Map.of()), auth)
                        .doOnNext(event -> {
                            switch (event) {
                                case TurnEvent.Token t -> answer.append(t.text());
                                case TurnEvent.ToolCall c -> {
                                    // What the model wrote before a call is working text, not the answer.
                                    answer.setLength(0);
                                    toolCalls.incrementAndGet();
                                    toolOfCall.put(c.callId(), c.tool().value());
                                }
                                case TurnEvent.ToolResult r -> {
                                    if (Integer.valueOf(401).equals(r.diagnostics().get("httpStatus"))) {
                                        throw new PrincipalRejected();
                                    }
                                    String tool = toolOfCall.get(r.callId());
                                    if (tool != null && r.status() == Turn.ToolResult.Status.OK) toolsUsed.add(tool);
                                }
                                default -> { /* citations, hints and the end carry nothing a run keeps */ }
                            }
                        })
                        .then()
                        .timeout(Duration.ofSeconds(request.deadlineSeconds()))
                        .block();
            } catch (RuntimeException e) {
                Throwable cause = reactor.core.Exceptions.unwrap(e);
                if (cause instanceof HeadlessStop stop) {
                    failure = stop.code();
                    failureMessage = stop.getMessage();
                } else if (cause instanceof PrincipalRejected) {
                    failure = FAIL_PRINCIPAL_REJECTED;
                    failureMessage = "the backend answered 401 to a tool call";
                } else if (cause instanceof TimeoutException) {
                    failure = FAIL_DEADLINE_EXCEEDED;
                    failureMessage = "not finished in " + request.deadlineSeconds() + " seconds";
                } else if (cause instanceof QuotaExceededException) {
                    failure = FAIL_QUOTA_EXCEEDED;
                    failureMessage = "the monthly allowance of assistant messages is used up";
                } else if (stopping) {
                    // Cut by the application stopping, not by anything the run did.
                    failure = FAIL_INTERRUPTED;
                    failureMessage = "the platform stopped during the run";
                    LOG.warn("headless run {} of tenant {} interrupted: the platform is stopping", runId,
                            tenant.value());
                } else {
                    failure = FAIL_LLM_ERROR;
                    failureMessage = "the model call failed (" + cause.getClass().getSimpleName() + ")";
                    LOG.warn("headless run {} of tenant {} failed: {}", runId, tenant.value(),
                            cause.getClass().getSimpleName());
                }
            } finally {
                if (!handedToEngine) usage.settle(reservation, false, 0, 0);
                if (sessionId != null) {
                    try {
                        // Closes the conversation and drops its working memory: it is never reopened.
                        engine.end(sessionId, new AuthContext(tenant, Map.of(), Optional.of(principal)));
                    } catch (RuntimeException e) {
                        LOG.warn("headless run {}: closing its session failed ({})", runId,
                                e.getClass().getSimpleName());
                    }
                }
            }

            String usageJson = write(usageOf(turn, toolCalls.get()));
            String toolsJson = write(new ArrayList<>(toolsUsed));
            String text = answer.toString().trim();
            if (failure == null && (text.isEmpty() || isOnlyAQuestion(text))) {
                failure = FAIL_NO_ANSWER;
                failureMessage = text.isEmpty() ? "the model wrote no answer" : "the model only asked a question";
            }
            Instant now = clock.instant();
            if (failure != null) {
                store.fail(tenant.value(), runId, failure, failureMessage, usageJson, toolsJson, now);
                LOG.info("headless run {} of tenant {} failed: {}", runId, tenant.value(), failure);
                return;
            }
            store.complete(tenant.value(), runId, cut(text, request.maxAnswerChars()), usageJson, toolsJson, now);
            LOG.info("headless run {} of tenant {} completed ({} rounds, {} tool calls)", runId, tenant.value(),
                    turn.rounds(), toolCalls.get());
        }
    }

    /** The backend refused the run token on a tool call: the run ends there. */
    private static final class PrincipalRejected extends RuntimeException {
        PrincipalRejected() {
            super(null, null, false, false);
        }
    }

    private static Map<String, Object> usageOf(HeadlessTurn turn, int toolCalls) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", turn.inputTokens());
        usage.put("outputTokens", turn.outputTokens());
        usage.put("cacheReadTokens", turn.cacheReadTokens());
        usage.put("rounds", turn.rounds());
        usage.put("toolCalls", toolCalls);
        return usage;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * True when every sentence of {@code text} is a question: the model asked
     * instead of answering, and nobody is there to reply.
     */
    static boolean isOnlyAQuestion(String text) {
        boolean any = false;
        for (String sentence : text.split("(?<=[.!?…])\\s+|\\n+")) {
            String s = sentence.trim();
            if (s.isEmpty()) continue;
            any = true;
            if (!s.endsWith("?")) return false;
        }
        return any;
    }

    /** At most {@code max} characters, ending in «…» when something was left out. */
    static String cut(String text, int max) {
        if (text.length() <= max) return text;
        int end = max - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end).stripTrailing() + "…";
    }

    // ---- restart and housekeeping -----------------------------------------

    /**
     * Runs a previous process left queued or running: their tokens went with
     * it, so they are closed as {@code interrupted} and the caller submits
     * again. This instance is the only one (live sessions are in its memory).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        failUnfinished();
    }

    public int failUnfinished() {
        try {
            int closed = store.failUnfinished(FAIL_INTERRUPTED, "the platform restarted before the run finished",
                    clock.instant());
            if (closed > 0) LOG.warn("headless runs: {} unfinished run(s) closed as interrupted", closed);
            return closed;
        } catch (RuntimeException e) {
            LOG.warn("headless runs: the start-up sweep failed ({})", e.getClass().getSimpleName());
            return 0;
        }
    }

    /** A run that started this long ago is past any deadline it could have been given. */
    Duration staleRunningAfter() {
        return Duration.ofSeconds(DEADLINE_MAX).plus(STALE_MARGIN);
    }

    /**
     * A run queued this long ago has waited longer than a full queue takes
     * when every run ahead of it uses the longest deadline.
     */
    Duration staleQueuedAfter() {
        int workerCount = props.workersOrDefault();
        long turns = (props.queueOrDefault() + workerCount + workerCount - 1L) / workerCount;
        return Duration.ofSeconds(DEADLINE_MAX * turns).plus(STALE_MARGIN);
    }

    /**
     * Erases answers past their 24 hours and rows past their 30 days, and
     * closes as {@code interrupted} the runs whose outcome was never written
     * (they would otherwise count as in flight until the next start).
     */
    @Scheduled(fixedDelayString = "${yaya.agentic.headless.housekeeping-fixed-delay-ms:900000}",
            initialDelayString = "${yaya.agentic.headless.housekeeping-initial-delay-ms:120000}")
    public void housekeep() {
        try {
            Instant now = clock.instant();
            int abandoned = store.failAbandoned(FAIL_INTERRUPTED, "the run did not finish and was closed",
                    now.minus(staleRunningAfter()), now.minus(staleQueuedAfter()), now);
            if (abandoned > 0) LOG.warn("headless runs: {} abandoned run(s) closed as interrupted", abandoned);
            int erased = store.eraseAnswersBefore(now.minus(props.answerTtlOrDefault()));
            int deleted = store.deleteBefore(now.minus(props.rowTtlOrDefault()));
            if (erased > 0 || deleted > 0) {
                LOG.info("headless runs housekeeping: {} answer(s) erased, {} row(s) deleted", erased, deleted);
            }
        } catch (RuntimeException e) {
            LOG.warn("headless runs housekeeping failed ({})", e.getClass().getSimpleName());
        }
    }
}

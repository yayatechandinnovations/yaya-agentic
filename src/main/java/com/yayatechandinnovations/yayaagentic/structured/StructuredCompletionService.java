package com.yayatechandinnovations.yayaagentic.structured;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient.StructuredLlmException;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient.StructuredRequest;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient.StructuredResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The whole of {@code POST /v1/structured-completions} after admission:
 * validate the request, meter it against the tenant's rate limit, make one
 * model call that must answer in the caller's JSON Schema, validate the answer
 * (one retry on violation), and account for it. Stateless — no session, no
 * conversation, no tool execution, nothing persisted.
 *
 * <p>Prompt, input and answer text never reach a log line or a metric tag.</p>
 */
@Service
public class StructuredCompletionService {

    /** The single tool the model answers through; its input schema is the caller's schema. */
    public static final String TOOL_NAME = "emit_result";

    static final int MAX_SYSTEM_CHARS = 200_000;
    static final int MAX_INPUT_CHARS = 8_000;
    static final int DEFAULT_MAX_OUTPUT_TOKENS = 1500;
    static final int MIN_OUTPUT_TOKENS = 64;
    static final int MAX_OUTPUT_TOKENS = 4096;
    private static final int MAX_ATTEMPTS = 2;
    private static final int MAX_DETAIL_CHARS = 600;
    private static final Pattern TASK = Pattern.compile("^[a-z0-9-]{1,64}$");

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(StructuredCompletionService.class);

    private final List<StructuredLlmClient> clients;
    private final String provider;
    private final ChatRateLimiter rateLimiter;
    private final StructuredCompletionMetrics metrics;
    private final StructuredCompletionsProperties props;
    private final ObjectMapper json;
    private final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    public StructuredCompletionService(List<StructuredLlmClient> clients,
                                       YayaAgenticProperties agentic,
                                       ChatRateLimiter rateLimiter,
                                       StructuredCompletionMetrics metrics,
                                       StructuredCompletionsProperties props,
                                       ObjectMapper json) {
        this.clients = clients;
        this.provider = agentic.llm() == null ? null : agentic.llm().provider();
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.props = props;
        this.json = json;
    }

    /** A successful answer. Usage is summed over every model call made for it. */
    public record Completion(Map<String, Object> output, long inputTokens, long outputTokens, String model) {
    }

    /**
     * Runs one request for an already-authenticated tenant. Errors surface as
     * {@link StructuredCompletionException} only.
     *
     * @param body the raw request body (JSON)
     */
    public Mono<Completion> complete(String tenant, byte[] body) {
        long startNanos = System.nanoTime();
        Tally tally = new Tally();
        return Mono.fromCallable(() -> run(tenant, body, tally))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(props.timeoutOrDefault())
                .onErrorMap(e -> !(e instanceof StructuredCompletionException), e -> {
                    // Class name only: exception messages may quote request content.
                    LOG.warn("structured-completion tenant={} failed with {}", tenant, e.getClass().getSimpleName());
                    return StructuredCompletionException.upstreamFailure();
                })
                .doOnSuccess(c -> record(tenant, tally, "ok", startNanos))
                .doOnError(e -> record(tenant, tally, ((StructuredCompletionException) e).code(), startNanos));
    }

    /** Accounts for a request refused before it reached {@link #complete} (401 / 403 / unreadable body). */
    public void recordRejected(String tenant, String outcome) {
        record(tenant, new Tally(), outcome, System.nanoTime());
    }

    // ---- The pipeline (blocking) ---------------------------------------

    private Completion run(String tenant, byte[] body, Tally tally) {
        Request request = parse(body);
        tally.task = request.task();
        JsonSchema schema = compile(request.schema());

        StructuredLlmClient client = clients.stream()
                .filter(c -> c.provider().equals(provider))
                .findFirst()
                .orElseThrow(StructuredCompletionException::llmUnavailable);

        requireWithinRateLimit(tenant);

        String violation = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            StructuredResult result;
            try {
                result = client.complete(new StructuredRequest(request.system(), request.input(), TOOL_NAME,
                        request.schemaJson(), request.maxOutputTokens(), violation));
            } catch (StructuredLlmException e) {
                LOG.warn("structured-completion tenant={} task={} provider call failed: {}",
                        tenant, request.task(), e.getMessage());
                throw StructuredCompletionException.upstreamFailure();
            }
            tally.add(result, attempt);
            violation = violationOf(schema, result);
            if (violation == null) {
                return new Completion(result.output(), tally.inputTokens, tally.outputTokens, tally.model);
            }
        }
        throw StructuredCompletionException.schemaViolation(violation);
    }

    private void requireWithinRateLimit(String tenant) {
        ChatRateLimiter.Decision decision;
        try {
            decision = rateLimiter.hit("structured", tenant, props.perMinuteOrDefault(), Duration.ofMinutes(1));
        } catch (RuntimeException e) {
            // Redis down: the limiter is a cost guard on an authenticated,
            // per-tenant service call — do not turn its outage into ours.
            LOG.warn("structured-completion rate limiter unavailable ({}); allowing", e.getClass().getSimpleName());
            return;
        }
        if (!decision.allowed()) throw StructuredCompletionException.rateLimited(decision.retryAfterSeconds());
    }

    // ---- Request validation --------------------------------------------

    record Request(String task, String system, String input, JsonNode schema, String schemaJson,
                   int maxOutputTokens) {
    }

    Request parse(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception e) {
            throw StructuredCompletionException.invalidRequest("body is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw StructuredCompletionException.invalidRequest("body must be a JSON object");
        }

        String task = text(root, "task", true);
        if (!TASK.matcher(task).matches()) {
            throw StructuredCompletionException.invalidRequest("task must match ^[a-z0-9-]{1,64}$");
        }
        String system = text(root, "system", false);
        if (system.length() > MAX_SYSTEM_CHARS) {
            throw StructuredCompletionException.invalidRequest("system exceeds " + MAX_SYSTEM_CHARS + " characters");
        }
        String input = text(root, "input", true);
        if (input.isBlank()) throw StructuredCompletionException.invalidRequest("input is required");
        if (input.length() > MAX_INPUT_CHARS) {
            throw StructuredCompletionException.invalidRequest("input exceeds " + MAX_INPUT_CHARS + " characters");
        }

        JsonNode schema = root.get("schema");
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText(null))) {
            throw StructuredCompletionException.invalidRequest(
                    "schema is required and must be a JSON Schema object with \"type\":\"object\"");
        }
        requireSelfContained(schema);

        int maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
        JsonNode max = root.get("maxOutputTokens");
        if (max != null && !max.isNull()) {
            if (!max.isIntegralNumber()) {
                throw StructuredCompletionException.invalidRequest("maxOutputTokens must be an integer");
            }
            maxOutputTokens = (int) Math.max(MIN_OUTPUT_TOKENS, Math.min(MAX_OUTPUT_TOKENS, max.asLong()));
        }
        return new Request(task, system, input, schema, schema.toString(), maxOutputTokens);
    }

    private static String text(JsonNode root, String field, boolean required) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            if (required) throw StructuredCompletionException.invalidRequest(field + " is required");
            return "";
        }
        if (!node.isTextual()) throw StructuredCompletionException.invalidRequest(field + " must be a string");
        return node.asText();
    }

    /**
     * The schema is caller-supplied and gets compiled here, so it must not be
     * able to make this service fetch anything: only in-document references
     * ({@code #…}) and the standard json-schema.org dialects are accepted.
     */
    private static void requireSelfContained(JsonNode node) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String name = field.getKey();
                JsonNode value = field.getValue();
                if ((name.equals("$ref") || name.equals("$dynamicRef") || name.equals("$recursiveRef"))
                        && value.isTextual() && !value.asText().startsWith("#")) {
                    throw StructuredCompletionException.invalidRequest(
                            "schema may only use in-document references (" + name + " must start with '#')");
                }
                if (name.equals("$schema") && value.isTextual()
                        && !value.asText().matches("^https?://json-schema\\.org/.*")) {
                    throw StructuredCompletionException.invalidRequest("schema uses an unsupported $schema dialect");
                }
                requireSelfContained(value);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) requireSelfContained(item);
        }
    }

    private JsonSchema compile(JsonNode schema) {
        try {
            JsonSchema compiled = schemas.getSchema(schema);
            compiled.initializeValidators();
            return compiled;
        } catch (RuntimeException e) {
            throw StructuredCompletionException.invalidRequest("schema is not a usable JSON Schema");
        }
    }

    // ---- Answer validation ---------------------------------------------

    /** {@code null} when the answer satisfies the schema, else a short, caller-safe description. */
    private String violationOf(JsonSchema schema, StructuredResult result) {
        if (result.output() == null) {
            return result.truncated()
                    ? "the model's answer was cut off at maxOutputTokens"
                    : "the model did not return a structured result";
        }
        Set<ValidationMessage> messages = schema.validate(json.valueToTree(result.output()));
        if (messages.isEmpty()) return null;
        StringBuilder detail = new StringBuilder();
        for (ValidationMessage m : messages) {
            if (detail.length() > 0) detail.append("; ");
            detail.append(m.getMessage());
            if (detail.length() >= MAX_DETAIL_CHARS) break;
        }
        return detail.length() > MAX_DETAIL_CHARS ? detail.substring(0, MAX_DETAIL_CHARS) : detail.toString();
    }

    // ---- Accounting -----------------------------------------------------

    /** Mutable per-request totals, written by the worker and read once at the end. */
    private static final class Tally {
        volatile String task;
        volatile String model;
        volatile long inputTokens;
        volatile long outputTokens;
        volatile long cacheReadTokens;
        volatile int attempts;

        void add(StructuredResult r, int attempt) {
            attempts = attempt;
            inputTokens += r.inputTokens();
            outputTokens += r.outputTokens();
            cacheReadTokens += r.cacheReadTokens();
            if (r.model() != null) model = r.model();
        }
    }

    private void record(String tenant, Tally tally, String outcome, long startNanos) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        // task is only set once it passed validation, so it is safe to log.
        LOG.info("structured-completion tenant={} task={} model={} inputTokens={} outputTokens={} "
                        + "cacheReadTokens={} attempts={} latencyMs={} outcome={}",
                tenant == null ? "-" : tenant, tally.task == null ? "-" : tally.task,
                tally.model == null ? "-" : tally.model, tally.inputTokens, tally.outputTokens,
                tally.cacheReadTokens, tally.attempts, elapsed.toMillis(), outcome);
        try {
            metrics.record(tenant, tally.task, outcome, tally.inputTokens, tally.outputTokens, elapsed);
        } catch (RuntimeException e) {
            // Telemetry only — never fail a request over a meter.
        }
    }
}

package com.yayatechandinnovations.yayaagentic.engine.suggest;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.engine.QuickReplies;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient.StructuredRequest;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient.StructuredResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The reply suggester (assistant-channels design §2.5): when the assistant
 * ended its turn with a question that no rule, {@code _askUser} or frame
 * answered, one extra structured model call — no tools, no history, no tool
 * data — proposes up to three short answers as the user would say them. The
 * engine validates the answer and emits it as {@code quick_replies
 * {source:"suggester"}}; the model never renders UI (non-negotiable #8).
 *
 * <p>Any failure or a timeout means no chips: never a retry, never an error to
 * the user. The call is not a message in the monthly allowance; its tokens
 * are added to the turn's meter by the caller.</p>
 */
@Component
public class ReplySuggester {

    private static final Logger log = LoggerFactory.getLogger(ReplySuggester.class);

    /** The answer tool's name; the stub client recognises it and answers canned. */
    public static final String TOOL_NAME = "suggest_replies";

    public static final int MAX_OPTIONS = 3;
    public static final int MAX_LABEL_CHARS = QuickReplies.LABEL_MAX;
    public static final int MAX_ASSISTANT_CHARS = 1200;
    static final int MAX_USER_CHARS = 400;
    private static final int MAX_OUTPUT_TOKENS = 120;

    static final String SCHEMA_JSON = """
            {"type":"object","required":["kind","options"],"properties":{
              "kind":{"type":"string","enum":["none","yes_no","choice"]},
              "options":{"type":"array","items":{"type":"string"},"maxItems":3}}}""";

    private static final String SYSTEM = """
            You propose tappable reply chips for the user of a chat assistant. You are given the user's last \
            message and the assistant's reply, which ends with a question. Answer ONLY through the tool.
            Rules:
            - kind "yes_no" when the question is answered with yes or no; options empty.
            - kind "choice" when the question offers or implies concrete alternatives: list them as the user \
            would say them, at most 3, each at most 20 characters, in the conversation's language.
            - kind "none" when the question asks for free text (a number, a name, a date, a reason) or you are \
            not sure.
            Never invent alternatives the assistant did not mention or imply. Never add questions or explanations.""";

    /** A validated suggestion: the options to show and what the call cost. */
    public record Suggestion(List<QuickReplies.Option> options, LlmClient.Usage usage) {}

    private final List<StructuredLlmClient> clients;
    private final String provider;
    private final SuggestProperties properties;

    public ReplySuggester(List<StructuredLlmClient> clients, YayaAgenticProperties agentic,
                          SuggestProperties properties) {
        this.clients = clients == null ? List.of() : clients;
        this.provider = agentic == null || agentic.llm() == null ? null : agentic.llm().provider();
        this.properties = properties == null ? SuggestProperties.defaults() : properties;
    }

    public boolean enabled() {
        return properties.isEnabled() && !clients.isEmpty();
    }

    /** True when the text's last sentence is a question. */
    public static boolean endsWithQuestion(String text) {
        if (text == null) return false;
        String t = text.strip();
        // Trailing markdown or a closing quote after the question mark still counts.
        t = t.replaceAll("[*_)\\]\"»']+$", "").strip();
        return t.endsWith("?");
    }

    /**
     * Suggest, within the configured timeout, off the caller's thread. Empty
     * on {@code none}, on an invalid answer, on any error, or when disabled.
     */
    public Mono<Suggestion> suggest(String lastUserText, String assistantText, String language) {
        if (!enabled() || assistantText == null || assistantText.isBlank()) return Mono.empty();
        Duration timeout = properties.effectiveTimeout();
        return Mono.fromCallable(() -> suggestNow(lastUserText, assistantText, language))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty))
                .timeout(timeout)
                .onErrorResume(e -> {
                    log.debug("reply suggester gave nothing ({}): {}", e.getClass().getSimpleName(), e.getMessage());
                    return Mono.empty();
                });
    }

    /** The blocking call, for the reactive wrapper above and for tests. */
    public Optional<Suggestion> suggestNow(String lastUserText, String assistantText, String language) {
        StructuredLlmClient client = clientFor(provider);
        if (client == null) return Optional.empty();
        StructuredRequest request = new StructuredRequest(SYSTEM, input(lastUserText, assistantText, language),
                TOOL_NAME, SCHEMA_JSON, MAX_OUTPUT_TOKENS, null, properties.modelOrNull());
        StructuredResult result = client.complete(request);
        if (result == null || result.output() == null || result.truncated()) return Optional.empty();
        List<QuickReplies.Option> options = validate(result.output(), language);
        if (options.isEmpty()) return Optional.empty();
        return Optional.of(new Suggestion(options, new LlmClient.Usage(result.inputTokens(), result.outputTokens(),
                result.cacheReadTokens(), result.cacheCreationTokens())));
    }

    static String input(String lastUserText, String assistantText, String language) {
        String user = lastUserText == null ? "" : lastUserText.strip();
        if (user.length() > MAX_USER_CHARS) user = user.substring(0, MAX_USER_CHARS);
        String assistant = assistantText == null ? "" : assistantText.strip();
        // The question is at the end: keep the tail.
        if (assistant.length() > MAX_ASSISTANT_CHARS) {
            assistant = assistant.substring(assistant.length() - MAX_ASSISTANT_CHARS);
        }
        return "Language: " + (language == null || language.isBlank() ? "es" : language)
                + "\n\nUser: " + user + "\n\nAssistant: " + assistant;
    }

    /**
     * The engine-side validation (§2.5): trim, drop empties and duplicates,
     * cut at three, refuse the WHOLE suggestion when any label is over 20
     * characters, {@code yes_no} → the fixed «Sí» / «No», {@code none} → nothing.
     */
    public static List<QuickReplies.Option> validate(Map<String, Object> output, String language) {
        if (output == null) return List.of();
        String kind = output.get("kind") == null ? "" : String.valueOf(output.get("kind")).strip()
                .toLowerCase(Locale.ROOT);
        switch (kind) {
            case "yes_no":
                return QuickReplies.yesNo(language);
            case "choice":
                break;
            default:
                return List.of();
        }
        if (!(output.get("options") instanceof List<?> raw)) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<String> labels = new ArrayList<>();
        for (Object o : raw) {
            if (o == null) continue;
            String label = String.valueOf(o).strip().replaceAll("\\s+", " ");
            if (label.isEmpty()) continue;
            if (label.codePointCount(0, label.length()) > MAX_LABEL_CHARS) return List.of();
            if (!seen.add(label.toLowerCase(Locale.ROOT))) continue;
            labels.add(label);
            if (labels.size() == MAX_OPTIONS) break;
        }
        if (labels.isEmpty()) return List.of();
        // Two chips that are just yes and no are the fixed pair, whatever the model wrote.
        if (labels.size() == 2 && isYesNo(labels)) return QuickReplies.yesNo(language);
        List<QuickReplies.Option> options = new ArrayList<>();
        for (String l : labels) options.add(QuickReplies.Option.of(l));
        return options;
    }

    private static boolean isYesNo(List<String> labels) {
        String a = labels.get(0).toLowerCase(Locale.ROOT).replaceAll("[^a-zí]", "");
        String b = labels.get(1).toLowerCase(Locale.ROOT).replaceAll("[^a-zí]", "");
        return (a.equals("sí") || a.equals("si") || a.equals("yes")) && b.equals("no");
    }

    private StructuredLlmClient clientFor(String provider) {
        if (clients.isEmpty()) return null;
        if (provider != null) {
            for (StructuredLlmClient c : clients) if (provider.equals(c.provider())) return c;
        }
        return clients.get(0);
    }
}

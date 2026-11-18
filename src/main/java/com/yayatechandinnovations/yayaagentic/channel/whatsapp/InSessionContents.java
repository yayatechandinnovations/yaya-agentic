package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.ProviderCreds;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioCreds;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioMessagingChannelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic, variable-driven Twilio Content resources for native WhatsApp
 * buttons inside the 24 h window (GAP-W3 of whatsapp-ops-integration-design.md).
 * WhatsApp only renders buttons through Content; in-session content needs no
 * Meta approval, so one resource per shape (k quick replies, with or without a
 * media header) serves every message: body, titles, payloads and media path
 * are all send-time variables. Resolved lazily per Twilio account (WABA
 * subaccounts each own their content) and cached for the process lifetime.
 *
 * <p>Variable layout — quick reply: body {{1}}, button i title {{2+2i}} /
 * payload {{3+2i}}. Media card: body {{1}}, media path {{2}}, button i title
 * {{3+2i}} / payload {{4+2i}}.
 */
@Component
public class InSessionContents {

    private static final Logger log = LoggerFactory.getLogger(InSessionContents.class);

    /** Bump when a definition changes so accounts get a fresh resource. */
    private static final String VERSION = "v1";
    public static final int MAX_BUTTONS = 3;
    /** WhatsApp's cap on list-picker rows. */
    public static final int MAX_LIST_ITEMS = 10;
    public static final int MAX_BODY_CHARS = 1024;

    private final TwilioMessagingChannelProvider twilio;
    private final String publicBaseUrl;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public InSessionContents(TwilioMessagingChannelProvider twilio,
                             @Value("${yaya.agentic.channels.twilio.webhook-base-url:${yaya.agentic.public-base-url:}}")
                             String publicBaseUrl) {
        this.twilio = twilio;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    public Optional<String> quickReply(int buttons, ProviderCreds creds) {
        if (buttons < 1 || buttons > MAX_BUTTONS) return Optional.empty();
        String name = "yaya_insession_qr" + buttons + "_" + VERSION;
        return resolve(name, creds, () -> {
            List<Map<String, Object>> actions = new ArrayList<>();
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("1", "Mensaje");
            for (int i = 0; i < buttons; i++) {
                actions.add(Map.of("title", "{{" + (2 + 2 * i) + "}}", "id", "{{" + (3 + 2 * i) + "}}"));
                vars.put(String.valueOf(2 + 2 * i), "Opción " + (i + 1));
                vars.put(String.valueOf(3 + 2 * i), "opt" + (i + 1));
            }
            return definition(name, vars, Map.of(
                    "twilio/quick-reply", Map.of("body", "{{1}}", "actions", actions),
                    "twilio/text", Map.of("body", "{{1}}")));
        });
    }

    /**
     * A native list picker ({@code twilio/list-picker}, assistant-channels
     * design §2.6) for 4–10 options or a label too long for a button: body
     * {{1}}, button text {{2}}, row i title {{3+3i}} / id {{4+3i}} /
     * description {{5+3i}}. In-session content like {@link #quickReply}: no
     * Meta approval, one resource per row count.
     */
    public Optional<String> listPicker(int items, ProviderCreds creds) {
        if (items < 1 || items > MAX_LIST_ITEMS) return Optional.empty();
        String name = "yaya_insession_list" + items + "_" + VERSION;
        return resolve(name, creds, () -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("1", "Mensaje");
            vars.put("2", "Elegir");
            for (int i = 0; i < items; i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("item", "{{" + (3 + 3 * i) + "}}");
                row.put("id", "{{" + (4 + 3 * i) + "}}");
                row.put("description", "{{" + (5 + 3 * i) + "}}");
                rows.add(row);
                vars.put(String.valueOf(3 + 3 * i), "Opción " + (i + 1));
                vars.put(String.valueOf(4 + 3 * i), "opt" + (i + 1));
                vars.put(String.valueOf(5 + 3 * i), "Descripción " + (i + 1));
            }
            Map<String, Object> picker = new LinkedHashMap<>();
            picker.put("body", "{{1}}");
            picker.put("button", "{{2}}");
            picker.put("items", rows);
            return definition(name, vars, Map.of(
                    "twilio/list-picker", picker,
                    "twilio/text", Map.of("body", "{{1}}")));
        });
    }

    /** Send-time variables for {@link #listPicker}: each row as {title, id, description}. */
    public static Map<String, String> listPickerVariables(String body, String button, List<String[]> rows) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", body);
        vars.put("2", button);
        for (int i = 0; i < rows.size(); i++) {
            vars.put(String.valueOf(3 + 3 * i), rows.get(i)[0]);
            vars.put(String.valueOf(4 + 3 * i), rows.get(i)[1]);
            vars.put(String.valueOf(5 + 3 * i), rows.get(i)[2]);
        }
        return vars;
    }

    public Optional<String> mediaCard(int buttons, ProviderCreds creds) {
        // A buttonless card is refused at send time (Twilio 21656); plain media with a caption carries it.
        if (buttons < 1 || buttons > MAX_BUTTONS || publicBaseUrl.isBlank()) return Optional.empty();
        String name = "yaya_insession_card" + buttons + "_" + VERSION;
        return resolve(name, creds, () -> {
            List<Map<String, Object>> actions = new ArrayList<>();
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("1", "Mensaje");
            vars.put("2", "sample/sample.pdf");
            for (int i = 0; i < buttons; i++) {
                actions.add(Map.of("type", "QUICK_REPLY",
                        "title", "{{" + (3 + 2 * i) + "}}", "id", "{{" + (4 + 2 * i) + "}}"));
                vars.put(String.valueOf(3 + 2 * i), "Opción " + (i + 1));
                vars.put(String.valueOf(4 + 2 * i), "opt" + (i + 1));
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("title", "{{1}}");
            card.put("media", List.of(publicBaseUrl + "/v1/media/{{2}}"));
            if (!actions.isEmpty()) card.put("actions", actions);
            return definition(name, vars, Map.of("twilio/card", card));
        });
    }

    /** Send-time variables for {@link #quickReply}. */
    public static Map<String, String> quickReplyVariables(String body, List<String[]> titleAndPayload) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", body);
        for (int i = 0; i < titleAndPayload.size(); i++) {
            vars.put(String.valueOf(2 + 2 * i), titleAndPayload.get(i)[0]);
            vars.put(String.valueOf(3 + 2 * i), titleAndPayload.get(i)[1]);
        }
        return vars;
    }

    /** Send-time variables for {@link #mediaCard}. */
    public static Map<String, String> mediaCardVariables(String body, String mediaPath,
                                                         List<String[]> titleAndPayload) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", body);
        vars.put("2", mediaPath);
        for (int i = 0; i < titleAndPayload.size(); i++) {
            vars.put(String.valueOf(3 + 2 * i), titleAndPayload.get(i)[0]);
            vars.put(String.valueOf(4 + 2 * i), titleAndPayload.get(i)[1]);
        }
        return vars;
    }

    private Optional<String> resolve(String name, ProviderCreds creds,
                                     java.util.function.Supplier<Map<String, Object>> definition) {
        String account = creds instanceof TwilioCreds tc ? tc.accountSid() : "default";
        String key = account + "|" + name;
        String cached = cache.get(key);
        if (cached != null) return Optional.of(cached);
        try {
            String sid = twilio.findContentByName(name, creds)
                    .orElseGet(() -> twilio.createContent(definition.get(), creds));
            cache.put(key, sid);
            return Optional.of(sid);
        } catch (RuntimeException e) {
            log.warn("In-session content {} unavailable for account {}: {}", name, account, e.getMessage());
            return Optional.empty();
        }
    }

    private static Map<String, Object> definition(String name, Map<String, String> vars, Map<String, Object> types) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("friendly_name", name);
        def.put("language", "es");
        def.put("variables", vars);
        def.put("types", types);
        return def;
    }
}

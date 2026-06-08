package com.yayatechandinnovations.yayaagentic.operator_auth.delegate;

import com.fasterxml.jackson.core.io.JsonStringEncoder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Pure substitution of {@code {{username}}}, {@code {{password}}}, and
 * {@code {{basic}}} placeholders. Escaping is format-aware:
 *
 * <ul>
 *   <li>{@link RequestShape.BodyFormat#JSON} → JSON-string escape
 *       (quotes, backslashes, control chars).</li>
 *   <li>{@link RequestShape.BodyFormat#FORM} → percent-encode.</li>
 *   <li>{@link RequestShape.BodyFormat#BASIC_AUTH} /
 *       {@link RequestShape.BodyFormat#NONE} → no body produced;
 *       {@link #renderHeaderValue(String, String, char[])} substitutes
 *       header templates with no body-format escaping.</li>
 * </ul>
 *
 * Operators cannot disable escaping — that's exactly the JSON-injection
 * footgun called out in design §13.
 */
public final class CredentialTemplate {

    private CredentialTemplate() {}

    private static final JsonStringEncoder JSON_ESCAPER = JsonStringEncoder.getInstance();

    public static String renderBody(RequestShape.BodyFormat format,
                                    String template,
                                    String username,
                                    char[] password) {
        if (format == null || format == RequestShape.BodyFormat.NONE
                || format == RequestShape.BodyFormat.BASIC_AUTH) {
            return null;
        }
        if (template == null || template.isEmpty()) {
            template = switch (format) {
                case JSON -> "{\"username\":\"{{username}}\",\"password\":\"{{password}}\"}";
                case FORM -> "username={{username}}&password={{password}}";
                default -> "";
            };
        }
        return substitute(template, username, password, format);
    }

    public static String renderHeaderValue(String template, String username, char[] password) {
        if (template == null || template.isEmpty()) return template;
        // Headers don't apply body-format escaping; we still substitute
        // {{basic}} (handy for Authorization: Basic {{basic}}).
        return substitute(template, username, password, null);
    }

    public static String basicAuthHeader(String username, char[] password) {
        String pair = username + ":" + new String(password);
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------

    private static String substitute(String template, String username, char[] password,
                                     RequestShape.BodyFormat format) {
        String userEsc = escape(username == null ? "" : username, format);
        String passEsc = escape(password == null ? "" : new String(password), format);
        String basicEsc = basicAuth(username, password);
        return template
                .replace("{{username}}", userEsc)
                .replace("{{password}}", passEsc)
                .replace("{{basic}}", basicEsc);
    }

    private static String escape(String value, RequestShape.BodyFormat format) {
        if (format == null) return stripCtl(value);
        return switch (format) {
            case JSON -> new String(JSON_ESCAPER.quoteAsString(value));
            case FORM -> URLEncoder.encode(value, StandardCharsets.UTF_8);
            case BASIC_AUTH, NONE -> stripCtl(value);
        };
    }

    private static String basicAuth(String username, char[] password) {
        String u = username == null ? "" : username;
        String p = password == null ? "" : new String(password);
        return Base64.getEncoder().encodeToString((u + ":" + p).getBytes(StandardCharsets.UTF_8));
    }

    /** Defence-in-depth for headers: strip CR/LF to prevent header injection. */
    private static String stripCtl(String s) {
        if (s == null) return null;
        return s.replace("\r", "").replace("\n", "");
    }
}

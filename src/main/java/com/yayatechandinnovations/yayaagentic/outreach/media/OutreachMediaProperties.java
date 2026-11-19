package com.yayatechandinnovations.yayaagentic.outreach.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * What an integration sender may attach to an outreach message — bound from
 * {@code yaya.agentic.outreach.media}. Its own allow-list, apart from the
 * knowledge-file one: a ticket copy is a PNG the chat shows inline, which a
 * knowledge corpus never takes, while a spreadsheet the corpus takes has no
 * place on a WhatsApp card. Images carry WhatsApp's own 5 MiB ceiling; every
 * other type keeps the knowledge per-file cap.
 */
@ConfigurationProperties(prefix = "yaya.agentic.outreach.media")
public record OutreachMediaProperties(
        List<String> allowedContentTypes,
        Long maxImageBytes
) {
    private static final long MIB = 1024L * 1024L;

    public OutreachMediaProperties {
        if (allowedContentTypes == null || allowedContentTypes.isEmpty()) {
            allowedContentTypes = List.of("application/pdf", "image/png", "image/jpeg");
        }
        allowedContentTypes = allowedContentTypes.stream()
                .filter(ct -> ct != null && !ct.isBlank())
                .map(ct -> ct.trim().toLowerCase(Locale.ROOT))
                .toList();
        if (maxImageBytes == null || maxImageBytes <= 0) maxImageBytes = 5L * MIB;
    }

    public boolean isAllowed(String contentType) {
        String ct = bare(contentType);
        return ct != null && allowedContentTypes.contains(ct);
    }

    public static boolean isImage(String contentType) {
        String ct = bare(contentType);
        return ct != null && ct.startsWith("image/");
    }

    private static String bare(String contentType) {
        if (contentType == null) return null;
        String ct = contentType.toLowerCase(Locale.ROOT);
        int semi = ct.indexOf(';');
        if (semi >= 0) ct = ct.substring(0, semi);
        return ct.trim();
    }
}

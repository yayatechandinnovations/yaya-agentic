package com.yayatechandinnovations.yayaagentic.channel.chat;

import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.persistence.ChatLogoEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatLogoRepository;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stores and serves entry-point logos ({@code chat-channel-design.md} §13). The
 * platform re-hosts the bytes so the chat app never hot-links a tenant URL and
 * the content type is validated (XSS / SVG-script surface). Logos are small, so
 * they are accepted as base64 JSON rather than multipart.
 */
@Service
public class ChatLogoService {

    /** SVG is intentionally excluded — it can carry script. */
    private static final Set<String> ALLOWED = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif");
    private static final int MAX_BYTES = 2_097_152; // 2 MiB

    private final ChatLogoRepository repo;
    private final String publicBaseUrl;

    public ChatLogoService(ChatLogoRepository repo, YayaAgenticProperties props) {
        this.repo = repo;
        this.publicBaseUrl = props == null ? null : props.publicBaseUrl();
    }

    public Stored store(TenantId tenant, String contentType, String dataBase64) {
        String ct = contentType == null ? "" : contentType.trim().toLowerCase();
        if (!ALLOWED.contains(ct)) {
            throw AdminApiException.badRequest("bad_logo_type",
                    "logo must be PNG, JPEG, WebP, or GIF");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(stripDataUrlPrefix(dataBase64));
        } catch (IllegalArgumentException e) {
            throw AdminApiException.badRequest("bad_logo_data", "logo data is not valid base64");
        }
        if (bytes.length == 0) throw AdminApiException.badRequest("empty_logo", "logo is empty");
        if (bytes.length > MAX_BYTES) {
            throw AdminApiException.badRequest("logo_too_large", "logo exceeds 2 MB");
        }
        String id = "logo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        repo.save(new ChatLogoEntity(id, tenant.value(), ct, bytes));
        return new Stored(id, urlFor(id));
    }

    public Optional<ChatLogoEntity> find(String id) {
        return id == null ? Optional.empty() : repo.findById(id);
    }

    /** Absolute when a public base URL is configured, else a root-relative path
     *  the client resolves against its own API base. */
    public String urlFor(String id) {
        String path = "/v1/chat/logos/" + id;
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) return path;
        return publicBaseUrl.replaceAll("/+$", "") + path;
    }

    private static String stripDataUrlPrefix(String s) {
        if (s == null) return "";
        int comma = s.indexOf(',');
        return (s.startsWith("data:") && comma >= 0) ? s.substring(comma + 1) : s;
    }

    public record Stored(String logoId, String url) {}
}

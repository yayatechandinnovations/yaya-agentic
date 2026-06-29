package com.yayatechandinnovations.yayaagentic.auth.anon;

import com.yayatechandinnovations.yayaagentic.channel.chat.verify.VerifiedDevice;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mints the principal for a phone-verified chat device ({@code chat-channel-design.md} §20) —
 * the chat twin of {@code WhatsAppPhoneAuthenticator}. The subject is the
 * verified phone ({@code chat:+E164}); the phone reaches the LLM/tenant only via
 * EntityMemory, never auto-projected, and the {@code name} is flagged
 * self-asserted (the user typed it; only the phone is platform-verified).
 */
@Component
public class VerifiedDeviceAuthenticator {

    public static final Set<String> BASELINE_SCOPES =
            Set.of("channel:chat", "channel:read", "phone:verified");

    public Principal mint(Ids.TenantId tenant, VerifiedDevice device, String handle) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("channel", "chat");
        claims.put("phone", device.phoneE164());
        claims.put("phoneVerified", true);
        if (device.name() != null && !device.name().isBlank()) {
            claims.put("name", device.name());
            claims.put("nameSelfAsserted", true);
        }
        if (device.deviceId() != null) claims.put("deviceId", device.deviceId());
        if (handle != null) claims.put("entryPoint", handle);
        return new Principal(
                "chat:" + device.phoneE164(),
                tenant,
                BASELINE_SCOPES,
                Map.copyOf(claims),
                Instant.now());
    }
}

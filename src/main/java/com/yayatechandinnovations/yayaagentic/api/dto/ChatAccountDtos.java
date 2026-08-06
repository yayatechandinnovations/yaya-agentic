package com.yayatechandinnovations.yayaagentic.api.dto;

import java.util.List;

/**
 * Wire DTOs for end-user account self-service ({@code /v1/chat/account/*},
 * {@code chat-account-self-service-design.md} §8). All of these ride behind a
 * valid device pass — the caller is always the account holder.
 */
public final class ChatAccountDtos {

    private ChatAccountDtos() {}

    /** {@code GET /v1/chat/account}. {@code pinSet} drives the Security screen's
     *  set-vs-change affordance ({@code chat-experience-design.md} §5.5). */
    public record AccountView(String phoneE164, String name, String createdAt, boolean pinSet) {}

    /* ---- PIN (chat-experience-design.md §5.4) ---- */

    /** Set or change. {@code currentPin} is required only when one is already set. */
    public record PinSetRequest(String currentPin, String pin) {}

    /** Remove. The current PIN is always required — the weaker factor may not be
     *  stripped by someone who doesn't know it. */
    public record PinRemoveRequest(String currentPin) {}

    /** A PIN change flips the pass TTL regime, so a fresh pass rides back. */
    public record PinResponse(String devicePass, boolean pinSet) {}

    /* ---- devices (chat-experience-design.md §5.4) ---- */

    public record DeviceView(String deviceId, String label, boolean pinRequired,
                             boolean current, String createdAt, String lastSeenAt) {}

    public record DevicesResponse(List<DeviceView> devices) {}

    /** Toggling PIN OFF for a device is a step-up: the current PIN must ride
     *  along, so a snatched unlocked phone can't self-trust. */
    public record DeviceUpdateRequest(Boolean pinRequired, String currentPin) {}

    /** Lazy migration for pass-holding apps predating device enrollment: mints
     *  this device's secret without another OTP. */
    public record DeviceEnrollResponse(String deviceSecret, boolean pinRequired, boolean pinSet) {}

    /* ---- name (§6.1) ---- */

    public record RenameRequest(String name) {}

    /** A rename re-mints the pass so the new name reaches the profile next session. */
    public record RenameResponse(String devicePass, String name) {}

    /* ---- phone change (§6.2) ---- */

    public record PhoneChangeStartRequest(String newPhoneE164) {}

    public record PhoneChangeStartResponse(String changeId, String state) {}

    /** Body for verify-old / verify-new / resend / abort. */
    public record PhoneChangeCodeRequest(String changeId, String code) {}

    public record PhoneChangeStateResponse(String state) {}

    /** Success of the ceremony: the migrated phone + a fresh device pass (the old
     *  device's pass was revoked by the generation bump). */
    public record PhoneChangeCompleteResponse(String devicePass, String phoneE164) {}

    /* ---- delete (§6.3) ---- */

    public record DeleteConfirmRequest(String code) {}

    public record StateResponse(String state) {}

    /* ---- tiles (§7.1) — addressed by public handle, never the entry-point id ---- */

    public record TilesResponse(List<PublicChatDtos.EntryPointCard> tiles) {}

    /* ---- receipts wallet (chat-experience-design.md §7.5) ---- */

    /** One issued receipt: the template snapshot pinned at issuance + its data. */
    public record ReceiptView(String id, String handle, String templateId,
                              java.util.Map<String, Object> template,
                              java.util.Map<String, Object> data,
                              String createdAt) {}

    public record ReceiptsResponse(List<ReceiptView> receipts, String nextCursor) {}
}

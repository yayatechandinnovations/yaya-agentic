package com.yayatechandinnovations.yayaagentic.api.dto;

import java.util.List;

/**
 * Wire DTOs for end-user account self-service ({@code /v1/chat/account/*},
 * {@code chat-account-self-service-design.md} §8). All of these ride behind a
 * valid device pass — the caller is always the account holder.
 */
public final class ChatAccountDtos {

    private ChatAccountDtos() {}

    /** {@code GET /v1/chat/account}. */
    public record AccountView(String phoneE164, String name, String createdAt) {}

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
}

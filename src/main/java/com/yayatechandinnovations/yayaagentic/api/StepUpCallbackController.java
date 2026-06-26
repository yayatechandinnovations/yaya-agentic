package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpCoordinator;
import com.yayatechandinnovations.yayaagentic.auth.stepup.providers.DeepLinkProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives the tenant web app's server-to-server callback after the user
 * completed deep-link verification (typically tenant-side OIDC). Authenticates
 * the POST via a bearer token equal to the {@code DeepLinkProvider} secret,
 * then hands the JWT to the {@link StepUpCoordinator} for verification and
 * elevation persistence.
 *
 * <p>This endpoint is OUTSIDE the {@code /v1/admin/**} tree — the tenant's
 * web backend is the caller, not an operator session. Origin checks don't
 * apply; the bearer token IS the trust anchor.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §10.3.
 */
@RestController
@RequestMapping("/v1/channels/whatsapp/step-up/callback")
public class StepUpCallbackController {

    private final StepUpCoordinator coordinator;
    private final DeepLinkProvider deepLinkProvider;

    public StepUpCallbackController(StepUpCoordinator coordinator,
                                    DeepLinkProvider deepLinkProvider) {
        this.coordinator = coordinator;
        this.deepLinkProvider = deepLinkProvider;
    }

    @PostMapping
    public ResponseEntity<CallbackResponse> verify(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody CallbackRequest body) {
        String bearer = stripBearer(authorizationHeader);
        if (!deepLinkProvider.isCallbackTokenValid(bearer)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new CallbackResponse("unauthorized", null));
        }
        if (body == null || body.token() == null || body.token().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new CallbackResponse("missing_token", null));
        }

        StepUpCoordinator.VerifyOutcome outcome = coordinator.verifyDeepLinkCallback(body.token());
        return switch (outcome) {
            case StepUpCoordinator.VerifyOutcome.NoPending n -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new CallbackResponse("no_pending", null));
            case StepUpCoordinator.VerifyOutcome.Verified v -> ResponseEntity.ok(
                    new CallbackResponse("verified", v.audience()));
            case StepUpCoordinator.VerifyOutcome.WrongCode w -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new CallbackResponse("verify_failed", w.audience()));
            case StepUpCoordinator.VerifyOutcome.Exhausted e -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new CallbackResponse("exhausted", e.audience()));
        };
    }

    private static String stripBearer(String header) {
        if (header == null) return null;
        String trimmed = header.trim();
        if (trimmed.toLowerCase().startsWith("bearer ")) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    /** Tenant web app body — just the JWT we minted, echoed back. */
    public record CallbackRequest(String token) {}

    public record CallbackResponse(String status, String audience) {}
}

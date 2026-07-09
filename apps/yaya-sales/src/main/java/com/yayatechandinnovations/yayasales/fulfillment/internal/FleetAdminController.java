package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.yayatechandinnovations.yayasales.fulfillment.DeliveryFleet;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryFleet.AgentStatus;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryFleet.AgentView;
import com.yayatechandinnovations.yayasales.platform.Ids.AgentId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fleet admin (design §10.1): mint invites, review KYC (documents decrypt only
 * on the explicit content endpoint — a listing never carries plaintext PII),
 * and decide — approve mints the courier credential, returned exactly once.
 * Agents of another store 404; existence is not leaked.
 */
@RestController
@RequestMapping("/v1/sales/admin/delivery")
public class FleetAdminController {

    private static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final DeliveryFleet fleet;

    public FleetAdminController(DeliveryFleet fleet) {
        this.fleet = fleet;
    }

    @PostMapping("/agents/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> mintInvite(@RequestHeader(STORE_HEADER) String storeId,
                                          @RequestBody(required = false) MintInvite body,
                                          Authentication auth) {
        DeliveryFleet.Invite invite = fleet.mintInvite(new StoreId(storeId), actor(auth),
                body == null || body.ttlHours() == null ? null : Duration.ofHours(body.ttlHours()));
        return Map.of("inviteToken", invite.token(), "expiresAt", invite.expiresAt().toString());
    }

    @GetMapping("/agents")
    public List<AgentView> agents(@RequestHeader(STORE_HEADER) String storeId,
                                  @RequestParam(required = false) AgentStatus status) {
        return fleet.agentsOf(new StoreId(storeId), Optional.ofNullable(status));
    }

    @GetMapping("/agents/{id}")
    public AgentView agent(@RequestHeader(STORE_HEADER) String storeId, @PathVariable String id) {
        return owned(storeId, id);
    }

    /** Decrypt-on-demand (§15): the one place a KYC scan leaves the envelope. */
    @GetMapping("/agents/{id}/documents/{documentId}/content")
    public Map<String, String> documentContent(@RequestHeader(STORE_HEADER) String storeId,
                                               @PathVariable String id,
                                               @PathVariable String documentId) {
        owned(storeId, id);
        return Map.of("contentBase64", fleet.documentContent(new AgentId(id), documentId));
    }

    @PostMapping("/agents/{id}/approve")
    public Map<String, Object> approve(@RequestHeader(STORE_HEADER) String storeId,
                                       @PathVariable String id, Authentication auth) {
        owned(storeId, id);
        DeliveryFleet.Credential credential = fleet.approve(new AgentId(id), actor(auth));
        // The ONE time the operational token is visible — hand it to the courier.
        return Map.of("agentId", credential.agent().value(),
                "status", AgentStatus.APPROVED.name(),
                "courierToken", credential.token());
    }

    @PostMapping("/agents/{id}/reject")
    public AgentView reject(@RequestHeader(STORE_HEADER) String storeId, @PathVariable String id,
                            @RequestBody(required = false) Decision body, Authentication auth) {
        owned(storeId, id);
        return fleet.reject(new AgentId(id), actor(auth), body == null ? null : body.reason());
    }

    @PostMapping("/agents/{id}/suspend")
    public AgentView suspend(@RequestHeader(STORE_HEADER) String storeId, @PathVariable String id,
                             @RequestBody(required = false) Decision body, Authentication auth) {
        owned(storeId, id);
        return fleet.suspend(new AgentId(id), actor(auth), body == null ? null : body.reason());
    }

    private AgentView owned(String storeId, String agentId) {
        return fleet.agent(new AgentId(agentId))
                .filter(a -> a.store().value().equals(storeId))
                .orElseThrow(() -> new ApiErrors.NotFoundException("delivery agent", agentId));
    }

    private static String actor(Authentication auth) {
        return auth == null ? "operator" : "op:" + auth.getName();
    }

    public record MintInvite(Integer ttlHours) {}

    public record Decision(String reason) {}
}

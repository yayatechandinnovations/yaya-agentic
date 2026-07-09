package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.yayatechandinnovations.yayasales.fulfillment.DeliveryFleet;
import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids.AgentId;
import com.yayatechandinnovations.yayasales.platform.auth.Caller;
import com.yayatechandinnovations.yayasales.platform.auth.CourierTokenFilter;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §8.9 self-registration — register and verify are the ONLY public courier
 * endpoints (§10.4): the invite token gates the first, the phone OTP the
 * second. Document upload already needs the registration token minted at
 * verification, so an invite alone can never plant files.
 */
@RestController
@RequestMapping("/v1/sales/delivery/agents")
public class CourierRegistrationController {

    private final DeliveryFleet fleet;

    public CourierRegistrationController(DeliveryFleet fleet) {
        this.fleet = fleet;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@Valid @RequestBody Register body) {
        DeliveryFleet.Registration registration = fleet.register(body.inviteToken(),
                new DeliveryFleet.AgentRegistration(body.legalName(), body.legalAddress(),
                        body.phone(), body.vehicle(), body.idDoc()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("agentId", registration.agent().value());
        response.put("status", registration.status().name());
        response.put("next", "verify the phone with the OTP, then upload your documents");
        if (registration.otpEcho() != null) {
            response.put("otp", registration.otpEcho());   // dev/test echo only (see properties)
        }
        return response;
    }

    @PostMapping("/verify")
    public Map<String, Object> verify(@Valid @RequestBody Verify body) {
        DeliveryFleet.Credential credential = fleet.verifyPhone(body.phone(), body.otp());
        // The registration-scoped bearer, shown once — it authenticates the
        // document upload and /me until approval rotates it.
        return Map.of("agentId", credential.agent().value(),
                "courierToken", credential.token());
    }

    /** Courier-token-authenticated; a courier can only feed its own file. */
    @PostMapping("/{id}/documents")
    public Map<String, Object> documents(HttpServletRequest request, @PathVariable String id,
                                         @Valid @RequestBody Documents body) {
        Caller.Courier courier = caller(request);
        if (!courier.agent().value().equals(id)) {
            throw new ApiErrors.NotFoundException("delivery agent", id);
        }
        DeliveryFleet.AgentView agent = fleet.submitDocuments(new AgentId(id), body.documents());
        return Map.of("agentId", agent.id().value(),
                "status", agent.status().name(),
                "documents", agent.documents());
    }

    private static Caller.Courier caller(HttpServletRequest request) {
        Object attribute = request.getAttribute(CourierTokenFilter.COURIER_CALLER);
        if (!(attribute instanceof Caller.Courier courier)) {
            throw new IllegalStateException("courier filter did not run");
        }
        return courier;
    }

    public record Register(@NotBlank String inviteToken,
                           @NotBlank String legalName,
                           @NotNull Address legalAddress,
                           @NotBlank String phone,
                           @NotNull DeliveryFleet.Vehicle vehicle,
                           DeliveryFleet.IdDocument idDoc) {}

    public record Verify(@NotBlank String phone, @NotBlank String otp) {}

    public record Documents(@NotNull List<DeliveryFleet.DocumentUpload> documents) {}
}

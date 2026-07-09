package com.yayatechandinnovations.yayasales.returns.internal;

import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.RmaId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.returns.RmaService;
import com.yayatechandinnovations.yayasales.returns.RmaStatus;
import com.yayatechandinnovations.yayasales.returns.RmaView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * RMA admin (design §10.1): request against a paid order, authorize/reject/
 * cancel, capture the return-shipment metadata, and receive — restock +
 * refund in one operator action. Operator-authenticated; every RMA is checked
 * against the acting store.
 */
@RestController
@RequestMapping("/v1/sales/admin/rmas")
public class RmaAdminController {

    private static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final RmaService rmas;

    public RmaAdminController(RmaService rmas) {
        this.rmas = rmas;
    }

    @PostMapping
    public RmaView request(@RequestHeader(STORE_HEADER) String storeId,
                           @Valid @RequestBody Request body, Authentication auth) {
        return rmas.request(new StoreId(storeId), new OrderId(body.orderId()),
                body.lines().stream()
                        .map(l -> new RmaView.Line(new VariantId(l.sku()), l.qty()))
                        .toList(),
                body.reason(), actor(auth));
    }

    @GetMapping
    public List<RmaView> list(@RequestHeader(STORE_HEADER) String storeId,
                              @RequestParam(required = false) RmaStatus status) {
        return rmas.rmasOf(new StoreId(storeId), Optional.ofNullable(status));
    }

    @GetMapping("/{id}")
    public RmaView detail(@RequestHeader(STORE_HEADER) String storeId, @PathVariable String id) {
        return scoped(storeId, id);
    }

    @PostMapping("/{id}/authorize")
    public RmaView authorize(@RequestHeader(STORE_HEADER) String storeId,
                             @PathVariable String id, Authentication auth) {
        return rmas.authorize(scoped(storeId, id).id(), actor(auth));
    }

    @PostMapping("/{id}/reject")
    public RmaView reject(@RequestHeader(STORE_HEADER) String storeId, @PathVariable String id,
                          @RequestBody(required = false) Reject body, Authentication auth) {
        return rmas.reject(scoped(storeId, id).id(),
                body == null ? null : body.reason(), actor(auth));
    }

    @PostMapping("/{id}/cancel")
    public RmaView cancel(@RequestHeader(STORE_HEADER) String storeId,
                          @PathVariable String id, Authentication auth) {
        return rmas.cancel(scoped(storeId, id).id(), actor(auth));
    }

    @PutMapping("/{id}/return-shipment")
    public RmaView returnShipment(@RequestHeader(STORE_HEADER) String storeId,
                                  @PathVariable String id,
                                  @RequestBody ReturnShipment body) {
        return rmas.setReturnShipment(scoped(storeId, id).id(),
                new RmaView.ReturnShipment(body.carrier(), body.trackingNumber(),
                        body.instructions()));
    }

    @PostMapping("/{id}/receive")
    public RmaView receive(@RequestHeader(STORE_HEADER) String storeId, @PathVariable String id,
                           @Valid @RequestBody Receive body, Authentication auth) {
        boolean toStoreCredit = !"original_tender".equals(body.refundTo());
        return rmas.receive(scoped(storeId, id).id(),
                new RmaService.ReceiveCommand(body.dispositions().stream()
                        .map(d -> new RmaView.Disposition(new VariantId(d.sku()), d.qty(),
                                d.disposition()))
                        .toList(),
                        toStoreCredit, body.amountMinor()),
                actor(auth));
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    /** Another store's RMA is answered as absent — existence is not leaked. */
    private RmaView scoped(String storeId, String id) {
        return rmas.rma(new RmaId(id))
                .filter(r -> r.store().value().equals(storeId))
                .orElseThrow(() -> new ApiErrors.NotFoundException("rma", id));
    }

    private static String actor(Authentication auth) {
        return auth == null ? "operator" : "op:" + auth.getName();
    }

    public record Request(@NotBlank String orderId, @NotEmpty List<Line> lines, String reason) {}

    public record Line(String sku, long qty) {}

    public record Reject(String reason) {}

    public record ReturnShipment(String carrier, String trackingNumber, String instructions) {}

    /** {@code refundTo}: {@code store_credit} (default, §8.3) or {@code original_tender}. */
    public record Receive(@NotEmpty List<Disposition> dispositions, String refundTo,
                          Long amountMinor) {}

    public record Disposition(String sku, long qty, RmaView.Disposition.Kind disposition) {}
}

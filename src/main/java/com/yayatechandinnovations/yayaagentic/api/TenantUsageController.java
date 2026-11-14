package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import com.yayatechandinnovations.yayaagentic.usage.UsageMeter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * A tenant's monthly allowance and what it has used
 * ({@code docs/guides/admin-panel-sessions.md} §9). Same access model as the
 * other tenant-scoped admin endpoints: a workspace ADMIN, or a platform-scope
 * API key (the integration that pushes the allowance from the tenant's plan).
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}")
public class TenantUsageController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final UsageMeter usage;

    public TenantUsageController(TenantGuard tenantGuard,
                                 WorkspaceAccessHelper workspaceAccess,
                                 UsageMeter usage) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.usage = usage;
    }

    /** Body {@code {"monthlyMessages": <int|null>}} — null = unlimited. */
    @PutMapping("/usage-quota")
    public QuotaResponse putQuota(@PathVariable("tenantId") String tenantId,
                                  @RequestBody(required = false) JsonNode body,
                                  ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        // The key must be present: an empty body must not silently lift the allowance.
        if (body == null || !body.isObject() || !body.has("monthlyMessages")) {
            throw AdminApiException.badRequest("missing_monthly_messages",
                    "body must be {\"monthlyMessages\": <int|null>}");
        }
        JsonNode value = body.get("monthlyMessages");
        Integer monthlyMessages;
        if (value.isNull()) {
            monthlyMessages = null;
        } else if (value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0) {
            monthlyMessages = value.asInt();
        } else {
            throw AdminApiException.badRequest("invalid_monthly_messages",
                    "monthlyMessages must be a whole number, zero or more, or null for unlimited");
        }
        usage.setMonthlyMessages(tenantId, monthlyMessages);
        return new QuotaResponse(monthlyMessages);
    }

    /** {@code month} is YYYY-MM; omitted = the current month in the tenant's time zone. */
    @GetMapping("/usage")
    public UsageResponse getUsage(@PathVariable("tenantId") String tenantId,
                                  @RequestParam(value = "month", required = false) String month,
                                  ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        UsageMeter.Totals totals;
        try {
            totals = usage.usage(tenantId, month);
        } catch (IllegalArgumentException e) {
            throw AdminApiException.badRequest("invalid_month", "month must be YYYY-MM");
        }
        return new UsageResponse(totals.month(), totals.messages(), totals.inputTokens(),
                totals.outputTokens(), totals.cacheReadTokens(), totals.cacheCreationTokens(),
                totals.monthlyMessages(),
                usage.usageByProfile(tenantId, totals.month()));
    }

    public record QuotaResponse(Integer monthlyMessages) {}

    /**
     * {@code messages} / {@code inputTokens} / {@code outputTokens} are the
     * metered profiles' totals — what {@code monthlyMessages} is measured
     * against. {@code profiles} breaks the month down for every profile,
     * metered or not. {@code cacheReadTokens} / {@code cacheCreationTokens} are
     * the parts of {@code inputTokens} the provider read from / wrote to its
     * prompt cache — billed at about a tenth and 1.25x of the input price.
     */
    public record UsageResponse(String month, long messages, long inputTokens, long outputTokens,
                                long cacheReadTokens, long cacheCreationTokens,
                                Integer monthlyMessages, List<UsageMeter.ProfileTotals> profiles) {}
}

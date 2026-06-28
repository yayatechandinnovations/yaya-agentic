package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.channel.sms.BusinessIdentity;
import com.yayatechandinnovations.yayaagentic.channel.sms.OptInDescription;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsBrand;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsCampaign;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire DTOs for the A2P 10DLC compliance surface
 * ({@code /v1/admin/tenants/{tenantId}/sms/*}).
 *
 * <p>By design these responses NEVER surface the Twilio profile / campaign SID
 * or the TCR brand id — Twilio and The Campaign Registry are invisible to the
 * tenant ({@code sms-channel-design.md} §2). The tenant sees status + a single
 * user-facing failure message, nothing carrier-internal.
 */
public final class SmsComplianceDtos {

    private SmsComplianceDtos() {}

    /* ----------------------------- Brand --------------------------------- */

    public record BusinessIdentityDto(
            String legalName,
            String taxId,
            String address,
            String vertical,
            String websiteUrl,
            String contactEmail
    ) {
        public BusinessIdentity toDomain() {
            return new BusinessIdentity(legalName, taxId, address, vertical, websiteUrl, contactEmail);
        }

        public static BusinessIdentityDto of(BusinessIdentity b) {
            return new BusinessIdentityDto(b.legalName(), b.taxId(), b.address(),
                    b.vertical(), b.websiteUrl(), b.contactEmail());
        }
    }

    /** Body for POST + PUT brand. */
    public record BrandRequest(BusinessIdentityDto business) {}

    public record BrandResponse(
            String              id,
            String              tenantId,
            BusinessIdentityDto business,
            String              status,
            String              failureMessage,
            Instant             createdAt,
            Instant             updatedAt,
            Instant             lastSyncAt
    ) {
        public static BrandResponse of(SmsBrand b) {
            return new BrandResponse(
                    b.id().value(),
                    b.tenant().value(),
                    BusinessIdentityDto.of(b.business()),
                    b.status().name(),
                    b.lastFailureMessage(),
                    b.createdAt(),
                    b.updatedAt(),
                    b.lastSyncAt());
        }
    }

    /* --------------------------- Campaign -------------------------------- */

    public record OptInDto(String description, String proofUrl) {
        public OptInDescription toDomain() {
            return new OptInDescription(description, proofUrl);
        }

        public static OptInDto of(OptInDescription o) {
            return new OptInDto(o.description(), o.proofUrl());
        }
    }

    /** Body for POST campaign. */
    public record CampaignRequest(
            String              brandId,
            String              useCase,
            String              description,
            List<String>        sampleMessages,
            OptInDto            optIn,
            Map<String, Object> evidence
    ) {}

    public record CampaignResponse(
            String       id,
            String       tenantId,
            String       brandId,
            String       useCase,
            boolean      restricted,
            String       description,
            List<String> sampleMessages,
            OptInDto     optIn,
            boolean      requiresOperatorReview,
            String       status,
            String       failureMessage,
            Instant      createdAt,
            Instant      updatedAt,
            Instant      lastSyncAt
    ) {
        public static CampaignResponse of(SmsCampaign c) {
            return new CampaignResponse(
                    c.id().value(),
                    c.tenant().value(),
                    c.brand().value(),
                    c.useCase().name(),
                    c.useCase().isRestricted(),
                    c.description(),
                    c.sampleMessages(),
                    OptInDto.of(c.optIn()),
                    c.requiresOperatorReview(),
                    c.status().name(),
                    c.lastFailureMessage(),
                    c.createdAt(),
                    c.updatedAt(),
                    c.lastSyncAt());
        }
    }
}

package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * How end users consent to receive messages under an {@link SmsCampaign}, plus
 * a reference to consent proof. Carriers can demand opt-in evidence at any
 * time; for marketing campaigns its absence is a hard rejection
 * ({@code sms-channel-design.md} §6.5).
 *
 * @param description plain-language description of where/how the user opts in
 * @param proofUrl    URL or stored-artifact reference showing the opt-in
 *                    surface (a screenshot, a form URL); may be null at DRAFT
 */
public record OptInDescription(String description, String proofUrl) {

    public OptInDescription {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("opt-in description must not be blank");
        }
    }
}

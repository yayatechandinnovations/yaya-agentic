package com.yayatechandinnovations.yayaagentic.channel.system;

import java.util.List;
import java.util.Set;

/**
 * Bounds what a given sender may target (§6). A closure-report sender reaches
 * its own store's operators, not arbitrary customers. Resolution is consent-
 * and opt-out-aware and silently drops ineligible targets — existence is never
 * leaked (design §5.8 rule).
 *
 * @param allowedKinds    which {@link OutreachRecipient} kinds this sender may use
 *                        ({@code PRINCIPAL} / {@code ROLE} / {@code SESSION})
 * @param allowedSubjects when non-empty, the only {@code ByPrincipal} subjects this
 *                        sender may reach; empty = any subject its scope permits
 * @param allowedRoles    when non-empty, the only {@code ByRole} roles allowed (SM-3)
 */
public record AddressingPolicy(
        Set<String> allowedKinds,
        List<String> allowedSubjects,
        List<String> allowedRoles
) {
    public AddressingPolicy {
        allowedKinds = allowedKinds == null || allowedKinds.isEmpty()
                ? Set.of("PRINCIPAL") : Set.copyOf(allowedKinds);
        allowedSubjects = allowedSubjects == null ? List.of() : List.copyOf(allowedSubjects);
        allowedRoles = allowedRoles == null ? List.of() : List.copyOf(allowedRoles);
    }

    public static AddressingPolicy principalsOnly() {
        return new AddressingPolicy(Set.of("PRINCIPAL"), List.of(), List.of());
    }

    /** True when this sender is permitted to address the given recipient. */
    public boolean permits(OutreachRecipient recipient) {
        if (!allowedKinds.contains(recipient.kindName())) return false;
        return switch (recipient) {
            case OutreachRecipient.ByPrincipal p ->
                    allowedSubjects.isEmpty() || allowedSubjects.contains(p.subject());
            case OutreachRecipient.ByRole r ->
                    allowedRoles.isEmpty() || allowedRoles.contains(r.role());
            case OutreachRecipient.BySession ignored -> true;
        };
    }
}

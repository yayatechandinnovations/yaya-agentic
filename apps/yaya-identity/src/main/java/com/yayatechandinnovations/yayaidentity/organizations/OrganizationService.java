package com.yayatechandinnovations.yayaidentity.organizations;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import com.yayatechandinnovations.yayaidentity.platform.outbox.OutboxRelay;
import com.yayatechandinnovations.yayaidentity.users.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Organization + membership operations (design §5.1). Slugs are derived from the
 * name and de-duplicated with a short random suffix. Adding a member validates
 * the user exists (intra-schema) and emits {@code membership.added}.
 */
@Service
public class OrganizationService {

    private final OrganizationRepository orgs;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final OutboxRelay outbox;

    public OrganizationService(OrganizationRepository orgs, MembershipRepository memberships,
                               UserRepository users, OutboxRelay outbox) {
        this.orgs = orgs;
        this.memberships = memberships;
        this.users = users;
        this.outbox = outbox;
    }

    @Transactional
    public Organization create(String name) {
        String id = "org_" + UUID.randomUUID().toString().replace("-", "");
        String slug = uniqueSlug(name);
        OrganizationEntity e = new OrganizationEntity(
                id, name, slug, Organization.Status.ACTIVE.name(), null, Instant.now());
        orgs.save(e);
        outbox.append("organization", id, "organization.created", "{\"id\":\"" + id + "\"}");
        return e.toDomain();
    }

    @Transactional(readOnly = true)
    public Optional<Organization> byId(OrgId id) {
        return orgs.findById(id.value()).map(OrganizationEntity::toDomain);
    }

    @Transactional(readOnly = true)
    public List<Organization> list() {
        return orgs.findAll().stream().map(OrganizationEntity::toDomain).toList();
    }

    @Transactional
    public void setMfaRequired(OrgId org, boolean required) {
        OrganizationEntity e = orgs.findById(org.value())
                .orElseThrow(() -> new IllegalArgumentException("no such org: " + org.value()));
        e.setMfaRequired(required);
    }

    @Transactional(readOnly = true)
    public boolean isMfaRequired(OrgId org) {
        return orgs.findById(org.value()).map(OrganizationEntity::isMfaRequired).orElse(false);
    }

    @Transactional
    public Membership addMember(OrgId org, UserId user) {
        if (!orgs.existsById(org.value())) {
            throw new IllegalArgumentException("no such org: " + org.value());
        }
        if (!users.existsById(user.value())) {
            throw new IllegalArgumentException("no such user: " + user.value());
        }
        MembershipEntity.Key key = new MembershipEntity.Key(user.value(), org.value());
        MembershipEntity m = memberships.findById(key).orElseGet(() -> {
            MembershipEntity created = new MembershipEntity(
                    key, Membership.Status.ACTIVE.name(), Instant.now());
            memberships.save(created);
            outbox.append("membership", user.value(), "membership.added",
                    "{\"org\":\"" + org.value() + "\",\"user\":\"" + user.value() + "\"}");
            return created;
        });
        return m.toDomain();
    }

    @Transactional(readOnly = true)
    public List<Membership> membersOf(OrgId org) {
        return memberships.findByIdOrgId(org.value()).stream().map(MembershipEntity::toDomain).toList();
    }

    @Transactional(readOnly = true)
    public List<Membership> orgsOf(UserId user) {
        return memberships.findByIdUserId(user.value()).stream().map(MembershipEntity::toDomain).toList();
    }

    private String uniqueSlug(String name) {
        String base = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isBlank()) base = "org";
        String slug = base;
        while (orgs.existsBySlug(slug)) {
            slug = base + "-" + UUID.randomUUID().toString().substring(0, 4);
        }
        return slug;
    }
}

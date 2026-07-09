package com.yayatechandinnovations.yayaidentity.organizations;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "memberships")
public class MembershipEntity {

    @Embeddable
    public record Key(@Column(name = "user_id") String userId,
                      @Column(name = "org_id") String orgId) implements Serializable {
        public Key {
            Objects.requireNonNull(userId);
            Objects.requireNonNull(orgId);
        }
    }

    @EmbeddedId
    private Key id;

    @Column(nullable = false)
    private String status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    protected MembershipEntity() {}

    public MembershipEntity(Key id, String status, Instant joinedAt) {
        this.id = id;
        this.status = status;
        this.joinedAt = joinedAt;
    }

    public Membership toDomain() {
        return new Membership(new UserId(id.userId()), new OrgId(id.orgId()),
                Membership.Status.valueOf(status), joinedAt);
    }
}

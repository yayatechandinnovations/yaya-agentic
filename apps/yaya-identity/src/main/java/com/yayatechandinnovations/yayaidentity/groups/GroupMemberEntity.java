package com.yayatechandinnovations.yayaidentity.groups;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "group_members")
public class GroupMemberEntity {

    @Embeddable
    public record Key(@Column(name = "group_id") String groupId,
                      @Column(name = "user_id") String userId) implements Serializable {
        public Key {
            Objects.requireNonNull(groupId);
            Objects.requireNonNull(userId);
        }
    }

    @EmbeddedId
    private Key id;

    protected GroupMemberEntity() {}

    public GroupMemberEntity(Key id) {
        this.id = id;
    }

    public Key getId() { return id; }
}

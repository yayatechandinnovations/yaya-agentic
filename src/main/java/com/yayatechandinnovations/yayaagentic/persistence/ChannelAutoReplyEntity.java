package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/** When a sender last answered an unknown caller with its fixed line (V97). */
@Entity
@Table(name = "channel_auto_replies")
@IdClass(ChannelAutoReplyEntity.PK.class)
public class ChannelAutoReplyEntity {

    @Id @Column(name = "sender_id", length = 64)
    private String senderId;

    @Id @Column(name = "end_user_phone", length = 20)
    private String endUserPhone;

    @Column(name = "replied_at", nullable = false)
    private OffsetDateTime repliedAt;

    protected ChannelAutoReplyEntity() {}

    public ChannelAutoReplyEntity(String senderId, String endUserPhone, OffsetDateTime repliedAt) {
        this.senderId = senderId;
        this.endUserPhone = endUserPhone;
        this.repliedAt = repliedAt;
    }

    public String getSenderId() { return senderId; }
    public String getEndUserPhone() { return endUserPhone; }
    public OffsetDateTime getRepliedAt() { return repliedAt; }
    public void setRepliedAt(OffsetDateTime v) { this.repliedAt = v; }

    public static class PK implements Serializable {
        private String senderId; private String endUserPhone;
        public PK() {}
        public PK(String senderId, String endUserPhone) {
            this.senderId = senderId; this.endUserPhone = endUserPhone;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(senderId, pk.senderId) && Objects.equals(endUserPhone, pk.endUserPhone);
        }
        @Override public int hashCode() { return Objects.hash(senderId, endUserPhone); }
    }
}

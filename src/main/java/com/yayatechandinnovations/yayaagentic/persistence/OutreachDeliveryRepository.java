package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface OutreachDeliveryRepository extends JpaRepository<OutreachDeliveryEntity, Long> {

    List<OutreachDeliveryEntity> findByTenantIdAndOutreachIdOrderByIdAsc(String tenantId, String outreachId);

    @WorkspaceUnscoped("signature-verified Twilio status callbacks name only the globally-unique message sid")
    Optional<OutreachDeliveryEntity> findFirstByProviderMessageSid(String providerMessageSid);

    /** WhatsApp deliveries to a phone the open conversation has not seen yet (§6.6). */
    List<OutreachDeliveryEntity>
            findByTenantIdAndWhatsappSenderIdAndEndUserPhoneAndChannelAndCreatedAtAfterOrderByCreatedAtAsc(
                    String tenantId, String whatsappSenderId, String endUserPhone, String channel,
                    OffsetDateTime after);
}

package com.yayatechandinnovations.yayaagentic.outreach;

import com.yayatechandinnovations.yayaagentic.channel.system.CapabilityMask;
import com.yayatechandinnovations.yayaagentic.channel.system.Correlation;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSender;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityKind;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityMemory;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityRef;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityScope;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.persistence.SystemPendingReplyEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SystemPendingReplyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * The mediation + reply-loop machinery (§8, §9.1). Splits cleanly along the two
 * moments in the loop:
 *
 * <ul>
 *   <li><b>Ingest (LLM_MEDIATED):</b> {@link #recordPending} persists the durable
 *       correlation so it survives the operator being offline.</li>
 *   <li><b>Reply (after resume):</b> {@link #primeReply} re-captures the
 *       correlation into entity memory (engine-pinned onto the response tool, §8)
 *       and arms the capability mask for the turn (§9.1 Rule 2); {@link #resolve}
 *       tears both down once the reply is sent.</li>
 * </ul>
 *
 * The correlation entities are captured {@code visibleToLlm=false}: the model
 * never sees the routing handle, so it cannot echo or redirect it.
 */
@Service
public class MediationService {

    private final SystemPendingReplyRepository pending;
    private final SystemSenderService senders;
    private final EntityMemory entityMemory;
    private final WorkingMemory workingMemory;

    public MediationService(SystemPendingReplyRepository pending,
                            SystemSenderService senders,
                            EntityMemory entityMemory,
                            WorkingMemory workingMemory) {
        this.pending = pending;
        this.senders = senders;
        this.entityMemory = entityMemory;
        this.workingMemory = workingMemory;
    }

    /** Ingest leg: persist the durable pending reply for an LLM-mediated message. */
    @Transactional
    public void recordPending(Ids.TenantId tenant, Ids.SessionId session,
                              SystemSender sender, Correlation c) {
        pending.save(new SystemPendingReplyEntity(
                tenant.value(), session.value(), sender.id().value(),
                c.originatorName(), c.originatorRef(), c.subject()));
    }

    /**
     * Reply leg: arm the session so the agent can answer the newest pending
     * system message. Captures the engine-pinned correlation entities and sets
     * the capability mask. Returns false when nothing is pending.
     */
    @Transactional
    public boolean primeReply(Ids.TenantId tenant, Ids.SessionId session) {
        List<SystemPendingReplyEntity> pend =
                pending.findBySessionIdAndStatusOrderByCreatedAtDesc(session.value(), "PENDING");
        if (pend.isEmpty()) return false;
        SystemPendingReplyEntity newest = pend.get(0); // most-recent default (§8.3)
        SystemSender sender = senders.find(tenant, new Ids.SystemSenderId(newest.getSenderId()))
                .orElse(null);
        if (sender == null) return false;

        entityMemory.capture(session, correlationEntity(
                SystemReplyToolBootstrap.CORRELATION_REF_ENTITY, newest.getOriginatorRef()));
        entityMemory.capture(session, correlationEntity(
                SystemReplyToolBootstrap.CORRELATION_SUBJECT_ENTITY,
                newest.getSubject() == null ? "" : newest.getSubject()));

        CapabilityMask mask = sender.effectiveMask();
        workingMemory.merge(session, Map.of(CapabilityMask.WORKING_MEMORY_KEY, mask.toWorkingMemory()));
        return true;
    }

    /** Tear down the mask + correlation once the reply has been sent. */
    @Transactional
    public void resolve(Ids.SessionId session) {
        List<SystemPendingReplyEntity> pend =
                pending.findBySessionIdAndStatusOrderByCreatedAtDesc(session.value(), "PENDING");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (SystemPendingReplyEntity p : pend) {
            p.setStatus("RESOLVED");
            p.setResolvedAt(now);
        }
        pending.saveAll(pend);
        workingMemory.remove(session, CapabilityMask.WORKING_MEMORY_KEY);
        entityMemory.clear(session, SystemReplyToolBootstrap.CORRELATION_REF_ENTITY);
        entityMemory.clear(session, SystemReplyToolBootstrap.CORRELATION_SUBJECT_ENTITY);
    }

    private static EntityRef correlationEntity(String name, String value) {
        // visibleToLlm=false: the routing handle is engine-only; the model binds
        // it onto the response tool without ever seeing it in the prompt (§8).
        return new EntityRef(name, value, EntityScope.SESSION, EntityKind.SINGLETON,
                false, SystemReplyToolBootstrap.RESPONSE_TOOL, Instant.now(), Map.of());
    }
}

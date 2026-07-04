package com.yayatechandinnovations.yayaagentic.outreach;

import com.yayatechandinnovations.yayaagentic.channel.system.AddressingPolicy;
import com.yayatechandinnovations.yayaagentic.channel.system.Correlation;
import com.yayatechandinnovations.yayaagentic.channel.system.MediationMode;
import com.yayatechandinnovations.yayaagentic.channel.system.SenderTrust;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSender;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSenderService;
import com.yayatechandinnovations.yayaagentic.channel.system.CapabilityMask;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityMemory;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityRef;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.persistence.SystemPendingReplyEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SystemPendingReplyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** §8 reply loop: correlation pinned (engine-only), mask armed, torn down on resolve. */
class MediationServiceTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("acme");
    private static final Ids.SessionId SESSION = new Ids.SessionId("sess-1");

    private SystemPendingReplyRepository pending;
    private SystemSenderService senders;
    private EntityMemory entityMemory;
    private WorkingMemory workingMemory;
    private MediationService service;

    @BeforeEach
    void setUp() {
        pending = mock(SystemPendingReplyRepository.class);
        senders = mock(SystemSenderService.class);
        entityMemory = mock(EntityMemory.class);
        workingMemory = mock(WorkingMemory.class);
        service = new MediationService(pending, senders, entityMemory, workingMemory);
    }

    @Test
    void primeReplyPinsCorrelationEngineOnlyAndArmsMask() {
        SystemPendingReplyEntity row = new SystemPendingReplyEntity(
                TENANT.value(), SESSION.value(), "sys_pos", "Cash Register #3", "cb-77", "closure");
        when(pending.findBySessionIdAndStatusOrderByCreatedAtDesc(SESSION.value(), "PENDING"))
                .thenReturn(List.of(row));
        when(senders.find(eq(TENANT), any())).thenReturn(Optional.of(senderWithReplyTool()));

        boolean primed = service.primeReply(TENANT, SESSION);
        assertThat(primed).isTrue();

        // Two correlation entities captured, both hidden from the LLM (§8).
        ArgumentCaptor<EntityRef> ent = ArgumentCaptor.forClass(EntityRef.class);
        verify(entityMemory, times(2)).capture(eq(SESSION), ent.capture());
        assertThat(ent.getAllValues()).allSatisfy(e -> assertThat(e.visibleToLlm()).isFalse());
        assertThat(ent.getAllValues())
                .anySatisfy(e -> {
                    assertThat(e.name()).isEqualTo(SystemReplyToolBootstrap.CORRELATION_REF_ENTITY);
                    assertThat(e.value()).isEqualTo("cb-77");
                });

        // Mask armed in working memory, response tool always in the allowlist (Rule 2).
        ArgumentCaptor<Map<String, Object>> wm = ArgumentCaptor.forClass(Map.class);
        verify(workingMemory).merge(eq(SESSION), wm.capture());
        Object mask = wm.getValue().get(CapabilityMask.WORKING_MEMORY_KEY);
        assertThat(mask).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        List<Object> allow = (List<Object>) ((Map<String, Object>) mask).get("allow");
        assertThat(allow).contains(SystemReplyToolBootstrap.RESPONSE_TOOL.value());
    }

    @Test
    void primeReplyNoopWhenNothingPending() {
        when(pending.findBySessionIdAndStatusOrderByCreatedAtDesc(SESSION.value(), "PENDING"))
                .thenReturn(List.of());
        assertThat(service.primeReply(TENANT, SESSION)).isFalse();
        verify(entityMemory, never()).capture(any(), any());
        verify(workingMemory, never()).merge(any(), any());
    }

    @Test
    void resolveClearsMaskAndMarksResolved() {
        SystemPendingReplyEntity row = new SystemPendingReplyEntity(
                TENANT.value(), SESSION.value(), "sys_pos", "n", "cb-77", "s");
        when(pending.findBySessionIdAndStatusOrderByCreatedAtDesc(SESSION.value(), "PENDING"))
                .thenReturn(List.of(row));

        service.resolve(SESSION);

        assertThat(row.getStatus()).isEqualTo("RESOLVED");
        assertThat(row.getResolvedAt()).isNotNull();
        verify(workingMemory).remove(SESSION, CapabilityMask.WORKING_MEMORY_KEY);
        verify(entityMemory).clear(SESSION, SystemReplyToolBootstrap.CORRELATION_REF_ENTITY);
        verify(entityMemory).clear(SESSION, SystemReplyToolBootstrap.CORRELATION_SUBJECT_ENTITY);
    }

    private SystemSender senderWithReplyTool() {
        return new SystemSender(new Ids.SystemSenderId("sys_pos"), TENANT, "chat_x", "POS", true,
                new SenderTrust("shared-secret-jwt", Set.of(), ""), MediationMode.LLM_MEDIATED,
                Optional.of(SystemReplyToolBootstrap.RESPONSE_TOOL),
                AddressingPolicy.principalsOnly(), Optional.empty(), Optional.empty(),
                java.time.Instant.now(), java.time.Instant.now());
    }
}

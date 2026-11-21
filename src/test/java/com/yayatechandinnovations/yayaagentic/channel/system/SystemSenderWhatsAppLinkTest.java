package com.yayatechandinnovations.yayaagentic.channel.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SystemSenderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SystemSenderRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The WhatsApp number a system sender links: a real sender of the tenant, or
 * nothing. The Playground simulator is refused — the WhatsApp leg never sends
 * through it, so a link to it turned every proactive message of a workspace
 * into a silent {@code no_whatsapp_sender} failure (SL org {@code test},
 * 2026-11-20).
 */
class SystemSenderWhatsAppLinkTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("test");
    private static final Ids.SystemSenderId OWNER = new Ids.SystemSenderId("sys_owner");

    private SystemSenderRepository repo;
    private WhatsAppSenderRepository whatsappSenders;
    private SystemSenderService service;
    private SystemSenderEntity row;

    @BeforeEach
    void setUp() {
        repo = mock(SystemSenderRepository.class);
        whatsappSenders = mock(WhatsAppSenderRepository.class);
        ChatEntryPointRepository entryPoints = mock(ChatEntryPointRepository.class);
        when(entryPoints.findByTenantIdAndId("test", "ep_owner"))
                .thenReturn(Optional.of(mock(ChatEntryPointEntity.class)));
        row = new SystemSenderEntity("sys_owner", "test", "ep_owner", "Smart Lottery — Dueño");
        when(repo.findByTenantIdAndId("test", "sys_owner")).thenReturn(Optional.of(row));
        when(repo.save(any(SystemSenderEntity.class))).thenAnswer(call -> call.getArgument(0));
        service = new SystemSenderService(repo, entryPoints, new ObjectMapper(), whatsappSenders);
    }

    private static SystemSenderService.Command linking(String whatsappSenderId) {
        return new SystemSenderService.Command("ep_owner", "Smart Lottery — Dueño", true,
                null, null, null, null, null, null, whatsappSenderId);
    }

    @Test
    void thePlaygroundSimulator_isRefused_andTheLinkStaysAsItWas() {
        row.setWhatsappSenderId("snd_real");

        AdminApiException ex = assertThrows(AdminApiException.class,
                () -> service.update(TENANT, OWNER, linking("snd_playground_sim__test")));

        assertEquals("playground_sender_not_linkable", ex.code());
        assertEquals("snd_real", row.getWhatsappSenderId());
        verify(repo, never()).save(any());
    }

    @Test
    void aRealSenderOfTheTenant_isLinked_andBlankUnlinks() {
        when(whatsappSenders.findByTenantIdAndId("test", "snd_real"))
                .thenReturn(Optional.of(mock(WhatsAppSenderEntity.class)));

        service.update(TENANT, OWNER, linking("snd_real"));
        assertEquals("snd_real", row.getWhatsappSenderId());

        service.update(TENANT, OWNER, linking(" "));
        assertNull(row.getWhatsappSenderId());
    }

    @Test
    void aSenderOfAnotherTenant_isRefused() {
        when(whatsappSenders.findByTenantIdAndId("test", "snd_other")).thenReturn(Optional.empty());

        AdminApiException ex = assertThrows(AdminApiException.class,
                () -> service.update(TENANT, OWNER, linking("snd_other")));

        assertEquals("unknown_whatsapp_sender", ex.code());
    }
}

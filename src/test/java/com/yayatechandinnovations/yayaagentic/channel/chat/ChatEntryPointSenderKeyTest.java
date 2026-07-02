package com.yayatechandinnovations.yayaagentic.channel.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBindingService;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * yaya-agentic is the source of truth for a chat entry point's sender key: it
 * mints the 8-char code on create and never lets a branding edit rotate it out
 * from under the tenant's resolver.
 */
@ExtendWith(MockitoExtension.class)
class ChatEntryPointSenderKeyTest {

    private static final TenantId TENANT = new TenantId("t_acme");

    @Mock ChatEntryPointRepository repo;
    @Mock ChannelBindingService bindings;

    ChatEntryPointService svc;
    ObjectMapper json;

    @BeforeEach
    void setUp() {
        json = new ObjectMapper().findAndRegisterModules();
        svc = new ChatEntryPointService(repo, bindings, json);
        // save echoes the entity back so we can read the encoded auth config.
        lenient().when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_mints_an_8_char_sender_key() {
        ChatEntryPoint ep = svc.createDraft(TENANT, resolverCreate(7L));

        AuthConfig.ResolverExchange rx = (AuthConfig.ResolverExchange) ep.authConfig();
        assertThat(rx.authResolverId()).isEqualTo(7L);
        // 4 + '-' + 4, unambiguous uppercase alnum (no I/O/0/1).
        assertThat(rx.senderKey()).matches("^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$");
    }

    @Test
    void create_ignores_any_inbound_sender_key() {
        ChatEntryPointService.CreateCommand cmd = new ChatEntryPointService.CreateCommand(
                "retail-customer", 1, "Acme", "https://logo", null, null, null, null,
                false, AuthMode.AUTH_OPTIONAL,
                new AuthConfig.ResolverExchange(7L, "PASTED-VAL"), null, null);

        ChatEntryPoint ep = svc.createDraft(TENANT, cmd);

        assertThat(((AuthConfig.ResolverExchange) ep.authConfig()).senderKey()).isNotEqualTo("PASTED-VAL");
    }

    @Test
    void update_preserves_the_stored_sender_key() {
        ChatEntryPointEntity existing = existingResolverEntity("KEEP-THIS");
        when(repo.findByTenantIdAndId(TENANT.value(), "chat_1")).thenReturn(Optional.of(existing));

        ChatEntryPoint updated = svc.updateBranding(TENANT, new ChatEntryPointId("chat_1"),
                new ChatEntryPointService.UpdateCommand(
                        "Renamed", null, null, null, null, null, null, null,
                        new AuthConfig.ResolverExchange(7L, null), null, null));

        assertThat(((AuthConfig.ResolverExchange) updated.authConfig()).senderKey()).isEqualTo("KEEP-THIS");
    }

    private ChatEntryPointService.CreateCommand resolverCreate(long resolverId) {
        return new ChatEntryPointService.CreateCommand(
                "retail-customer", 1, "Acme", "https://logo", null, null, null, null,
                false, AuthMode.AUTH_OPTIONAL,
                new AuthConfig.ResolverExchange(resolverId, null), null, null);
    }

    private ChatEntryPointEntity existingResolverEntity(String senderKey) {
        ChatEntryPointEntity e = new ChatEntryPointEntity(
                "chat_1", TENANT.value(), "e_handle", "retail-customer", 1,
                "Acme", "https://logo", writeJson(com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy.defaults()),
                EntryPointStatus.DRAFT.name());
        e.setAuthMode(AuthMode.AUTH_OPTIONAL.name());
        e.setAuthConfigJson(writeJson(new AuthConfig.ResolverExchange(7L, senderKey)));
        e.setAbusePolicyJson(writeJson(AbusePolicy.defaults()));
        return e;
    }

    private String writeJson(Object v) {
        try { return json.writeValueAsString(v); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}

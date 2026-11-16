package com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppTemplateDtos;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCreds;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.WhatsAppTemplateService.Caller;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.WhatsAppTemplateService.Declaration;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioMessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioMessagingChannelProvider.ApprovalState;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppMessagingAccountEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppMessagingAccountRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderRepository;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * whatsapp-shared-templates-design.md §4–§5: one template per WhatsApp account,
 * used by the workspaces that hold it; platform admins decide who holds what.
 * The provider is a mock, so submissions are counted.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class SharedTemplatesTest {

    private static final Caller PLATFORM = new Caller(true, "test:platform");
    private static final Caller WORKSPACE = new Caller(false, "test:workspace");
    private static final AtomicInteger PHONES = new AtomicInteger(1000);

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;
    @Autowired WhatsAppTemplateService templates;
    @Autowired WhatsAppSenderService senders;
    @Autowired WhatsAppSenderRepository senderRows;
    @Autowired WhatsAppMessagingAccountRepository accountRows;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @MockitoBean TwilioMessagingChannelProvider twilio;
    @MockitoBean(name = "tenantSubaccountTwilioCredsResolver") ProviderCredsResolver creds;

    private Ids.TenantId a;
    private Ids.TenantId b;
    private Ids.TenantId c;
    private SenderId senderA;
    private SenderId senderB;
    private SenderId senderC;

    @BeforeEach
    void setUp() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        String run = UUID.randomUUID().toString().substring(0, 8);
        a = tenant("shared-a-" + run);
        b = tenant("shared-b-" + run);
        c = tenant("shared-c-" + run);
        senderA = sender(a);
        senderB = sender(b);
        senderC = sender(c);

        when(creds.isReady()).thenReturn(true);
        when(creds.resolveForAccount(any())).thenReturn(mock(ProviderCreds.class));
        when(twilio.findContentByName(anyString(), any())).thenReturn(Optional.empty());
        when(twilio.createContent(anyMap(), any())).thenReturn("HX" + run);
        when(twilio.requestWhatsAppApproval(anyString(), anyString(), anyString(), any()))
                .thenReturn(new ApprovalState("pending", null));
        clearInvocations(twilio);
    }

    @Test
    void a_platform_declaration_shares_an_existing_template_and_a_workspace_one_is_refused() {
        Declaration d = declaration("sl_alert_" + suffix(), "Alerta {{1}}.");
        WhatsAppTemplate first = templates.declare(a, senderA, d, WORKSPACE);
        assertThat(first.status()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(first.accountKey()).isEqualTo(TemplateAccounts.PLATFORM);
        assertThat(first.shared()).isFalse();

        // A workspace admin learns only that the name is in use.
        assertConflict(() -> templates.declare(b, senderB, d, WORKSPACE), "template_name_taken");
        assertConflict(() -> templates.declare(b, senderB, declaration(d.name(), "Otra {{1}}."), WORKSPACE),
                "template_name_taken");
        assertConflict(() -> templates.create(b, senderB, d.name(), "es", TemplateCategory.UTILITY,
                "Otra.", WORKSPACE), "template_name_taken");
        assertThat(templates.listForSender(b, senderB)).isEmpty();

        WhatsAppTemplate joined = templates.declare(b, senderB, d, PLATFORM);
        assertThat(joined.id()).isEqualTo(first.id());
        assertThat(joined.shared()).isTrue();
        assertThat(templates.listForSender(a, senderA)).singleElement()
                .satisfies(t -> assertThat(t.shared()).isTrue());
        assertThat(templates.listForSender(b, senderB)).extracting(WhatsAppTemplate::id)
                .containsExactly(first.id());
        assertThat(templates.listForSender(c, senderC)).isEmpty();
        // Declaring it again changes nothing.
        assertThat(templates.declare(b, senderB, d, PLATFORM).id()).isEqualTo(first.id());
        assertThat(holders(first)).containsExactly(a.value(), b.value());
    }

    @Test
    void new_wording_is_for_the_sole_holder_or_a_platform_admin() {
        String name = "sl_edit_" + suffix();
        WhatsAppTemplate first = templates.declare(a, senderA, declaration(name, "Uno {{1}}."), WORKSPACE);
        // The only holder rewrites its own draft.
        WhatsAppTemplate edited = templates.declare(a, senderA, declaration(name, "Dos {{1}}."), WORKSPACE);
        assertThat(edited.id()).isEqualTo(first.id());
        assertThat(edited.body()).isEqualTo("Dos {{1}}.");

        templates.declare(b, senderB, declaration(name, "Dos {{1}}."), PLATFORM);
        assertConflict(() -> templates.declare(a, senderA, declaration(name, "Tres {{1}}."), WORKSPACE),
                "not_editable");
        assertConflict(() -> templates.update(a, senderA, first.id(), "Tres.", TemplateCategory.UTILITY,
                WORKSPACE), "not_editable");
        WhatsAppTemplate byPlatform = templates.declare(a, senderA, declaration(name, "Tres {{1}}."), PLATFORM);
        assertThat(byPlatform.body()).isEqualTo("Tres {{1}}.");
        assertThat(templates.listForSender(b, senderB)).singleElement()
                .satisfies(t -> assertThat(t.body()).isEqualTo("Tres {{1}}."));

        // Once WhatsApp has it, nobody rewrites it under the same name.
        templates.submit(a, senderA, first.id());
        assertConflict(() -> templates.declare(a, senderA, declaration(name, "Cuatro {{1}}."), PLATFORM),
                "not_editable");
    }

    @Test
    void a_template_reaches_the_provider_once_and_only_holders_send_it() {
        Declaration d = declaration("sl_once_" + suffix(), "Aviso {{1}}.");
        WhatsAppTemplate t = templates.declare(a, senderA, d, PLATFORM);
        templates.declare(b, senderB, d, PLATFORM);

        assertThat(templates.submit(a, senderA, t.id()).status()).isEqualTo(TemplateStatus.SUBMITTED);
        // The second workspace's one-click finds it submitted: nothing more goes out.
        assertThat(templates.submit(b, senderB, t.id()).status()).isEqualTo(TemplateStatus.SUBMITTED);
        verify(twilio, times(1)).createContent(anyMap(), any());
        verify(twilio, times(1)).requestWhatsAppApproval(anyString(), eq(d.name()), anyString(), any());
        // A workspace that does not hold it cannot submit or even see it.
        assertThatThrownBy(() -> templates.submit(c, senderC, t.id()))
                .isInstanceOfSatisfying(AdminApiException.class,
                        e -> assertThat(e.code()).isEqualTo("template_not_found"));

        assertThat(templates.findApproved(a, senderOf(a, senderA), d.name(), "es")).isEmpty();
        when(twilio.fetchWhatsAppApproval(anyString(), any())).thenReturn(new ApprovalState("approved", null));
        assertThat(templates.syncSender(b, senderB)).singleElement()
                .satisfies(row -> assertThat(row.status()).isEqualTo(TemplateStatus.APPROVED));
        assertThat(templates.findApproved(a, senderOf(a, senderA), d.name(), "es")).isPresent();
        assertThat(templates.findApproved(b, senderOf(b, senderB), d.name(), "es")).isPresent();
        assertThat(templates.findApproved(c, senderOf(c, senderC), d.name(), "es")).isEmpty();

        clearInvocations(twilio);
        assertThat(templates.submit(b, senderB, t.id()).status()).isEqualTo(TemplateStatus.APPROVED);
        verify(twilio, never()).requestWhatsAppApproval(anyString(), anyString(), anyString(), any());
    }

    @Test
    void a_rejected_template_goes_back_to_the_provider_only_after_it_is_reworded() {
        String name = "sl_rejected_" + suffix();
        WhatsAppTemplate t = templates.declare(a, senderA, declaration(name, "Clave {{1}}."), PLATFORM);
        templates.submit(a, senderA, t.id());
        when(twilio.fetchWhatsAppApproval(anyString(), any()))
                .thenReturn(new ApprovalState("rejected", "INCORRECT_CATEGORY"));
        templates.syncSender(a, senderA);

        // The integration converges again with the same wording: the verdict is
        // returned, and the provider (which refuses a repeated request) is not asked.
        clearInvocations(twilio);
        WhatsAppTemplate same = templates.declare(a, senderA, declaration(name, "Clave {{1}}."), PLATFORM);
        assertThat(same.status()).isEqualTo(TemplateStatus.REJECTED);
        WhatsAppTemplate again = templates.submit(a, senderA, t.id());
        assertThat(again.status()).isEqualTo(TemplateStatus.REJECTED);
        assertThat(again.rejectionReason()).isEqualTo("INCORRECT_CATEGORY");
        verify(twilio, never()).requestWhatsAppApproval(anyString(), anyString(), anyString(), any());
        verify(twilio, never()).createContent(anyMap(), any());

        // New wording is a new submission.
        WhatsAppTemplate reworded = templates.declare(a, senderA,
                declaration(name, "Tienes asignado el retiro {{1}} en tu ruta de hoy."), PLATFORM);
        assertThat(reworded.status()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(templates.submit(a, senderA, t.id()).status()).isEqualTo(TemplateStatus.SUBMITTED);
        verify(twilio, times(1)).requestWhatsAppApproval(anyString(), eq(name), anyString(), any());
    }

    @Test
    void stopping_use_removes_one_workspace_and_an_approved_template_stays_in_the_account() {
        Declaration d = declaration("sl_keep_" + suffix(), "Aviso {{1}}.");
        WhatsAppTemplate t = templates.declare(a, senderA, d, PLATFORM);
        templates.declare(b, senderB, d, PLATFORM);
        templates.submit(a, senderA, t.id());
        when(twilio.fetchWhatsAppApproval(anyString(), any())).thenReturn(new ApprovalState("approved", null));
        templates.syncSender(a, senderA);

        templates.delete(b, senderB, t.id(), WORKSPACE);
        assertThat(templates.findApproved(b, senderOf(b, senderB), d.name(), "es")).isEmpty();
        assertThat(templates.findApproved(a, senderOf(a, senderA), d.name(), "es")).isPresent();

        templates.delete(a, senderA, t.id(), WORKSPACE);
        assertThat(holders(t)).isEmpty();
        var unheld = templates.listForAccount(TemplateAccounts.PLATFORM).stream()
                .filter(row -> row.template().id().equals(t.id())).findFirst();
        assertThat(unheld).isPresent();
        assertThat(unheld.get().template().status()).isEqualTo(TemplateStatus.APPROVED);
        assertConflict(() -> templates.deleteForAccount(TemplateAccounts.PLATFORM, t.id()), "not_deletable");

        // A platform admin hands it to another workspace without a new approval.
        clearInvocations(twilio);
        templates.share(TemplateAccounts.PLATFORM, t.id(), c, PLATFORM);
        assertThat(templates.findApproved(c, senderOf(c, senderC), d.name(), "es")).isPresent();
        verify(twilio, never()).requestWhatsAppApproval(anyString(), anyString(), anyString(), any());
        assertThat(jdbc.queryForList("SELECT action || ':' || tenant_id FROM whatsapp_template_holder_events "
                + "WHERE template_id = ? ORDER BY id", String.class, t.id().value()))
                .containsExactly("SHARED:" + a.value(), "SHARED:" + b.value(), "UNSHARED:" + b.value(),
                        "UNSHARED:" + a.value(), "SHARED:" + c.value());

        // A draft nobody holds any more is simply gone.
        WhatsAppTemplate draft = templates.declare(a, senderA, declaration("sl_gone_" + suffix(), "Hola {{1}}."),
                WORKSPACE);
        templates.delete(a, senderA, draft.id(), WORKSPACE);
        assertThat(templates.listForAccount(TemplateAccounts.PLATFORM))
                .noneMatch(row -> row.template().id().equals(draft.id()));
    }

    @Test
    void a_workspaces_own_account_keeps_its_templates_to_itself() {
        String account = "wma_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        accountRows.saveAndFlush(new WhatsAppMessagingAccountEntity(account, c.value(),
                "waba-" + account, "Own Brand", "ACTIVE"));
        jdbc.update("UPDATE whatsapp_senders SET messaging_account_id = ? WHERE id = ?", account, senderC.value());

        // The same name as on the platform account is a separate template there.
        Declaration d = declaration("sl_own_" + suffix(), "Aviso {{1}}.");
        WhatsAppTemplate onPlatform = templates.declare(a, senderA, d, WORKSPACE);
        WhatsAppTemplate onOwn = templates.declare(c, senderC, d, WORKSPACE);
        assertThat(onOwn.id()).isNotEqualTo(onPlatform.id());
        assertThat(onOwn.accountKey()).isEqualTo(account);

        assertConflict(() -> templates.share(account, onOwn.id(), a, PLATFORM), "holder_not_allowed");
        assertConflict(() -> templates.shareAll(account, List.of(onOwn.id()), List.of(c, a), PLATFORM),
                "holder_not_allowed");
        assertThatThrownBy(() -> templates.listForAccount("wma_missing"))
                .isInstanceOfSatisfying(AdminApiException.class,
                        e -> assertThat(e.code()).isEqualTo("whatsapp_account_not_found"));
        // A platform-account template is not reachable through the other account.
        assertThatThrownBy(() -> templates.share(account, onPlatform.id(), c, PLATFORM))
                .isInstanceOfSatisfying(AdminApiException.class,
                        e -> assertThat(e.code()).isEqualTo("template_not_found"));
        // The account is the workspace's own: it takes back an approved template it
        // stopped using without anyone sharing it.
        templates.submit(c, senderC, onOwn.id());
        when(twilio.fetchWhatsAppApproval(anyString(), any())).thenReturn(new ApprovalState("approved", null));
        templates.syncSender(c, senderC);
        templates.delete(c, senderC, onOwn.id(), WORKSPACE);
        assertThat(templates.findApproved(c, senderOf(c, senderC), d.name(), "es")).isEmpty();
        assertThat(templates.declare(c, senderC, d, WORKSPACE).id()).isEqualTo(onOwn.id());
        assertThat(templates.findApproved(c, senderOf(c, senderC), d.name(), "es")).isPresent();

        assertThat(templates.listAccounts()).extracting(WhatsAppTemplateService.Account::accountKey)
                .startsWith(TemplateAccounts.PLATFORM).contains(account);
    }

    @Test
    void platform_admin_routes_declare_share_and_list() {
        String name = "sl_http_" + suffix();
        var decl = new WhatsAppTemplateDtos.TemplateDeclaration(name, "es", TemplateCategory.UTILITY, "NONE",
                "Aviso {{1}}.", List.of(), new WhatsAppTemplateDtos.Samples(List.of("x"), null));
        var declared = client.put()
                .uri("/v1/admin/whatsapp-accounts/platform/templates/by-name/{n}", name)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(decl).exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppTemplateDtos.AccountTemplateResponse.class).returnResult().getResponseBody();
        assertThat(declared.holders()).isEmpty();
        assertThat(declared.status()).isEqualTo(TemplateStatus.DRAFT);

        var shared = client.put()
                .uri("/v1/admin/whatsapp-accounts/platform/templates/{id}/holders/{t}", declared.id(), a.value())
                .exchange().expectStatus().isOk()
                .expectBody(WhatsAppTemplateDtos.AccountTemplateResponse.class).returnResult().getResponseBody();
        assertThat(shared.holders()).extracting(WhatsAppTemplateDtos.HolderResponse::tenantId)
                .containsExactly(a.value());
        client.put()
                .uri("/v1/admin/whatsapp-accounts/platform/templates/{id}/holders/{t}", declared.id(), "no-such")
                .exchange().expectStatus().isBadRequest();

        // The workspace sees it through its sender, as before.
        var listed = client.get()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates", a.value(), senderA.value())
                .exchange().expectStatus().isOk()
                .expectBodyList(WhatsAppTemplateDtos.TemplateResponse.class).returnResult().getResponseBody();
        assertThat(listed).extracting(WhatsAppTemplateDtos.TemplateResponse::name).contains(name);
        assertThat(listed).allSatisfy(t -> {
            assertThat(t.tenantId()).isEqualTo(a.value());
            assertThat(t.senderId()).isEqualTo(senderA.value());
        });

        client.get().uri("/v1/admin/whatsapp-accounts").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].accountKey").isEqualTo("platform");
        client.delete()
                .uri("/v1/admin/whatsapp-accounts/platform/templates/{id}/holders/{t}", declared.id(), a.value())
                .exchange().expectStatus().isOk();
        client.delete().uri("/v1/admin/whatsapp-accounts/platform/templates/{id}", declared.id())
                .exchange().expectStatus().isNoContent();

        // A platform admin's declaration of a name another workspace holds shares it
        // (the bootstrap operator is one).
        Declaration taken = declaration("sl_key_" + suffix(), "Aviso {{1}}.");
        templates.declare(a, senderA, taken, WORKSPACE);
        var again = new WhatsAppTemplateDtos.TemplateDeclaration(taken.name(), "es", TemplateCategory.UTILITY,
                "NONE", "Aviso {{1}}.", List.of(), new WhatsAppTemplateDtos.Samples(List.of("ejemplo"), null));
        client.put()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates/by-name/{n}",
                        b.value(), senderB.value(), taken.name())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(again).exchange()
                .expectStatus().isOk()
                .expectBody(WhatsAppTemplateDtos.TemplateResponse.class)
                .value(t -> assertThat(t.shared()).isTrue());
    }

    @Test
    void hiding_changes_only_the_lists_and_a_shared_template_is_a_platform_admins_to_hide() {
        Declaration oldD = declaration("sl_hide_" + suffix() + "_v1", "Aviso {{1}}.");
        WhatsAppTemplate old = templates.declare(a, senderA, oldD, PLATFORM);
        Declaration bothD = declaration("sl_both_" + suffix(), "Hola {{1}}.");
        WhatsAppTemplate both = templates.declare(a, senderA, bothD, PLATFORM);
        templates.declare(b, senderB, bothD, PLATFORM);
        templates.submit(a, senderA, old.id());
        when(twilio.fetchWhatsAppApproval(anyString(), any())).thenReturn(new ApprovalState("approved", null));
        templates.syncSender(a, senderA);

        // A template other workspaces hold is not a workspace admin's to hide, and
        // the call changes nothing else it names.
        assertConflict(() -> templates.setHidden(a, senderA, List.of(old.id(), both.id()), true, WORKSPACE),
                "not_hideable");
        assertThat(templates.listForSender(a, senderA)).noneMatch(WhatsAppTemplate::hidden);
        assertThatThrownBy(() -> templates.setHidden(b, senderB, List.of(old.id()), true, WORKSPACE))
                .isInstanceOfSatisfying(AdminApiException.class,
                        e -> assertThat(e.code()).isEqualTo("template_not_found"));

        // The sole holder hides its own; it is still approved and still sent.
        assertThat(templates.setHidden(a, senderA, List.of(old.id(), old.id()), true, WORKSPACE))
                .singleElement().satisfies(t -> {
                    assertThat(t.hidden()).isTrue();
                    assertThat(t.hiddenBy()).isEqualTo(WORKSPACE.actor());
                    assertThat(t.status()).isEqualTo(TemplateStatus.APPROVED);
                });
        assertThat(templates.findApproved(a, senderOf(a, senderA), oldD.name(), "es")).isPresent();
        // Hiding it again keeps who hid it.
        assertThat(templates.setHidden(a, senderA, List.of(old.id()), true, PLATFORM).get(0).hiddenBy())
                .isEqualTo(WORKSPACE.actor());

        // A platform admin hides the shared one; every holder's list says so.
        assertThat(templates.setHiddenForAccount(TemplateAccounts.PLATFORM, List.of(both.id()), true, PLATFORM))
                .singleElement().satisfies(t -> assertThat(t.template().hidden()).isTrue());
        assertThat(templates.listForSender(b, senderB)).singleElement()
                .satisfies(t -> assertThat(t.hidden()).isTrue());
        assertThat(templates.listAccounts().get(0).hiddenCount()).isGreaterThanOrEqualTo(2);

        templates.setHiddenForAccount(TemplateAccounts.PLATFORM, List.of(old.id(), both.id()), false, PLATFORM);
        assertThat(templates.listForSender(a, senderA)).noneMatch(WhatsAppTemplate::hidden);
        assertThat(jdbc.queryForObject("SELECT hidden_by FROM whatsapp_templates WHERE id = ?", String.class,
                old.id().value())).isNull();
    }

    @Test
    void several_templates_are_shared_with_several_workspaces_in_one_go() {
        WhatsAppTemplate t1 = templates.declare(a, senderA, declaration("sl_many_" + suffix(), "Uno {{1}}."),
                WORKSPACE);
        WhatsAppTemplate t2 = templates.declare(a, senderA, declaration("sl_many_" + suffix(), "Dos {{1}}."),
                WORKSPACE);

        // One template outside the account and nobody gets anything.
        assertThatThrownBy(() -> templates.shareAll(TemplateAccounts.PLATFORM,
                List.of(t1.id(), new TemplateId(Long.MAX_VALUE)), List.of(b), PLATFORM))
                .isInstanceOfSatisfying(AdminApiException.class,
                        e -> assertThat(e.code()).isEqualTo("template_not_found"));
        assertThat(holders(t1)).containsExactly(a.value());

        var shared = templates.shareAll(TemplateAccounts.PLATFORM, List.of(t1.id(), t2.id(), t1.id()),
                List.of(b, c, b, a), PLATFORM);
        assertThat(shared).extracting(row -> row.template().id()).containsExactly(t1.id(), t2.id());
        assertThat(shared).allSatisfy(row -> assertThat(row.holders()).extracting(WhatsAppTemplateService.Holder::tenantId)
                .containsExactlyInAnyOrder(a.value(), b.value(), c.value()));
        assertThat(templates.listForSender(c, senderC)).extracting(WhatsAppTemplate::id)
                .containsExactlyInAnyOrder(t1.id(), t2.id());
        // Once per new pair; the workspace that already held them is left alone.
        String events = "SELECT count(*) FROM whatsapp_template_holder_events WHERE template_id IN (?, ?)";
        assertThat(jdbc.queryForObject(events, Integer.class, t1.id().value(), t2.id().value())).isEqualTo(6);
        templates.shareAll(TemplateAccounts.PLATFORM, List.of(t1.id(), t2.id()), List.of(b, c), PLATFORM);
        assertThat(jdbc.queryForObject(events, Integer.class, t1.id().value(), t2.id().value())).isEqualTo(6);

        client.post().uri("/v1/admin/whatsapp-accounts/platform/templates/share")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("templateIds", List.of(t1.id().value()), "tenantIds", List.of(a.value(), b.value())))
                .exchange().expectStatus().isOk()
                .expectBodyList(WhatsAppTemplateDtos.AccountTemplateResponse.class)
                .value(rows -> assertThat(rows).singleElement().satisfies(r -> assertThat(r.holders())
                        .extracting(WhatsAppTemplateDtos.HolderResponse::tenantId)
                        .containsExactlyInAnyOrder(a.value(), b.value(), c.value())));
        client.post().uri("/v1/admin/whatsapp-accounts/platform/templates/share")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("templateIds", List.of(t1.id().value()), "tenantIds", List.of("no-such")))
                .exchange().expectStatus().isBadRequest();
        client.post().uri("/v1/admin/whatsapp-accounts/platform/templates/share")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("templateIds", List.of(t1.id().value()), "tenantIds", List.of()))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void hide_and_unhide_routes() {
        WhatsAppTemplate t = templates.declare(a, senderA, declaration("sl_route_" + suffix(), "Aviso {{1}}."),
                WORKSPACE);
        client.post().uri("/v1/admin/whatsapp-accounts/platform/templates/hide")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("templateIds", List.of(t.id().value())))
                .exchange().expectStatus().isOk()
                .expectBodyList(WhatsAppTemplateDtos.AccountTemplateResponse.class)
                .value(rows -> assertThat(rows).singleElement().satisfies(r -> {
                    assertThat(r.hidden()).isTrue();
                    assertThat(r.hiddenAt()).isNotNull();
                    assertThat(r.hiddenBy()).isNotBlank();
                }));
        client.get().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates", a.value(), senderA.value())
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].hidden").isEqualTo(true);

        client.post().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates/unhide", a.value(), senderA.value())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("templateIds", List.of(t.id().value())))
                .exchange().expectStatus().isOk()
                .expectBodyList(WhatsAppTemplateDtos.TemplateResponse.class)
                .value(rows -> assertThat(rows).singleElement()
                        .satisfies(r -> assertThat(r.hidden()).isFalse()));

        client.post().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates/hide", a.value(), senderA.value())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("templateIds", List.of()))
                .exchange().expectStatus().isBadRequest();
        client.post().uri("/v1/admin/whatsapp-accounts/platform/templates/hide")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("templateIds", List.of(Long.MAX_VALUE)))
                .exchange().expectStatus().isNotFound();
    }

    /* ------------------------------ helpers ------------------------------- */

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static Declaration declaration(String name, String body) {
        return new Declaration(name, "es", TemplateCategory.UTILITY, "NONE", body, List.of(),
                List.of("ejemplo"), null);
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AdminApiException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    private List<String> holders(WhatsAppTemplate t) {
        return jdbc.queryForList("SELECT tenant_id FROM whatsapp_template_holders WHERE template_id = ? "
                + "ORDER BY tenant_id", String.class, t.id().value());
    }

    private WhatsAppSender senderOf(Ids.TenantId tenant, SenderId id) {
        return senders.requireForTenant(tenant, id);
    }

    private Ids.TenantId tenant(String id) {
        var req = new AdminDtos.TenantRequest(id, id, "https://" + id + ".example",
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req).exchange().expectStatus().isCreated();
        return new Ids.TenantId(id);
    }

    private SenderId sender(Ids.TenantId tenant) {
        var req = new WhatsAppSenderDtos.SenderRequest(
                "+1555" + (9000000 + PHONES.incrementAndGet()) , "Shared Co",
                new WhatsAppSenderDtos.BusinessProfileRequest(
                        "Shared Inc.", "Shared Co", "ops@shared.example",
                        "Address", "https://shared.example", "RETAIL", "Stuff."),
                null);
        var res = client.post().uri("/v1/admin/tenants/{t}/whatsapp-senders", tenant.value())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req).exchange()
                .expectStatus().isCreated()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class).returnResult().getResponseBody();
        return new SenderId(res.id());
    }
}

package com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords;

import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppKeywordActionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppKeywordActionRepository;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpEgressPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tracker C6/C8 P5: normalisation and whole-word prefix matching of keyword actions. */
class KeywordActionServiceTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("acme");
    private static final SenderId SENDER = new SenderId("wa_1");
    private static final String URL = "https://banca.example/integrations/keywords";

    private WhatsAppKeywordActionRepository repo;
    private KeywordActionService service;

    @BeforeEach
    void setUp() {
        repo = mock(WhatsAppKeywordActionRepository.class);
        service = new KeywordActionService(repo, workspaceSenders(), egress(false));
        when(repo.findByTenantIdAndSenderIdOrderByIdAsc("acme", "wa_1")).thenReturn(List.of(
                new WhatsAppKeywordActionEntity("acme", "wa_1", "TICKET", URL),
                new WhatsAppKeywordActionEntity("acme", "wa_1", "NO ES MIO", URL),
                new WhatsAppKeywordActionEntity("acme", "wa_1", "NO MAS TICKETS", URL)));
    }

    @Test
    void normalises_trim_spaces_case_and_accents() {
        assertThat(KeywordTextNormalizer.normalize("  tícket   7g4k2 ")).isEqualTo("TICKET 7G4K2");
        assertThat(KeywordTextNormalizer.normalize("No es mío")).isEqualTo("NO ES MIO");
        assertThat(KeywordTextNormalizer.normalize(null)).isEmpty();
    }

    @Test
    void matches_the_prefix_as_a_whole_word_only() {
        assertThat(service.match(TENANT, SENDER, "ticket 7G4K2")).map(KeywordAction::prefix).contains("TICKET");
        assertThat(service.match(TENANT, SENDER, "TICKET")).map(KeywordAction::prefix).contains("TICKET");
        assertThat(service.match(TENANT, SENDER, "No es mío")).map(KeywordAction::prefix).contains("NO ES MIO");
        assertThat(service.match(TENANT, SENDER, "no  más   tickets por favor")).map(KeywordAction::prefix)
                .contains("NO MAS TICKETS");
        assertThat(service.match(TENANT, SENDER, "TICKETS 7G4K2")).isEmpty();
        assertThat(service.match(TENANT, SENDER, "mi ticket 7G4K2")).isEmpty();
        assertThat(service.match(TENANT, SENDER, "")).isEmpty();
        assertThat(service.match(TENANT, SENDER, null)).isEmpty();
    }

    @Test
    void longest_prefix_wins_when_several_match() {
        when(repo.findByTenantIdAndSenderIdOrderByIdAsc("acme", "wa_1")).thenReturn(List.of(
                new WhatsAppKeywordActionEntity("acme", "wa_1", "NO", URL + "/no"),
                new WhatsAppKeywordActionEntity("acme", "wa_1", "NO MAS TICKETS", URL + "/stop")));
        assertThat(service.match(TENANT, SENDER, "no mas tickets")).map(KeywordAction::forwardUrl)
                .contains(URL + "/stop");
        assertThat(service.match(TENANT, SENDER, "no gracias")).map(KeywordAction::forwardUrl)
                .contains(URL + "/no");
    }

    @Test
    void disabled_rows_do_not_match() {
        WhatsAppKeywordActionEntity off = new WhatsAppKeywordActionEntity("acme", "wa_1", "TICKET", URL);
        off.setEnabled(false);
        when(repo.findByTenantIdAndSenderIdOrderByIdAsc("acme", "wa_1")).thenReturn(List.of(off));
        assertThat(service.match(TENANT, SENDER, "TICKET 1")).isEmpty();
    }

    @Test
    void replace_all_deletes_then_writes_the_normalised_set_without_duplicates() {
        List<KeywordAction> saved = service.replaceAll(TENANT, SENDER, List.of(
                new KeywordAction("ticket", URL), new KeywordAction(" Ticket ", URL), new KeywordAction("No es mío", URL)));

        verify(repo).deleteByTenantIdAndSenderId("acme", "wa_1");
        ArgumentCaptor<WhatsAppKeywordActionEntity> rows = ArgumentCaptor.forClass(WhatsAppKeywordActionEntity.class);
        verify(repo, times(2)).save(rows.capture());
        assertThat(rows.getAllValues()).extracting(WhatsAppKeywordActionEntity::getPrefix)
                .containsExactly("TICKET", "NO ES MIO");
        assertThat(saved).extracting(KeywordAction::prefix).containsExactly("TICKET", "NO ES MIO");
    }

    @Test
    void forward_url_must_be_absolute_https() {
        assertThatThrownBy(() -> service.replaceAll(TENANT, SENDER, List.of(new KeywordAction("TICKET", "/relative"))))
                .isInstanceOf(AdminApiException.class)
                .extracting(e -> ((AdminApiException) e).code()).isEqualTo("bad_forward_url");
        assertThatThrownBy(() -> service.replaceAll(TENANT, SENDER, List.of(new KeywordAction("TICKET", "ftp://x/y"))))
                .isInstanceOf(AdminApiException.class);
        // The forward carries a service token: plaintext http is refused in production.
        assertThatThrownBy(() -> service.replaceAll(TENANT, SENDER,
                List.of(new KeywordAction("TICKET", "http://banca.example/integrations/keywords"))))
                .isInstanceOf(AdminApiException.class)
                .extracting(e -> ((AdminApiException) e).code()).isEqualTo("bad_forward_url");
        assertThatThrownBy(() -> new KeywordAction("", URL)).isInstanceOf(IllegalArgumentException.class);
        verify(repo, times(0)).save(any());
    }

    @Test
    void plain_http_is_admitted_only_under_the_dev_allow_private_networks_flag() {
        KeywordActionService dev = new KeywordActionService(repo, workspaceSenders(), egress(true));
        List<KeywordAction> saved = dev.replaceAll(TENANT, SENDER,
                List.of(new KeywordAction("TICKET", "http://localhost:8089/integrations/keywords")));
        assertThat(saved).hasSize(1);
        assertThatThrownBy(() -> dev.replaceAll(TENANT, SENDER, List.of(new KeywordAction("TICKET", "ftp://x/y"))))
                .isInstanceOf(AdminApiException.class);
    }

    private static HttpEgressPolicy egress(boolean allowPrivate) {
        YayaAgenticProperties props = new YayaAgenticProperties(
                "default", "default", null, null, null, null, null,
                new YayaAgenticProperties.HttpTools(List.of(), allowPrivate, Duration.ofSeconds(10)),
                null, null, null);
        return new HttpEgressPolicy(props);
    }

    /** A workspace's own number: keyword rows behave as before platform senders. */
    private static WhatsAppSenderService workspaceSenders() {
        WhatsAppSenderService senders = mock(WhatsAppSenderService.class);
        org.mockito.Mockito.when(senders.requireUsable(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(mock(com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender.class));
        return senders;
    }
}

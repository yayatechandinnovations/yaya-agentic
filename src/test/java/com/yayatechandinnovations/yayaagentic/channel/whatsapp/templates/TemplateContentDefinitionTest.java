package com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioMessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppTemplateEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppTemplateRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** §6.3 of whatsapp-ops-integration-design.md: the Twilio Content shape and
 *  Meta's template rules. */
class TemplateContentDefinitionTest {

    private final WhatsAppTemplateService service = new WhatsAppTemplateService(
            mock(WhatsAppTemplateRepository.class), mock(WhatsAppSenderService.class),
            mock(TwilioMessagingChannelProvider.class), mock(ProviderCredsResolver.class),
            new ObjectMapper(), "https://hub.example");

    @Test
    @SuppressWarnings("unchecked")
    void document_template_is_a_card_with_variable_media_and_button_payloads() {
        WhatsAppTemplateEntity e = new WhatsAppTemplateEntity("t", "wa", "sl_undo_request_v1", "es",
                "UTILITY", "Solicitud {{1}} pendiente.\nBoleto {{2}}.", 2);
        e.setHeaderKind("DOCUMENT");
        e.setButtonsJson("[\"Aprobar\",\"Rechazar\"]");
        e.setSamplesJson("{\"body\":[\"UR-1\",\"120\"],\"mediaFilename\":\"ticket-120.pdf\"}");
        e.setContentHash("abcdef1234");

        Map<String, Object> def = service.contentDefinition(e);

        assertThat(def.get("friendly_name")).isEqualTo("sl_undo_request_v1_es_abcdef12");
        Map<String, String> vars = (Map<String, String>) def.get("variables");
        assertThat(vars).containsEntry("1", "UR-1").containsEntry("3", "act:sample1")
                .containsEntry("4", "act:sample2").containsEntry("5", "sample/ticket-120.pdf");
        Map<String, Object> card = (Map<String, Object>) ((Map<String, Object>) def.get("types")).get("twilio/card");
        assertThat(card.get("media")).isEqualTo(List.of("https://hub.example/v1/media/{{5}}"));
        assertThat((List<Map<String, Object>>) card.get("actions"))
                .extracting(a -> a.get("id")).containsExactly("{{3}}", "{{4}}");
    }

    @Test
    void declarations_follow_meta_rules() {
        var ok = new WhatsAppTemplateService.Declaration("sl_ok_v1", "es", TemplateCategory.UTILITY, "NONE",
                "La solicitud {{1}} (boleto {{2}}) fue {{3}} por {{4}}. Listo.", List.of(),
                List.of("a", "b", "c", "d"), null);
        WhatsAppTemplateService.validateDeclaration(ok);

        assertThatThrownBy(() -> WhatsAppTemplateService.validateDeclaration(
                new WhatsAppTemplateService.Declaration("sl_x", "es", null, "NONE",
                        "{{1}} empieza con variable.", List.of(), List.of("a"), null)))
                .isInstanceOf(AdminApiException.class).hasMessageContaining("start or end");
        assertThatThrownBy(() -> WhatsAppTemplateService.validateDeclaration(
                new WhatsAppTemplateService.Declaration("sl_x", "es", null, "NONE",
                        "Hola {{1}}, {{2}} juntos.", List.of(), List.of("a", "b"), null)))
                .isInstanceOf(AdminApiException.class).hasMessageContaining("adjacent");
        assertThatThrownBy(() -> WhatsAppTemplateService.validateDeclaration(
                new WhatsAppTemplateService.Declaration("sl_x", "es", null, "DOCUMENT",
                        "Hola {{1}} fin.", List.of(), List.of("a"), null)))
                .isInstanceOf(AdminApiException.class).hasMessageContaining("mediaFilename");
        assertThatThrownBy(() -> WhatsAppTemplateService.validateDeclaration(
                new WhatsAppTemplateService.Declaration("sl_x", "es", null, "NONE",
                        "Hola {{1}} fin.", List.of("Un botón demasiado largo"), List.of("a"), null)))
                .isInstanceOf(AdminApiException.class).hasMessageContaining("button");
    }
}

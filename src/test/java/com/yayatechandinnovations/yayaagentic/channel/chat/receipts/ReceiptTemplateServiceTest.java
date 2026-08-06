package com.yayatechandinnovations.yayaagentic.channel.chat.receipts;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ReceiptTemplateEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ReceiptTemplateRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The closed element vocabulary and trait resolution
 * ({@code chat-experience-design.md} §7.3–§7.4): unknown element types are
 * rejected at authoring time (the renderer contract can't be broken from the
 * console), sections nest exactly one level, and the profile extension's
 * template ids resolve into full payloads for {@code StartResponse.traits}.
 */
class ReceiptTemplateServiceTest {

    private final ReceiptTemplateRepository repo = mock(ReceiptTemplateRepository.class);
    private final ReceiptTemplateService svc = new ReceiptTemplateService(repo);

    private static List<Map<String, Object>> okElements() {
        return List.of(
                Map.of("type", "header", "title", "{{store.name}}"),
                Map.of("type", "kv", "label", "{{drawLabel}}", "value", "{{drawDate}}"),
                Map.of("type", "section", "repeat", "sections", "elements",
                        List.of(Map.of("type", "table", "repeat", "rows",
                                "cols", List.of(Map.of("bind", "label"))))),
                Map.of("type", "barcode", "symbology", "code128", "value", "{{serial}}"),
                Map.of("type", "footer", "lines", List.of("{{disclaimer}}")));
    }

    @Test
    void validTemplateIsCreated() {
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        ReceiptTemplateEntity created =
                svc.create("t1", "Ticket", "numbered", 32, "58mm", okElements(), null);
        assertThat(created.getId()).startsWith("rt_");
        assertThat(created.getElements()).hasSize(5);
    }

    @Test
    void unknownElementTypeIsRejected() {
        assertThatThrownBy(() -> svc.create("t1", "X", null, 32, "58mm",
                List.of(Map.of("type", "hologram")), null))
                .isInstanceOf(ReceiptTemplateService.InvalidTemplateException.class)
                .hasMessageContaining("hologram");
    }

    @Test
    void nestedSectionsAreRejected() {
        List<Map<String, Object>> nested = List.of(Map.of("type", "section", "elements",
                List.of(Map.of("type", "section", "elements",
                        List.of(Map.of("type", "text"))))));
        assertThatThrownBy(() -> svc.create("t1", "X", null, 32, "58mm", nested, null))
                .isInstanceOf(ReceiptTemplateService.InvalidTemplateException.class)
                .hasMessageContaining("nest");
    }

    @Test
    void paperBoundsAreEnforced() {
        assertThatThrownBy(() -> svc.create("t1", "X", null, 10, "58mm", okElements(), null))
                .isInstanceOf(ReceiptTemplateService.InvalidTemplateException.class);
        assertThatThrownBy(() -> svc.create("t1", "X", null, 32, "A4", okElements(), null))
                .isInstanceOf(ReceiptTemplateService.InvalidTemplateException.class);
    }

    @Test
    void traitResolutionJoinsIdsToPayloadsAndKeepsBindings() {
        ReceiptTemplateEntity t = new ReceiptTemplateEntity("rt_1", "t1", "Ticket",
                null, 32, "58mm", okElements(), null);
        when(repo.findByTenantIdAndId("t1", "rt_1")).thenReturn(Optional.of(t));
        when(repo.findByTenantIdAndId("t1", "rt_missing")).thenReturn(Optional.empty());

        Map<String, Object> trait = svc.resolveTrait(new Ids.TenantId("t1"), Map.of(
                "templates", List.of("rt_1", "rt_missing"),
                "bindings", Map.of("some.tool", "rt_1")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resolved = (List<Map<String, Object>>) trait.get("templates");
        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0)).containsEntry("id", "rt_1").containsKeys("paper", "elements");
        assertThat(trait).containsKey("bindings");
    }

    @Test
    void malformedExtensionResolvesToNull() {
        assertThat(svc.resolveTrait(new Ids.TenantId("t1"), "not-a-map")).isNull();
    }

    @Test
    void defaultsSeedOnceForAnEmptyTenant() {
        List<ReceiptTemplateEntity> created = new java.util.ArrayList<>();
        when(repo.findByTenantIdOrderByCreatedAtDesc("t1")).thenAnswer(i -> List.copyOf(created));
        when(repo.save(any())).thenAnswer(i -> {
            created.add(i.getArgument(0));
            return i.getArgument(0);
        });

        List<ReceiptTemplateEntity> seeded = svc.ensureDefaults("t1");
        assertThat(seeded).hasSize(3);
        assertThat(seeded).extracting(ReceiptTemplateEntity::getName)
                .containsExactlyInAnyOrder("Sale receipt", "Numbered ticket", "Confirmation slip");

        // Second visit: no duplicates.
        assertThat(svc.ensureDefaults("t1")).hasSize(3);
    }

    @Test
    void everyDefaultPassesItsOwnValidation() {
        // Seeding runs through create(); a vocabulary drift in the defaults
        // must fail here, not on a tenant's first console visit.
        when(repo.findByTenantIdOrderByCreatedAtDesc("t1")).thenReturn(List.of());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        svc.ensureDefaults("t1"); // throws InvalidTemplateException on drift
    }
}

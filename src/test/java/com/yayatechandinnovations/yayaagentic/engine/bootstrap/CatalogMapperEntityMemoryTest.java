package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityKind;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityScope;
import com.yayatechandinnovations.yayaagentic.persistence.ToolEntity;
import com.yayatechandinnovations.yayaagentic.tool.BindingMode;
import com.yayatechandinnovations.yayaagentic.tool.OnConflict;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain-JUnit projection test for {@link CatalogMapper}. Asserts that a
 * {@link ToolEntity} with operator-declared captures + bindings JSON columns
 * projects to a {@link ToolDescriptor} whose internal records match. This is
 * the load side of the M2.10 persistence wiring — see
 * {@code docs/design/entity-memory-design.md} §11.
 *
 * <p>No Spring or Postgres: builds the entity by hand and exercises the
 * mapper directly. Fast feedback for the JSON shape stored in the DB.
 */
class CatalogMapperEntityMemoryTest {

    private final CatalogMapper mapper = new CatalogMapper(new ObjectMapper());

    @Test
    void empty_columns_produce_empty_lists() {
        ToolEntity e = beanTool("noop");
        // Defaults are "[]" from the entity's field initializer.
        ToolDescriptor d = mapper.toDescriptor(e);
        assertThat(d.captures()).isEmpty();
        assertThat(d.bindings()).isEmpty();
    }

    @Test
    void blank_columns_degrade_to_empty_lists() {
        ToolEntity e = beanTool("noop");
        e.setCapturesJson("");
        e.setBindingsJson("");
        ToolDescriptor d = mapper.toDescriptor(e);
        assertThat(d.captures()).isEmpty();
        assertThat(d.bindings()).isEmpty();
    }

    @Test
    void populated_columns_produce_typed_records() {
        ToolEntity e = beanTool("prepare-draft");
        e.setCapturesJson("[{"
                + "\"name\":\"draftId\","
                + "\"jsonPointer\":\"/draftId\","
                + "\"scope\":\"SESSION\","
                + "\"kind\":\"SINGLETON\","
                + "\"onConflict\":\"ASK_USER\","
                + "\"visibleToLlm\":true,"
                + "\"rollbackTool\":\"cancel-draft\","
                + "\"describeTool\":null"
                + "}]");
        e.setBindingsJson("[{"
                + "\"argName\":\"draftId\","
                + "\"entityName\":\"draftId\","
                + "\"mode\":\"OVERRIDE\""
                + "}]");

        ToolDescriptor d = mapper.toDescriptor(e);

        assertThat(d.captures()).hasSize(1);
        var cap = d.captures().get(0);
        assertThat(cap.name()).isEqualTo("draftId");
        assertThat(cap.jsonPointer()).isEqualTo("/draftId");
        assertThat(cap.scope()).isEqualTo(EntityScope.SESSION);
        assertThat(cap.kind()).isEqualTo(EntityKind.SINGLETON);
        assertThat(cap.onConflict()).isEqualTo(OnConflict.ASK_USER);
        assertThat(cap.visibleToLlm()).isTrue();
        assertThat(cap.rollbackTool()).isPresent();
        assertThat(cap.rollbackTool().get().value()).isEqualTo("cancel-draft");
        assertThat(cap.describeTool()).isEmpty();

        assertThat(d.bindings()).hasSize(1);
        var bind = d.bindings().get(0);
        assertThat(bind.argName()).isEqualTo("draftId");
        assertThat(bind.entityName()).isEqualTo("draftId");
        assertThat(bind.mode()).isEqualTo(BindingMode.OVERRIDE);

        assertThat(d.handler()).isInstanceOf(ToolHandlerRef.Bean.class);
    }

    @Test
    void malformed_columns_degrade_to_empty_lists_not_a_crash() {
        // A poisoned row must not take down the whole runtime catalog load —
        // CatalogMapper.readJson falls back to the supplied default on parse
        // failure (defensive load is documented behavior).
        ToolEntity e = beanTool("noop");
        e.setCapturesJson("not-json");
        e.setBindingsJson("[not-json");
        ToolDescriptor d = mapper.toDescriptor(e);
        assertThat(d.captures()).isEmpty();
        assertThat(d.bindings()).isEmpty();
    }

    private static ToolEntity beanTool(String id) {
        ToolEntity e = new ToolEntity("admin-test", id, 1,
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", "BEAN");
        e.setHandlerBeanName("echoTool");
        return e;
    }
}

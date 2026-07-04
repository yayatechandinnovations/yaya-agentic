package com.yayatechandinnovations.yayaagentic.outreach;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.profile.Capability;
import com.yayatechandinnovations.yayaagentic.tool.EntityBinding;
import com.yayatechandinnovations.yayaagentic.tool.HttpToolSpec;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers the built-in system-reply tool + capability (§8). This is the tool
 * the agent invokes to carry a user's answer back to the originating system.
 *
 * <p>The security division is the whole point (§8): the LLM decides <em>whether</em>
 * and <em>what</em> to reply — its input schema exposes only {@code message} and
 * {@code decision}. The engine decides <em>to whom</em>: {@code originatorRef} and
 * {@code subject} are pinned from entity memory via {@link EntityBinding}, so a
 * prompt injection cannot redirect the reply. The callback is an HTTP tool with
 * {@code SERVICE_TOKEN} forwarding — the backend service credential calls the
 * system back, never the user's token (non-negotiable #6).
 *
 * <p>{@code @Order(200)} runs after {@code RuntimeCatalogLoader} ({@code @Order(100)})
 * so the platform tool is present alongside tenant-loaded tools. A tenant profile
 * that wants mediation lists the {@link #REPLY_CAPABILITY} capability and points
 * its sender's {@code responseTool} at {@link #RESPONSE_TOOL}.
 */
@Component
@Order(200)
public class SystemReplyToolBootstrap implements ApplicationRunner {

    public static final Ids.ToolId RESPONSE_TOOL = new Ids.ToolId("system.reply");
    public static final Ids.CapabilityId REPLY_CAPABILITY = new Ids.CapabilityId("sys.reply");
    public static final String REPLY_SCOPE = "system:reply";

    /** Entity keys the correlation is captured under; bound onto the tool call. */
    public static final String CORRELATION_REF_ENTITY = "system.correlation.originatorRef";
    public static final String CORRELATION_SUBJECT_ENTITY = "system.correlation.subject";

    private static final String INPUT_SCHEMA = """
            {"type":"object","required":["message"],
             "properties":{
               "message":{"type":"string","description":"the reply to send back to the system"},
               "decision":{"type":"string","enum":["CONFIRM","DECLINE","OTHER"]}}}""";
    private static final String OUTPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\"}}}";
    private static final String BODY_TEMPLATE = """
            {"originatorRef":"{originatorRef}","subject":"{subject}",\
            "message":"{message}","decision":"{decision}"}""";

    private final M0Catalog catalog;

    public SystemReplyToolBootstrap(M0Catalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        HttpToolSpec spec = new HttpToolSpec(
                HttpToolSpec.HttpMethod.POST,
                // Path-only (host resolves from the tenant's integration base URL).
                "/integrations/callbacks/{originatorRef}",
                Map.of("Content-Type", "application/json"),
                new HttpToolSpec.BodyTemplate("application/json", BODY_TEMPLATE),
                new HttpToolSpec.ResponseProjection(null, Map.of()),
                HttpToolSpec.AuthForwarding.SERVICE_TOKEN);

        catalog.registerTool(new ToolDescriptor(
                RESPONSE_TOOL, INPUT_SCHEMA, OUTPUT_SCHEMA,
                new PermissionRequirement(Set.of(REPLY_SCOPE), List.of(), null),
                new ToolHandlerRef.Http(spec),
                ToolPolicy.defaults(),
                List.of(),
                // Engine-pinned routing — the model never supplies these.
                List.of(EntityBinding.override("originatorRef", CORRELATION_REF_ENTITY),
                        EntityBinding.override("subject", CORRELATION_SUBJECT_ENTITY))));

        catalog.registerCapability(new Capability(
                REPLY_CAPABILITY,
                "Reply to the system",
                "Send the user's answer back to the system that messaged them.",
                "When the user wants to answer or confirm/decline a message a system sent them, "
                        + "call system.reply with their message. Never invent the recipient.",
                List.of(RESPONSE_TOOL),
                List.of(),
                PermissionRequirement.none()));
    }
}

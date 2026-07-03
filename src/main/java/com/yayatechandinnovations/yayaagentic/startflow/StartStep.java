package com.yayatechandinnovations.yayaagentic.startflow;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.List;
import java.util.Map;

/**
 * One unit of a {@link StartFlow}. Sealed so the runner's dispatch is total:
 * {@link RunTool} (dispatch a tool), {@link Decision} (pure routing), and
 * {@link Elicit} (ask the user, pause, resume on their reply). Persisted as
 * JSONB with a {@code kind} discriminator. See
 * {@code docs/design/session-start-flows.md} §5.1.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StartStep.RunTool.class, name = "RunTool"),
        @JsonSubTypes.Type(value = StartStep.Decision.class, name = "Decision"),
        @JsonSubTypes.Type(value = StartStep.Elicit.class, name = "Elicit"),
})
public sealed interface StartStep permits StartStep.RunTool, StartStep.Decision, StartStep.Elicit {

    /** Stable within a flow; referenced by {@link ValueSource.StepOutput} and branch targets. */
    String key();

    /**
     * Run a tool deterministically: assemble args from {@code inputs}, dispatch
     * through the shared {@code ToolExecutor} (schema + Authorizer), write
     * {@code captures} to the blackboard, then route via {@code next}.
     *
     * @param next empty ⇒ fall through to the next authored step
     */
    record RunTool(
            String key,
            Ids.ToolId tool,
            List<ArgBinding> inputs,
            List<OutputCapture> captures,
            List<Branch> next,
            StepErrorPolicy onError
    ) implements StartStep {

        public RunTool {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("RunTool.key must be non-blank");
            if (tool == null) throw new IllegalArgumentException("RunTool.tool must be set");
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            captures = captures == null ? List.of() : List.copyOf(captures);
            next = next == null ? List.of() : List.copyOf(next);
            if (onError == null) onError = StepErrorPolicy.abort();
        }
    }

    /** Pure routing, no side effect: pick a target from {@code branches}, else {@code defaultTarget}. */
    record Decision(
            String key,
            List<Branch> branches,
            BranchTarget defaultTarget
    ) implements StartStep {

        public Decision {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("Decision.key must be non-blank");
            branches = branches == null ? List.of() : List.copyOf(branches);
        }
    }

    /**
     * Ask the user something and pause the flow. The prompt (and quick replies)
     * render over the blackboard; an optional {@code uiHintKind} drives a client
     * widget (e.g. {@code "otp_entry"}). On the next inbound message the runner
     * writes the reply text to {@code captureTarget}, then routes via {@code next}.
     *
     * @param captureTarget where the user's reply text lands — an {@code Entity}
     *                      (shareable with the LLM) or a {@code StepVar} (scratch,
     *                      never in a prompt — right for OTP codes)
     */
    record Elicit(
            String key,
            String promptTemplate,
            List<String> quickReplyTemplates,
            String uiHintKind,
            Map<String, Object> uiHintPayload,
            CaptureTarget captureTarget,
            List<Branch> next
    ) implements StartStep {

        public Elicit {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("Elicit.key must be non-blank");
            if (promptTemplate == null || promptTemplate.isBlank()) {
                throw new IllegalArgumentException("Elicit.promptTemplate must be non-blank");
            }
            quickReplyTemplates = quickReplyTemplates == null ? List.of() : List.copyOf(quickReplyTemplates);
            uiHintPayload = uiHintPayload == null ? Map.of() : Map.copyOf(uiHintPayload);
            next = next == null ? List.of() : List.copyOf(next);
        }
    }
}

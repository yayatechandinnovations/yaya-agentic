package com.yayatechandinnovations.yayaagentic.tool.dispatch;

import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolExecutor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Entry point the engine calls for every tool invocation. Order:
 * <ol>
 *   <li>{@link ToolSchemaValidator} splits violations into
 *       missing-required-field vs. other. Missing required fields →
 *       {@link Outcome.NeedsInput} so the engine can ask the user a
 *       focused question instead of dispatching with bad args.</li>
 *   <li>Other violations → {@link Outcome.Dispatched} with a FAILED
 *       result carrying the messages (no point dispatching a
 *       schema-violating call).</li>
 *   <li>Sealed-switch on {@link ToolHandlerRef} picks the dispatcher.</li>
 * </ol>
 * Authorization has already happened upstream in the engine — by the time
 * the executor runs, the call is authorized.
 */
@Component
public class DefaultToolExecutor implements ToolExecutor {

    private final ToolSchemaValidator validator;
    private final BeanToolDispatcher beanDispatcher;
    private final HttpToolDispatcher httpDispatcher;

    public DefaultToolExecutor(ToolSchemaValidator validator,
                               BeanToolDispatcher beanDispatcher,
                               HttpToolDispatcher httpDispatcher) {
        this.validator = validator;
        this.beanDispatcher = beanDispatcher;
        this.httpDispatcher = httpDispatcher;
    }

    @Override
    public Outcome execute(ToolDescriptor descriptor,
                           Map<String, Object> args,
                           ExecutionContext ctx,
                           String suggestedCallId) {
        String callId = (suggestedCallId == null || suggestedCallId.isBlank())
                ? UUID.randomUUID().toString()
                : suggestedCallId;

        ToolSchemaValidator.Result violations = validator.validate(descriptor, args);
        if (violations.needsElicitation()) {
            return new Outcome.NeedsInput(violations.missingRequired(), violations.otherViolations());
        }
        if (!violations.otherViolations().isEmpty()) {
            return new Outcome.Dispatched(new Turn.ToolResult(
                    callId, Turn.ToolResult.Status.FAILED, null,
                    "input schema: " + String.join("; ", violations.otherViolations())));
        }

        Turn.ToolResult result = switch (descriptor.handler()) {
            case ToolHandlerRef.Bean bean -> beanDispatcher.dispatch(bean, descriptor, args, ctx, callId);
            case ToolHandlerRef.Http http -> httpDispatcher.dispatch(http, descriptor, args, ctx, callId);
        };
        return new Outcome.Dispatched(result);
    }
}

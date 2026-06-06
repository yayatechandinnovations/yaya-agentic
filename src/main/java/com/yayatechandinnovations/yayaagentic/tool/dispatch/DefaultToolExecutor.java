package com.yayatechandinnovations.yayaagentic.tool.dispatch;

import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolExecutor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entry point the engine calls for every tool invocation. Order:
 * <ol>
 *   <li>{@link ToolSchemaValidator} validates args against the tool's
 *       declared input schema. Failures return FAILED with a
 *       human-readable reason (engine surfaces it as a clarifying turn).</li>
 *   <li>Sealed-switch on {@link ToolHandlerRef} picks the dispatcher:
 *       {@link BeanToolDispatcher} for {@code Bean}, {@link HttpToolDispatcher}
 *       for {@code Http}. New variants would be a compile error here.</li>
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
    public Turn.ToolResult execute(ToolDescriptor descriptor,
                                   Map<String, Object> args,
                                   ExecutionContext ctx) {
        String callId = UUID.randomUUID().toString();

        List<String> violations = validator.validateInput(descriptor, args);
        if (!violations.isEmpty()) {
            return new Turn.ToolResult(callId, Turn.ToolResult.Status.FAILED, null,
                    "input schema: " + String.join("; ", violations));
        }

        return switch (descriptor.handler()) {
            case ToolHandlerRef.Bean bean -> beanDispatcher.dispatch(bean, descriptor, args, ctx, callId);
            case ToolHandlerRef.Http http -> httpDispatcher.dispatch(http, descriptor, args, ctx, callId);
        };
    }
}

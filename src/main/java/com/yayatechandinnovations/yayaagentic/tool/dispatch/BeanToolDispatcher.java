package com.yayatechandinnovations.yayaagentic.tool.dispatch;

import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolException;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * In-process dispatch. Resolves the bean by name from the Spring context
 * and invokes its {@link ToolHandler} contract. Schema validation has
 * already happened in {@link DefaultToolExecutor}.
 */
@Component
public class BeanToolDispatcher {

    private final ApplicationContext context;

    public BeanToolDispatcher(ApplicationContext context) {
        this.context = context;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Turn.ToolResult dispatch(ToolHandlerRef.Bean ref,
                                    ToolDescriptor descriptor,
                                    Map<String, Object> args,
                                    ExecutionContext ctx,
                                    String callId) {
        try {
            ToolHandler handler = context.getBean(ref.beanName(), ToolHandler.class);
            Object output = handler.execute(args, ctx);
            return new Turn.ToolResult(callId, Turn.ToolResult.Status.OK, output, null);
        } catch (NoSuchBeanDefinitionException e) {
            return new Turn.ToolResult(callId, Turn.ToolResult.Status.FAILED, null,
                    "bean '" + ref.beanName() + "' for tool '" + descriptor.id().value() + "' not found");
        } catch (ToolException e) {
            return new Turn.ToolResult(callId, Turn.ToolResult.Status.FAILED, null, e.getMessage());
        } catch (RuntimeException e) {
            return new Turn.ToolResult(callId, Turn.ToolResult.Status.FAILED, null,
                    "tool '" + descriptor.id().value() + "' threw: " + e.getMessage());
        }
    }
}

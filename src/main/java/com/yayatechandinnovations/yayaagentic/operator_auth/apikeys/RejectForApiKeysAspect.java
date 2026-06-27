package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import com.yayatechandinnovations.yayaagentic.operator_auth.Operator;
import com.yayatechandinnovations.yayaagentic.operator_auth.web.OperatorContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.lang.reflect.Method;

/**
 * Central enforcement of {@link RejectForApiKeys} (§6.3). If the current
 * operator is API-key-sourced, the annotated handler is refused before it runs
 * — even for an {@code OWNER}-role key. Keys cannot mint keys, delete
 * workspaces, hit the session endpoints, or touch auth-strategy config; those
 * stay behind an interactive human session.
 *
 * <p>The exception is thrown synchronously; Spring routes it to the same
 * exception handlers a controller throw would, so it works for both reactive
 * and blocking return types.</p>
 */
@Aspect
@Component
public class RejectForApiKeysAspect {

    @Around("@annotation(com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.RejectForApiKeys) "
            + "|| @within(com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.RejectForApiKeys)")
    public Object guard(ProceedingJoinPoint pjp) throws Throwable {
        ServerWebExchange exchange = findExchange(pjp.getArgs());
        if (exchange != null && isApiKey(exchange)) {
            RejectForApiKeys ann = resolveAnnotation(pjp);
            exchange.getAttributes().put("yaya.apikey.deny_reason", ann.reason());
            throw new ResponseStatusException(ann.status(), ann.reason());
        }
        return pjp.proceed();
    }

    private static boolean isApiKey(ServerWebExchange exchange) {
        return OperatorContext.current(exchange)
                .map(op -> op.source() == Operator.Source.API_KEY)
                .orElse(false);
    }

    private static ServerWebExchange findExchange(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof ServerWebExchange e) return e;
        }
        return null;
    }

    private static RejectForApiKeys resolveAnnotation(ProceedingJoinPoint pjp) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RejectForApiKeys onMethod = method.getAnnotation(RejectForApiKeys.class);
        if (onMethod != null) return onMethod;
        return method.getDeclaringClass().getAnnotation(RejectForApiKeys.class);
    }
}

package com.yayatechandinnovations.yayaidentity.policies;

import java.util.Map;

/**
 * Evaluates a policy condition against attribute bags (design §7, SPI §15). The
 * expression language is small and SANDBOXED — no I/O, no arbitrary code, bounded
 * by the (small) expression size — so a policy can never run wild.
 */
public interface ConditionEvaluator {

    /** A null/blank condition is unconditional (always matches). {@code data} holds the subject/resource/env bags. */
    boolean matches(String conditionJson, Map<String, Object> data);
}

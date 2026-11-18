package com.yayatechandinnovations.yayaagentic.engine.handoff;

import com.yayatechandinnovations.yayaagentic.engine.secret.SecretPrompts;

import java.time.Instant;
import java.util.Map;

/**
 * Which frame a session still waits an answer for, read from its working
 * memory the way {@code latest} reads it: {@code confirm}, {@code secret},
 * {@code plan}, or null. At most one is ever pending.
 */
public final class PendingFrame {

    public static final String CONFIRM = "confirm";
    public static final String PLAN = "plan";
    public static final String SECRET = "secret";

    private PendingFrame() {}

    @SuppressWarnings("unchecked")
    public static String of(Map<String, Object> workingMemory, Instant now) {
        if (workingMemory == null) return null;
        if (workingMemory.get("pending_confirm") instanceof Map<?, ?> confirm
                && confirm.get("frame") instanceof Map<?, ?> frame && !frame.isEmpty()) {
            return CONFIRM;
        }
        if (workingMemory.get(SecretPrompts.SLOT) instanceof Map<?, ?> secret
                && SecretPrompts.view((Map<String, Object>) secret, now) != null) {
            return SECRET;
        }
        if (workingMemory.get("pending_plan") instanceof Map<?, ?> plan
                && "PENDING".equals(plan.get("state"))
                && plan.get("frame") instanceof Map<?, ?> frame && !frame.isEmpty()) {
            return PLAN;
        }
        return null;
    }
}

package com.yayatechandinnovations.yayaagentic.headless;

/**
 * Ends the turn of a headless run from inside the engine: something came up
 * that only a person could answer, or the turn ran out of tool rounds. Nobody
 * is present, so the engine never frames a question, a card or a prompt for
 * such a turn and writes no pending state — it fails the turn with one of
 * these codes, which becomes the run's {@code failure.code}.
 */
public class HeadlessStop extends RuntimeException {

    /** A confirmation, a secret prompt, a step-up, an elicitation, a picker or an entity conflict. */
    public static final String NEEDS_INTERACTION = "needs_interaction";
    public static final String TOOL_ROUNDS_EXHAUSTED = "tool_rounds_exhausted";

    private final String code;

    public HeadlessStop(String code, String detail) {
        super(detail, null, false, false);
        this.code = code;
    }

    public static HeadlessStop needsInteraction(String what) {
        return new HeadlessStop(NEEDS_INTERACTION, what);
    }

    public String code() { return code; }
}

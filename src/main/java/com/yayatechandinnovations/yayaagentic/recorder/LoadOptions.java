package com.yayatechandinnovations.yayaagentic.recorder;

public record LoadOptions(boolean summarized, int maxTurns, boolean includeToolPayloads) {
    public static LoadOptions verbatim() {
        return new LoadOptions(false, Integer.MAX_VALUE, true);
    }
    public static LoadOptions summarizedForPrompt(int maxTurns) {
        return new LoadOptions(true, maxTurns, false);
    }
}

package com.yayatechandinnovations.yayaagentic.recorder;

public record TurnRange(Integer fromIndex, Integer toIndex, Integer limit) {
    public static TurnRange last(int n) { return new TurnRange(null, null, n); }
    public static TurnRange all() { return new TurnRange(null, null, null); }
}

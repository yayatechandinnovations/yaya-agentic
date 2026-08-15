package com.yayatechandinnovations.yayaagentic.engine.confirm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The network entry point is Spanish (design §3.2/§5): "Sí" MUST resolve a
 * pending confirm, and Spanish negatives must cancel.
 */
class ConfirmDetectorSpanishTest {

    private final ConfirmDetector detector = new ConfirmDetector();

    @Test
    void recognizesSpanishAffirmatives() {
        for (String yes : new String[]{"Sí", "sí", "si", "Claro", "dale", "confirmar", "de acuerdo"}) {
            assertEquals(ConfirmDetector.Signal.CONFIRM, detector.detect(yes), yes);
        }
    }

    @Test
    void recognizesSpanishNegatives() {
        for (String no : new String[]{"no", "cancela", "cancelar", "espera"}) {
            assertEquals(ConfirmDetector.Signal.CANCEL, detector.detect(no), no);
        }
    }

    @Test
    void stillRecognizesEnglish() {
        assertEquals(ConfirmDetector.Signal.CONFIRM, detector.detect("yes"));
        assertEquals(ConfirmDetector.Signal.CANCEL, detector.detect("cancel"));
    }
}

package com.yayatechandinnovations.yayaagentic.knowledge.extract;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

/**
 * Determines a file's content type from its bytes, not its declared header or
 * extension. The client-supplied content type is advisory; this value is
 * authoritative (design §7). A {@code .txt} that is really a PDF is treated as a
 * PDF; a renamed executable is caught here and rejected by the allow-list.
 */
@Component
public class ContentTypeSniffer {

    private final Tika tika = new Tika();

    public String sniff(byte[] data, String filename) {
        return tika.detect(data, filename == null ? "" : filename);
    }
}

package com.yayatechandinnovations.yayaagentic.knowledge.loaders;

import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeLoader;
import com.yayatechandinnovations.yayaagentic.knowledge.SourceLocation;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Simplest loader — turns an {@link SourceLocation.Inline} payload into
 * {@link RawDocument}s without touching the filesystem or network. Useful
 * for tests, bootstrap seeds, and a demo profile that ships its content in
 * config rather than alongside a real document corpus.
 */
@Component
public class InlineLoader implements KnowledgeLoader {

    @Override
    public boolean supports(SourceLocation location) {
        return location instanceof SourceLocation.Inline;
    }

    @Override
    public Stream<RawDocument> load(SourceLocation location, IngestionContext ctx) {
        SourceLocation.Inline inline = (SourceLocation.Inline) location;
        return inline.docs().stream().map(blob -> {
            Map<String, Object> meta = new HashMap<>();
            meta.put("loader", "inline");
            meta.put("contentType", blob.contentType());
            return new RawDocument(
                    blob.id(),
                    "inline:" + blob.id(),
                    blob.id(),
                    blob.contentType() == null ? "text/plain" : blob.contentType(),
                    blob.text(),
                    meta);
        });
    }
}

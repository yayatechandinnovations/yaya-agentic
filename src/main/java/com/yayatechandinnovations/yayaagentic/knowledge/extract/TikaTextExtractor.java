package com.yayatechandinnovations.yayaagentic.knowledge.extract;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Universal {@link TextExtractor} backed by Apache Tika (embedded, in-process —
 * no server, no paid API). Handles PDF/DOCX/PPTX/XLSX/HTML/RTF/ODF and plain
 * text/markdown.
 *
 * <p>Page provenance: Tika's PDF parser emits one {@code <div class="page">}
 * per page in its XHTML stream. {@link PageCollectingHandler} splits on those
 * boundaries so each {@link Page} maps to a real document page, which the loader
 * threads into chunk metadata for "filename, p.N" citations (design §8). Formats
 * with no page structure collapse to a single page.
 */
@Component
public class TikaTextExtractor implements TextExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(TikaTextExtractor.class);

    @Override
    public boolean supports(String contentType) {
        // Sole extractor in this increment — Tika auto-detects and handles the
        // full allow-listed format set, so it is the universal fallback.
        return true;
    }

    @Override
    public Extracted extract(InputStream in, String filename, String contentType) {
        Metadata metadata = new Metadata();
        if (filename != null) metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        if (contentType != null) metadata.set(Metadata.CONTENT_TYPE, contentType);

        PageCollectingHandler handler = new PageCollectingHandler();
        try (in) {
            new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
        } catch (Exception e) {
            // A parse failure for one file must not abort the whole ingest run;
            // surface it as empty text and let the orchestrator record the error.
            LOG.warn("Tika extraction failed for {} ({}): {}", filename, contentType, e.toString());
            return new Extracted(List.of(), Map.of("extractionError", e.toString()));
        }

        List<Page> pages = handler.pages();
        Map<String, Object> meta = new HashMap<>();
        meta.put("pageCount", pages.size());
        String title = metadata.get(TikaCoreProperties.TITLE);
        if (title != null && !title.isBlank()) meta.put("documentTitle", title);
        String sniffed = metadata.get(Metadata.CONTENT_TYPE);
        if (sniffed != null) meta.put("tikaContentType", sniffed);
        return new Extracted(pages, meta);
    }

    /**
     * SAX handler that accumulates text per PDF page div and inserts newlines at
     * block boundaries so paragraphs don't run together. Non-paginated documents
     * produce exactly one page.
     */
    private static final class PageCollectingHandler extends DefaultHandler {

        private static final Set<String> BLOCK = Set.of(
                "p", "div", "br", "li", "tr", "table", "ul", "ol",
                "h1", "h2", "h3", "h4", "h5", "h6", "section", "article");

        private final List<Page> pages = new ArrayList<>();
        private final StringBuilder buf = new StringBuilder();
        private int pageNo = 0;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            String tag = local(localName, qName);
            if ("div".equals(tag) && "page".equals(attrs.getValue("class"))) {
                if (pageNo > 0) flush();   // close the previous page
                pageNo++;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            buf.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (BLOCK.contains(local(localName, qName))) buf.append('\n');
        }

        @Override
        public void endDocument() {
            flush();
        }

        private void flush() {
            String text = buf.toString().strip();
            buf.setLength(0);
            if (!text.isEmpty()) pages.add(new Page(Math.max(1, pageNo), text));
        }

        List<Page> pages() {
            return pages;
        }

        private static String local(String localName, String qName) {
            String name = (localName == null || localName.isEmpty()) ? qName : localName;
            return name == null ? "" : name.toLowerCase(Locale.ROOT);
        }
    }
}

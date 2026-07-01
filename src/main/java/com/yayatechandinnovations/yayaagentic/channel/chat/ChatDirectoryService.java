package com.yayatechandinnovations.yayaagentic.channel.chat;

import com.yayatechandinnovations.yayaagentic.api.ChatApiException;
import com.yayatechandinnovations.yayaagentic.api.dto.DirectoryDtos.DirectoryCard;
import com.yayatechandinnovations.yayaagentic.api.dto.DirectoryDtos.DirectoryPage;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository.DirectoryCardView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Read side of the public chat directory ({@code chat-public-directory-design.md} §4):
 * sanitizes the query, decodes/encodes the opaque keyset cursor, and derives the
 * {@code nextCursor} from a {@code limit+1} look-ahead. The repository holds the
 * (index-backed, hot-set-scoped) SQL; this service holds the paging + input-safety
 * contract so the controller stays a thin HTTP shell.
 */
@Service
public class ChatDirectoryService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    static final int MAX_QUERY_LEN = 64;
    // NUL can never appear in a Postgres text value, so it is a collision-free
    // delimiter between the two keyset components (display_name, id).
    private static final char CURSOR_DELIM = (char) 0;

    private final ChatEntryPointRepository repo;

    public ChatDirectoryService(ChatEntryPointRepository repo) {
        this.repo = repo;
    }

    public DirectoryPage search(String q, String cursor, Integer limit) {
        int lim = clampLimit(limit);
        String like = toLikePattern(q);
        Cursor c = decodeCursor(cursor);

        // Fetch one extra row to learn whether a further page exists without a COUNT.
        List<DirectoryCardView> rows =
                repo.searchDirectory(like, c == null ? null : c.name(), c == null ? null : c.id(), lim + 1);

        boolean hasMore = rows.size() > lim;
        List<DirectoryCardView> page = hasMore ? rows.subList(0, lim) : rows;

        List<DirectoryCard> items = page.stream().map(DirectoryCard::of).toList();
        String nextCursor = hasMore
                ? encodeCursor(page.get(page.size() - 1).getDisplayName(), page.get(page.size() - 1).getId())
                : null;
        return new DirectoryPage(items, nextCursor);
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * A {@code %term%} substring pattern, or null for a pure browse. The query is
     * length-capped and every LIKE metacharacter ({@code \ % _}) is escaped so user
     * input is matched literally — there is no query-language injection surface, and
     * it is passed only as a bound parameter. {@code search_text} is already
     * lower-cased in the DB, so we lower-case the term to match.
     */
    private static String toLikePattern(String q) {
        if (q == null) return null;
        String trimmed = q.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > MAX_QUERY_LEN) trimmed = trimmed.substring(0, MAX_QUERY_LEN);
        String escaped = trimmed.toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private String encodeCursor(String name, String id) {
        String raw = (name == null ? "" : name) + CURSOR_DELIM + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.indexOf(CURSOR_DELIM);
            if (sep < 0) throw badCursor();
            return new Cursor(raw.substring(0, sep), raw.substring(sep + 1));
        } catch (IllegalArgumentException e) {
            throw badCursor();
        }
    }

    private static ChatApiException badCursor() {
        return new ChatApiException(HttpStatus.BAD_REQUEST, "bad_cursor", "invalid directory cursor", 0);
    }

    private record Cursor(String name, String id) {}
}

package com.yayatechandinnovations.yayaagentic.channel.chat;

import com.yayatechandinnovations.yayaagentic.api.ChatApiException;
import com.yayatechandinnovations.yayaagentic.api.dto.DirectoryDtos.DirectoryPage;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository.DirectoryCardView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Paging + input-safety contract of the directory read side
 * (chat-public-directory-design.md §4). The SQL/visibility gate is exercised against a
 * real DB elsewhere; here we pin the cursor codec, the {@code limit+1} look-ahead, and
 * query sanitization with a mocked repository.
 */
class ChatDirectoryServiceTest {

    private final ChatEntryPointRepository repo = mock(ChatEntryPointRepository.class);
    private final ChatDirectoryService service = new ChatDirectoryService(repo);

    @Test
    void lastPageHasNoCursor_andEmitsAllRows() {
        List<DirectoryCardView> found = rows(2);                 // fewer than limit → last page
        when(repo.searchDirectory(any(), any(), any(), eq(4)))   // limit 3 → fetch 4
                .thenReturn(found);

        DirectoryPage page = service.search(null, null, 3);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void fullPageEmitsCursorFromTheLastKeptRow_andDropsTheLookAheadRow() {
        List<DirectoryCardView> found = rows(4);                 // one more than limit → has next page
        when(repo.searchDirectory(any(), any(), any(), eq(4)))
                .thenReturn(found);

        DirectoryPage page = service.search(null, null, 3);

        assertThat(page.items()).hasSize(3);                     // look-ahead row dropped
        assertThat(page.nextCursor()).isNotNull();
        // Feeding the cursor back resolves to the 3rd row's (display_name, id).
        service.search(null, page.nextCursor(), 3);
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(2))
                .searchDirectory(any(), name.capture(), id.capture(), eq(4));
        assertThat(name.getAllValues().get(1)).isEqualTo("name2"); // rows are name0..name3; kept 0..2
        assertThat(id.getAllValues().get(1)).isEqualTo("id2");
    }

    @Test
    void blankQueryBrowses_nullLikePattern() {
        when(repo.searchDirectory(any(), any(), any(), any(Integer.class))).thenReturn(rows(0));
        service.search("   ", null, 20);
        ArgumentCaptor<String> like = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(repo).searchDirectory(like.capture(), any(), any(), any(Integer.class));
        assertThat(like.getValue()).isNull();
    }

    @Test
    void queryIsLowercasedWrappedAndLikeMetacharsEscaped() {
        when(repo.searchDirectory(any(), any(), any(), any(Integer.class))).thenReturn(rows(0));
        service.search("50%_Off\\Now", null, 20);
        ArgumentCaptor<String> like = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(repo).searchDirectory(like.capture(), any(), any(), any(Integer.class));
        // % _ \ are all escaped so they match literally; term lower-cased and %-wrapped.
        assertThat(like.getValue()).isEqualTo("%50\\%\\_off\\\\now%");
    }

    @Test
    void limitIsClampedToMax() {
        when(repo.searchDirectory(any(), any(), any(), eq(ChatDirectoryService.MAX_LIMIT + 1)))
                .thenReturn(rows(0));
        service.search(null, null, 9999);
        org.mockito.Mockito.verify(repo)
                .searchDirectory(any(), any(), any(), eq(ChatDirectoryService.MAX_LIMIT + 1));
    }

    @Test
    void malformedCursorIsRejectedWith400() {
        assertThatThrownBy(() -> service.search(null, "!!!not-base64!!!", 20))
                .isInstanceOf(ChatApiException.class)
                .satisfies(e -> assertThat(((ChatApiException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static List<DirectoryCardView> rows(int n) {
        List<DirectoryCardView> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(row("id" + i, "name" + i));
        return out;
    }

    private static DirectoryCardView row(String id, String name) {
        DirectoryCardView v = mock(DirectoryCardView.class);
        when(v.getId()).thenReturn(id);
        when(v.getHandle()).thenReturn("e_" + id);
        when(v.getDisplayName()).thenReturn(name);
        when(v.getLogoUrl()).thenReturn("/logo/" + id);
        when(v.getAccentColorHex()).thenReturn("#0A7");
        when(v.getTagline()).thenReturn(null);
        when(v.getAuthMode()).thenReturn("ANONYMOUS_ONLY");
        return v;
    }
}

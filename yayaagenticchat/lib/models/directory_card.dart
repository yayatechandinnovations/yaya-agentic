/// One row of the public directory — mirrors the backend
/// `DirectoryDtos.DirectoryCard` (chat-public-directory-design.md §4). A trimmed
/// projection: just enough to render a `LOGO | NAME` tile in the Discover list.
/// Tapping one resolves the full [EntryPointCard] before the confirm/add step.
class DirectoryCard {
  final String handle;
  final String displayName;
  final String logoUrl;
  final String? accentColorHex;
  final String? tagline;
  final String authMode;

  const DirectoryCard({
    required this.handle,
    required this.displayName,
    required this.logoUrl,
    required this.authMode,
    this.accentColorHex,
    this.tagline,
  });

  bool get requiresAuth => authMode == 'AUTH_REQUIRED';

  factory DirectoryCard.fromJson(Map<String, dynamic> j) => DirectoryCard(
        handle: j['handle'] as String,
        displayName: j['displayName'] as String,
        logoUrl: j['logoUrl'] as String,
        accentColorHex: j['accentColorHex'] as String?,
        tagline: j['tagline'] as String?,
        authMode: (j['authMode'] as String?) ?? 'ANONYMOUS_ONLY',
      );
}

/// A page of directory results. [nextCursor] is null on the last page.
class DirectoryPage {
  final List<DirectoryCard> items;
  final String? nextCursor;

  const DirectoryPage({required this.items, this.nextCursor});

  factory DirectoryPage.fromJson(Map<String, dynamic> j) => DirectoryPage(
        items: (j['items'] as List? ?? const [])
            .map((e) => DirectoryCard.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
        nextCursor: j['nextCursor'] as String?,
      );
}

/// Runtime configuration for the chat app.
///
/// The backend base URL can be overridden at build time:
///   flutter run --dart-define=YAYA_API_BASE=https://api.example.com
class AppConfig {
  static const String apiBase = String.fromEnvironment(
    'YAYA_API_BASE',
    defaultValue: 'http://localhost:8080',
  );

  /// The yaya-sales deployment behind the courier mode (yaya-sales-design.md
  /// §11.5). Couriers talk to yaya-sales DIRECTLY with the yaya-sales-issued
  /// agent token — never through yaya-agentic (§10.4/§15).
  ///   flutter run --dart-define=SALES_API_BASE_URL=https://sales.example.com
  static const String salesApiBase = String.fromEnvironment(
    'SALES_API_BASE_URL',
    defaultValue: 'http://localhost:8092',
  );

  /// Resolve a (possibly root-relative) URL — e.g. a platform-hosted logo at
  /// `/v1/chat/logos/{id}` — against the API base.
  static String resolveUrl(String url) {
    if (url.isEmpty || url.startsWith('http')) return url;
    return '${apiBase.replaceAll(RegExp(r'/+$'), '')}$url';
  }

  /// Deep links the QR encodes: https://chat.yaya.app/e/{handle} or
  /// yayachat://e/{handle}. We only need the trailing {handle} segment, which
  /// self-identifies by its `e_` prefix (chat-channel-design.md §6). We parse
  /// defensively so a bare handle, the canonical /e/ link, and a link whose base
  /// was misconfigured without the /e/ segment (…/e_ABC) all resolve alike.
  static String? handleFromDeepLink(String raw) {
    var text = raw.trim();
    if (text.isEmpty) return null;
    // Drop query/fragment, then any trailing slashes, before hunting a handle.
    final q = text.indexOf(RegExp(r'[?#]'));
    if (q >= 0) text = text.substring(0, q);
    text = text.replaceAll(RegExp(r'/+$'), '');

    // Bare handle pasted directly.
    if (!text.contains('/') && !text.contains(':')) {
      return _isHandle(text) ? text : null;
    }
    // Canonical marker …/e/{handle}.
    const marker = '/e/';
    final idx = text.indexOf(marker);
    if (idx >= 0) {
      final rest = _firstSegment(text.substring(idx + marker.length));
      if (_isHandle(rest)) return rest;
    }
    // Fallback: the last path segment (covers a base set without the /e/ path,
    // e.g. https://host/e_ABC — the handle still carries its own prefix).
    final tail = text.substring(text.lastIndexOf('/') + 1);
    return _isHandle(tail) ? tail : null;
  }

  static String _firstSegment(String s) {
    final cut = s.indexOf(RegExp(r'[/?#]'));
    return cut >= 0 ? s.substring(0, cut) : s;
  }

  /// Entry-point handles are `e_` + base64url (chat-channel-design.md §6).
  static bool _isHandle(String s) => s.length > 2 && s.startsWith('e_');
}

/// Runtime configuration for the chat app.
///
/// The backend base URL can be overridden at build time:
///   flutter run --dart-define=YAYA_API_BASE=https://api.example.com
class AppConfig {
  static const String apiBase = String.fromEnvironment(
    'YAYA_API_BASE',
    defaultValue: 'http://localhost:8080',
  );

  /// Resolve a (possibly root-relative) URL — e.g. a platform-hosted logo at
  /// `/v1/chat/logos/{id}` — against the API base.
  static String resolveUrl(String url) {
    if (url.isEmpty || url.startsWith('http')) return url;
    return '${apiBase.replaceAll(RegExp(r'/+$'), '')}$url';
  }

  /// Deep links the QR encodes: https://chat.yaya.app/e/{handle} or
  /// yayachat://e/{handle}. We only need the trailing handle segment.
  static String? handleFromDeepLink(String raw) {
    final text = raw.trim();
    if (text.isEmpty) return null;
    // Bare handle pasted directly.
    if (!text.contains('/') && !text.contains(':')) return text;
    final marker = '/e/';
    final idx = text.indexOf(marker);
    if (idx >= 0) {
      var rest = text.substring(idx + marker.length);
      final cut = rest.indexOf(RegExp(r'[/?#]'));
      if (cut >= 0) rest = rest.substring(0, cut);
      return rest.isEmpty ? null : rest;
    }
    return null;
  }
}

import 'entry_point_card.dart';

/// An entry point the user has added to their on-device directory — the
/// `LOGO | NAME` tile (chat-channel-design.md §3). Persisted locally; the
/// `sessionId` lets a reopened tile resume its conversation.
class Tile {
  final String handle;
  final String displayName;
  final String logoUrl;
  final String? accentColorHex;
  final String authMode;
  final String? sessionId;

  const Tile({
    required this.handle,
    required this.displayName,
    required this.logoUrl,
    required this.authMode,
    this.accentColorHex,
    this.sessionId,
  });

  Tile copyWith({String? sessionId, bool clearSession = false}) => Tile(
        handle: handle,
        displayName: displayName,
        logoUrl: logoUrl,
        accentColorHex: accentColorHex,
        authMode: authMode,
        sessionId: clearSession ? null : (sessionId ?? this.sessionId),
      );

  factory Tile.fromCard(EntryPointCard c) => Tile(
        handle: c.handle,
        displayName: c.displayName,
        logoUrl: c.logoUrl,
        accentColorHex: c.accentColorHex,
        authMode: c.authMode,
      );

  Map<String, dynamic> toJson() => {
        'handle': handle,
        'displayName': displayName,
        'logoUrl': logoUrl,
        'accentColorHex': accentColorHex,
        'authMode': authMode,
        'sessionId': sessionId,
      };

  factory Tile.fromJson(Map<String, dynamic> j) => Tile(
        handle: j['handle'] as String,
        displayName: j['displayName'] as String,
        logoUrl: j['logoUrl'] as String,
        accentColorHex: j['accentColorHex'] as String?,
        authMode: (j['authMode'] as String?) ?? 'ANONYMOUS_ONLY',
        sessionId: j['sessionId'] as String?,
      );
}

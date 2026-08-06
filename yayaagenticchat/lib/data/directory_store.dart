import 'dart:convert';
import 'dart:math';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/tile.dart';

/// On-device persistence for the directory of added tiles and the stable
/// device id (chat-channel-design.md §4.2 — directory is local in v1, no
/// server-side per-user store).
class DirectoryStore {
  static const _tilesKey = 'yaya.chat.tiles';
  static const _deviceKey = 'yaya.chat.deviceId';
  static const _phoneKey = 'yaya.chat.phone';
  static const _nameKey = 'yaya.chat.name';
  static const _pinRequiredKey = 'yaya.chat.pinRequired';

  final SharedPreferences _prefs;
  DirectoryStore(this._prefs);

  List<Tile> loadTiles() {
    final raw = _prefs.getString(_tilesKey);
    if (raw == null || raw.isEmpty) return [];
    final list = jsonDecode(raw) as List;
    return list.map((e) => Tile.fromJson(Map<String, dynamic>.from(e as Map))).toList();
  }

  Future<void> saveTiles(List<Tile> tiles) async {
    await _prefs.setString(_tilesKey, jsonEncode(tiles.map((t) => t.toJson()).toList()));
  }

  /// Stable per-install id; used for session continuity + rate limiting. Not
  /// trusted for authorization (§7.1).
  String deviceId() {
    var id = _prefs.getString(_deviceKey);
    if (id == null || id.isEmpty) {
      id = _randomHex(16);
      _prefs.setString(_deviceKey, id);
    }
    return id;
  }

  /// Self-asserted identity attributes shown in the UI. The device pass itself
  /// moved to secure storage (CredentialStore) — only non-secrets live here.
  String? phone() => _prefs.getString(_phoneKey);
  String? name() => _prefs.getString(_nameKey);

  /// Whether THIS device asks for the PIN on unlock (chat-experience-design.md
  /// §5.4). Mirrors the server-side flag so startup can auto-unlock silently.
  bool pinRequired() => _prefs.getBool(_pinRequiredKey) ?? true;
  Future<void> savePinRequired(bool required) async {
    await _prefs.setBool(_pinRequiredKey, required);
  }

  Future<void> saveIdentity(String phone, String? name) async {
    await _prefs.setString(_phoneKey, phone);
    if (name != null && name.isNotEmpty) await _prefs.setString(_nameKey, name);
  }

  /// Persist just the account name (rename keeps the same phone; the pass is
  /// rotated separately via [saveVerification]).
  Future<void> saveName(String name) async {
    if (name.isEmpty) return;
    await _prefs.setString(_nameKey, name);
  }

  Future<void> clearVerification() async {
    await _prefs.remove(_phoneKey);
    await _prefs.remove(_nameKey);
    await _prefs.remove(_pinRequiredKey);
  }

  /// Wipe everything the app knows about this identity — tiles + verification.
  /// Used after delete or sign-out-everywhere, returning the app to onboarding.
  Future<void> clearAll() async {
    await _prefs.remove(_tilesKey);
    await clearVerification();
  }

  static String _randomHex(int bytes) {
    final r = Random.secure();
    final sb = StringBuffer();
    for (var i = 0; i < bytes; i++) {
      sb.write(r.nextInt(256).toRadixString(16).padLeft(2, '0'));
    }
    return sb.toString();
  }
}

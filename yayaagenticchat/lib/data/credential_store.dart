import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// The device's secret credentials (chat-experience-design.md §5.5): the
/// short-lived session pass and the long-lived per-device secret that unlocks
/// it. Kept in platform secure storage (Keychain / Keystore; localStorage on
/// web — acceptable because the pass TTL is short and the PIN is verified
/// server-side), never in plain SharedPreferences.
class Credentials {
  final String? devicePass;
  final String? deviceSecret;
  const Credentials({this.devicePass, this.deviceSecret});

  static const empty = Credentials();
}

class CredentialStore {
  static const _passKey = 'yaya.chat.devicePass';
  static const _secretKey = 'yaya.chat.deviceSecret';

  final FlutterSecureStorage _storage;
  CredentialStore([FlutterSecureStorage? storage])
      : _storage = storage ?? const FlutterSecureStorage();

  /// Load both credentials, migrating a pre-CX pass out of SharedPreferences
  /// on first run (the old plaintext copy is removed once secured).
  Future<Credentials> load(SharedPreferences prefs) async {
    var pass = await _storage.read(key: _passKey);
    final legacy = prefs.getString(_passKey);
    if ((pass == null || pass.isEmpty) && legacy != null && legacy.isNotEmpty) {
      await _storage.write(key: _passKey, value: legacy);
      pass = legacy;
    }
    if (legacy != null) await prefs.remove(_passKey);
    final secret = await _storage.read(key: _secretKey);
    return Credentials(devicePass: pass, deviceSecret: secret);
  }

  Future<void> savePass(String pass) => _storage.write(key: _passKey, value: pass);

  Future<void> saveSecret(String secret) => _storage.write(key: _secretKey, value: secret);

  Future<void> clear() async {
    await _storage.delete(key: _passKey);
    await _storage.delete(key: _secretKey);
  }

  Future<void> clearPassOnly() => _storage.delete(key: _passKey);
}

/// Is this JWT expired (or malformed)? Client-side convenience only — the
/// server re-verifies everything; this just decides whether to show the lock
/// screen instead of firing a request that will 401.
bool isJwtExpired(String? jwt, {Duration skew = const Duration(seconds: 30)}) {
  if (jwt == null || jwt.isEmpty) return true;
  try {
    final parts = jwt.split('.');
    if (parts.length != 3) return true;
    final payload = utf8.decode(base64Url.decode(base64Url.normalize(parts[1])));
    final exp = (jsonDecode(payload) as Map<String, dynamic>)['exp'];
    if (exp is! num) return true;
    final expiry = DateTime.fromMillisecondsSinceEpoch(exp.toInt() * 1000, isUtc: true);
    return expiry.isBefore(DateTime.now().toUtc().add(skew));
  } catch (_) {
    return true;
  }
}

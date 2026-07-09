import 'package:shared_preferences/shared_preferences.dart';

/// Persists the access token and the in-flight PKCE verifier. An interface so
/// the auth interceptor and repository depend on an abstraction, not storage.
abstract class TokenStore {
  Future<String?> readToken();
  Future<void> writeToken(String token);
  Future<void> clear();

  Future<String?> readPkceVerifier();
  Future<void> writePkceVerifier(String verifier);
}

class SharedPrefsTokenStore implements TokenStore {
  SharedPrefsTokenStore(this._prefs);

  final SharedPreferences _prefs;

  static const _tokenKey = 'yaya_sales_access_token';
  static const _verifierKey = 'yaya_sales_pkce_verifier';

  @override
  Future<String?> readToken() async => _prefs.getString(_tokenKey);

  @override
  Future<void> writeToken(String token) async => _prefs.setString(_tokenKey, token);

  @override
  Future<void> clear() async {
    await _prefs.remove(_tokenKey);
    await _prefs.remove(_verifierKey);
  }

  @override
  Future<String?> readPkceVerifier() async => _prefs.getString(_verifierKey);

  @override
  Future<void> writePkceVerifier(String verifier) async =>
      _prefs.setString(_verifierKey, verifier);
}

import '../entities/auth_session.dart';

/// AuthN boundary for the console. The implementation drives OAuth auth-code +
/// PKCE against yaya-identity; the domain/presentation layers stay transport-free.
abstract class AuthRepository {
  /// Redirect the browser to the authorization endpoint (starts login).
  Future<void> beginLogin();

  /// Exchange the returned authorization [code] for a session.
  Future<AuthSession> completeLogin(String code);

  /// The current session from stored state, or null if signed out.
  Future<AuthSession?> currentSession();

  Future<void> logout();
}

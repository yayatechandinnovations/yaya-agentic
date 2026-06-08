import 'operator.dart';

/// Console-operator auth state, as the router + screens see it.
///
/// - [AuthChecking] is the brief startup window where the app fires GET
///   /v1/auth/me to figure out whether an existing session cookie is still
///   valid. The router holds traffic on a spinner during this.
/// - [AuthAuthenticated] carries the verified operator.
/// - [AuthUnauthenticated] is the steady state for a logged-out user; the
///   optional [error] surfaces a failed login attempt without changing the
///   user-visible screen.
/// - [AuthSessionExpired] is what the Dio 401 interceptor flips to when an
///   admin call comes back unauthorised mid-session — the router redirects
///   to /login with a short banner ("Your session has expired").
sealed class AuthState {
  const AuthState();
}

final class AuthChecking extends AuthState {
  const AuthChecking();
}

final class AuthUnauthenticated extends AuthState {
  const AuthUnauthenticated({this.error});
  final String? error;
}

final class AuthAuthenticated extends AuthState {
  const AuthAuthenticated(this.operator);
  final Operator operator;
}

final class AuthSessionExpired extends AuthState {
  const AuthSessionExpired();
}

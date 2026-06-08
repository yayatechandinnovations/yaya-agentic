import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/auth_api.dart';
import '../models/auth_state.dart';

/// Owns the operator's auth state across the app lifetime.
///
/// On first build the controller fires GET /v1/auth/me to detect an
/// existing session (the browser may already hold a valid YAYA_SESSION
/// cookie from a prior tab); during that probe the state is
/// [AuthChecking], which the router holds on a spinner.
class AuthController extends Notifier<AuthState> {
  @override
  AuthState build() {
    _bootstrap();
    return const AuthChecking();
  }

  Future<void> _bootstrap() async {
    try {
      final api = await ref.read(authApiProvider.future);
      final op = await api.me();
      state = op == null ? const AuthUnauthenticated() : AuthAuthenticated(op);
    } catch (_) {
      // Network failure at boot → treat as unauthenticated. The login
      // screen will surface a more useful error when the operator tries
      // to sign in.
      state = const AuthUnauthenticated();
    }
  }

  Future<bool> login({required String username, required String password}) async {
    state = const AuthChecking();
    try {
      final api = await ref.read(authApiProvider.future);
      final op = await api.login(username: username, password: password);
      state = AuthAuthenticated(op);
      return true;
    } on AuthApiException catch (e) {
      state = AuthUnauthenticated(error: e.message);
      return false;
    } catch (e) {
      state = AuthUnauthenticated(error: 'Sign-in failed: $e');
      return false;
    }
  }

  Future<void> logout() async {
    try {
      final api = await ref.read(authApiProvider.future);
      await api.logout();
    } catch (_) {
      // Best-effort: cookie clears regardless, and the server-side session
      // either succeeded or was already gone.
    }
    state = const AuthUnauthenticated();
  }

  /// Called by the Dio 401 interceptor when an admin request comes back
  /// unauthorised mid-session. Only flips when the operator currently
  /// *thinks* they're authenticated — prevents loops with /me 401s during
  /// boot (the boot path handles that itself via [_bootstrap]).
  void markExpiredFromInterceptor() {
    if (state is AuthAuthenticated) state = const AuthSessionExpired();
  }
}

final authProvider = NotifierProvider<AuthController, AuthState>(AuthController.new);

import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/usecases/auth_usecases.dart';

part 'auth_event.dart';
part 'auth_state.dart';

/// Owns the operator's session lifecycle. Events in, states out — the pages
/// only read state and dispatch events.
class AuthBloc extends Bloc<AuthEvent, AuthState> {
  AuthBloc({
    required LoadSession loadSession,
    required BeginLogin beginLogin,
    required CompleteLogin completeLogin,
    required Logout logout,
  })  : _loadSession = loadSession,
        _beginLogin = beginLogin,
        _completeLogin = completeLogin,
        _logout = logout,
        super(const AuthState()) {
    on<AuthStarted>(_onStarted);
    on<AuthLoginRequested>(_onLoginRequested);
    on<AuthCodeReceived>(_onCodeReceived);
    on<AuthLogoutRequested>(_onLogoutRequested);
    on<AuthSessionExpired>(_onSessionExpired);
  }

  final LoadSession _loadSession;
  final BeginLogin _beginLogin;
  final CompleteLogin _completeLogin;
  final Logout _logout;

  Future<void> _onStarted(AuthStarted event, Emitter<AuthState> emit) async {
    final session = await _loadSession();
    emit(state.copyWith(
      status: session != null ? AuthStatus.authenticated : AuthStatus.unauthenticated,
    ));
  }

  Future<void> _onLoginRequested(AuthLoginRequested event, Emitter<AuthState> emit) async {
    emit(state.copyWith(status: AuthStatus.authenticating));
    await _beginLogin(); // full-page redirect; control leaves the app here
  }

  Future<void> _onCodeReceived(AuthCodeReceived event, Emitter<AuthState> emit) async {
    emit(state.copyWith(status: AuthStatus.authenticating));
    try {
      await _completeLogin(event.code);
      emit(state.copyWith(status: AuthStatus.authenticated));
    } on Failure catch (f) {
      emit(AuthState(status: AuthStatus.failure, error: f.message));
    }
  }

  Future<void> _onLogoutRequested(AuthLogoutRequested event, Emitter<AuthState> emit) async {
    await _logout();
    emit(const AuthState(status: AuthStatus.unauthenticated, signedOut: true));
  }

  /// Parallel requests can all 401 at once — only the first transition acts.
  Future<void> _onSessionExpired(AuthSessionExpired event, Emitter<AuthState> emit) async {
    if (state.status != AuthStatus.authenticated) return;
    await _logout();
    emit(const AuthState(status: AuthStatus.unauthenticated));
  }
}

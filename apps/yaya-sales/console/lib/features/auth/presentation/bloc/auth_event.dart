part of 'auth_bloc.dart';

sealed class AuthEvent extends Equatable {
  const AuthEvent();

  @override
  List<Object?> get props => [];
}

/// Resolve the session at startup (already signed in?).
class AuthStarted extends AuthEvent {
  const AuthStarted();
}

/// Operator tapped "Sign in" → redirect to the authorization endpoint.
class AuthLoginRequested extends AuthEvent {
  const AuthLoginRequested();
}

/// The redirect came back with a code → exchange it for a session.
class AuthCodeReceived extends AuthEvent {
  const AuthCodeReceived(this.code);

  final String code;

  @override
  List<Object?> get props => [code];
}

class AuthLogoutRequested extends AuthEvent {
  const AuthLogoutRequested();
}

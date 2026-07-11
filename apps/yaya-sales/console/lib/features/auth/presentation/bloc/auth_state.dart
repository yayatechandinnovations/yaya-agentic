part of 'auth_bloc.dart';

enum AuthStatus { unknown, authenticating, authenticated, unauthenticated, failure }

class AuthState extends Equatable {
  const AuthState({this.status = AuthStatus.unknown, this.error, this.signedOut = false});

  final AuthStatus status;
  final String? error;

  /// True only after an EXPLICIT logout. The sign-in page auto-starts OAuth on
  /// any other unauthenticated arrival (first visit, expired session) — but a
  /// deliberate logout must show the button, or the IdP's SSO cookie would
  /// sign the operator straight back in and logout would be impossible.
  final bool signedOut;

  bool get isAuthenticated => status == AuthStatus.authenticated;

  AuthState copyWith({AuthStatus? status, String? error, bool? signedOut}) =>
      AuthState(
        status: status ?? this.status,
        error: error,
        signedOut: signedOut ?? this.signedOut,
      );

  @override
  List<Object?> get props => [status, error, signedOut];
}

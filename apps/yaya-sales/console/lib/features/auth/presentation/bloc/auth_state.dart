part of 'auth_bloc.dart';

enum AuthStatus { unknown, authenticating, authenticated, unauthenticated, failure }

class AuthState extends Equatable {
  const AuthState({this.status = AuthStatus.unknown, this.error});

  final AuthStatus status;
  final String? error;

  bool get isAuthenticated => status == AuthStatus.authenticated;

  AuthState copyWith({AuthStatus? status, String? error}) =>
      AuthState(status: status ?? this.status, error: error);

  @override
  List<Object?> get props => [status, error];
}

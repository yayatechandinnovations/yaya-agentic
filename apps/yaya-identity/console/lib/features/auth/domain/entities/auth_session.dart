import 'package:equatable/equatable.dart';

/// An authenticated session — the operator's access token for the admin API.
class AuthSession extends Equatable {
  const AuthSession({required this.accessToken});

  final String accessToken;

  @override
  List<Object?> get props => [accessToken];
}

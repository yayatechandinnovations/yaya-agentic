part of 'accept_invite_bloc.dart';

sealed class AcceptInviteEvent extends Equatable {
  const AcceptInviteEvent();

  @override
  List<Object?> get props => [];
}

class AcceptInviteSubmitted extends AcceptInviteEvent {
  const AcceptInviteSubmitted({
    required this.token,
    required this.displayName,
    required this.password,
  });

  final String token;
  final String displayName;
  final String password;

  @override
  List<Object?> get props => [token, displayName, password];
}

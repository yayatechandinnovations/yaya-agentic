part of 'users_bloc.dart';

enum UsersStatus { initial, loading, loaded, failure }

class UsersState extends Equatable {
  const UsersState({
    this.status = UsersStatus.initial,
    this.members = const [],
    this.roles = const [],
    this.org = '',
    this.error,
    this.inviteToken,
  });

  final UsersStatus status;
  final List<Member> members;
  final List<Role> roles;
  final String org;
  final String? error;
  final String? inviteToken;

  UsersState copyWith({
    UsersStatus? status,
    List<Member>? members,
    List<Role>? roles,
    String? org,
    String? error,
    String? inviteToken,
  }) =>
      UsersState(
        status: status ?? this.status,
        members: members ?? this.members,
        roles: roles ?? this.roles,
        org: org ?? this.org,
        error: error,
        inviteToken: inviteToken,
      );

  @override
  List<Object?> get props => [status, members, roles, org, error, inviteToken];
}

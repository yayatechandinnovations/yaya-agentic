part of 'roles_bloc.dart';

enum RolesStatus { initial, loading, loaded, failure }

class RolesState extends Equatable {
  const RolesState({
    this.status = RolesStatus.initial,
    this.roles = const [],
    this.catalog = const [],
    this.members = const [],
    this.groups = const [],
    this.org = '',
    this.error,
    this.message,
  });

  final RolesStatus status;
  final List<Role> roles;
  final List<String> catalog;
  final List<Member> members;
  final List<Group> groups;
  final String org;
  final String? error;
  final String? message;

  RolesState copyWith({
    RolesStatus? status,
    List<Role>? roles,
    List<String>? catalog,
    List<Member>? members,
    List<Group>? groups,
    String? org,
    String? error,
    String? message,
  }) =>
      RolesState(
        status: status ?? this.status,
        roles: roles ?? this.roles,
        catalog: catalog ?? this.catalog,
        members: members ?? this.members,
        groups: groups ?? this.groups,
        org: org ?? this.org,
        error: error,
        message: message,
      );

  @override
  List<Object?> get props =>
      [status, roles, catalog, members, groups, org, error, message];
}

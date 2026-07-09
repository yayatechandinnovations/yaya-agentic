part of 'roles_bloc.dart';

enum RolesStatus { initial, loading, loaded, failure }

class RolesState extends Equatable {
  const RolesState({
    this.status = RolesStatus.initial,
    this.roles = const [],
    this.org = '',
    this.error,
    this.message,
  });

  final RolesStatus status;
  final List<Role> roles;
  final String org;
  final String? error;
  final String? message;

  RolesState copyWith({
    RolesStatus? status,
    List<Role>? roles,
    String? org,
    String? error,
    String? message,
  }) =>
      RolesState(
        status: status ?? this.status,
        roles: roles ?? this.roles,
        org: org ?? this.org,
        error: error,
        message: message,
      );

  @override
  List<Object?> get props => [status, roles, org, error, message];
}

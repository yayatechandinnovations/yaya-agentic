part of 'roles_bloc.dart';

sealed class RolesEvent extends Equatable {
  const RolesEvent();

  @override
  List<Object?> get props => [];
}

class RolesRequested extends RolesEvent {
  const RolesRequested(this.org);
  final String org;
  @override
  List<Object?> get props => [org];
}

class RoleCreateRequested extends RolesEvent {
  const RoleCreateRequested(this.org, this.name, this.permissions);
  final String org;
  final String name;
  final List<String> permissions;
  @override
  List<Object?> get props => [org, name, permissions];
}

class RoleAssignRequested extends RolesEvent {
  const RoleAssignRequested(this.request);
  final RoleAssignmentRequest request;
  @override
  List<Object?> get props => [request];
}

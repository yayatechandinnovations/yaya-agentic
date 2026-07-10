import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../../groups/domain/entities/group.dart';
import '../../../groups/domain/usecases/group_usecases.dart';
import '../../../users/domain/entities/member.dart';
import '../../../users/domain/usecases/user_usecases.dart';
import '../../domain/entities/role.dart';
import '../../domain/entities/role_assignment_request.dart';
import '../../domain/usecases/role_usecases.dart';

part 'roles_event.dart';
part 'roles_state.dart';

/// Roles + everything the RBAC screen picks from: the permission catalog (role
/// composition) and the org's members/groups (assignment subjects).
class RolesBloc extends Bloc<RolesEvent, RolesState> {
  RolesBloc({
    required ListRoles listRoles,
    required CreateRole createRole,
    required AssignRole assignRole,
    required GetPermissionCatalog getPermissionCatalog,
    required ListMembers listMembers,
    required ListGroups listGroups,
  })  : _listRoles = listRoles,
        _createRole = createRole,
        _assignRole = assignRole,
        _getPermissionCatalog = getPermissionCatalog,
        _listMembers = listMembers,
        _listGroups = listGroups,
        super(const RolesState()) {
    on<RolesRequested>(_onRequested);
    on<RoleCreateRequested>(_onCreate);
    on<RoleAssignRequested>(_onAssign);
  }

  final ListRoles _listRoles;
  final CreateRole _createRole;
  final AssignRole _assignRole;
  final GetPermissionCatalog _getPermissionCatalog;
  final ListMembers _listMembers;
  final ListGroups _listGroups;

  Future<void> _onRequested(RolesRequested event, Emitter<RolesState> emit) async {
    emit(state.copyWith(status: RolesStatus.loading, org: event.org));
    try {
      final results = await Future.wait<Object>([
        _listRoles(event.org),
        _getPermissionCatalog(),
        _listMembers(event.org),
        _listGroups(event.org),
      ]);
      emit(state.copyWith(
        status: RolesStatus.loaded,
        roles: results[0] as List<Role>,
        catalog: (results[1] as List).cast<String>(),
        members: results[2] as List<Member>,
        groups: results[3] as List<Group>,
      ));
    } on Failure catch (f) {
      emit(state.copyWith(status: RolesStatus.failure, error: f.message));
    }
  }

  Future<void> _onCreate(RoleCreateRequested event, Emitter<RolesState> emit) async {
    try {
      await _createRole(event.org, event.name, event.permissions);
      add(RolesRequested(event.org));
    } on Failure catch (f) {
      emit(state.copyWith(status: RolesStatus.failure, error: f.message));
    }
  }

  Future<void> _onAssign(RoleAssignRequested event, Emitter<RolesState> emit) async {
    try {
      await _assignRole(event.request);
      emit(state.copyWith(message: 'Role assigned.'));
    } on Failure catch (f) {
      emit(state.copyWith(status: RolesStatus.failure, error: f.message));
    }
  }
}

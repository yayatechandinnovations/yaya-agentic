import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/role.dart';
import '../../domain/entities/role_assignment_request.dart';
import '../../domain/usecases/role_usecases.dart';

part 'roles_event.dart';
part 'roles_state.dart';

class RolesBloc extends Bloc<RolesEvent, RolesState> {
  RolesBloc({
    required ListRoles listRoles,
    required CreateRole createRole,
    required AssignRole assignRole,
  })  : _listRoles = listRoles,
        _createRole = createRole,
        _assignRole = assignRole,
        super(const RolesState()) {
    on<RolesRequested>(_onRequested);
    on<RoleCreateRequested>(_onCreate);
    on<RoleAssignRequested>(_onAssign);
  }

  final ListRoles _listRoles;
  final CreateRole _createRole;
  final AssignRole _assignRole;

  Future<void> _onRequested(RolesRequested event, Emitter<RolesState> emit) async {
    emit(state.copyWith(status: RolesStatus.loading, org: event.org));
    try {
      final roles = await _listRoles(event.org);
      emit(state.copyWith(status: RolesStatus.loaded, roles: roles));
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

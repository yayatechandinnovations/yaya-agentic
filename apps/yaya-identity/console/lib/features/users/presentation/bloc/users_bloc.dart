import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../../roles/domain/entities/role.dart';
import '../../../roles/domain/usecases/role_usecases.dart';
import '../../domain/entities/member.dart';
import '../../domain/usecases/user_usecases.dart';

part 'users_event.dart';
part 'users_state.dart';

class UsersBloc extends Bloc<UsersEvent, UsersState> {
  UsersBloc({
    required ListMembers listMembers,
    required LockUser lockUser,
    required DisableUser disableUser,
    required DeleteUser deleteUser,
    required InviteUser inviteUser,
    required ListRoles listRoles,
  })  : _listMembers = listMembers,
        _lockUser = lockUser,
        _disableUser = disableUser,
        _deleteUser = deleteUser,
        _inviteUser = inviteUser,
        _listRoles = listRoles,
        super(const UsersState()) {
    on<MembersRequested>(_onRequested);
    on<UserLockRequested>((e, emit) => _act(e.org, () => _lockUser(e.userId), emit));
    on<UserDisableRequested>((e, emit) => _act(e.org, () => _disableUser(e.userId), emit));
    on<UserDeleteRequested>((e, emit) => _act(e.org, () => _deleteUser(e.userId), emit));
    on<UserInviteRequested>(_onInvite);
  }

  final ListMembers _listMembers;
  final LockUser _lockUser;
  final DisableUser _disableUser;
  final DeleteUser _deleteUser;
  final InviteUser _inviteUser;
  final ListRoles _listRoles;

  Future<void> _onRequested(MembersRequested event, Emitter<UsersState> emit) async {
    emit(state.copyWith(status: UsersStatus.loading, org: event.org));
    try {
      // Members and the assignable roles (for the invite dialog) in parallel.
      final results = await Future.wait([_listMembers(event.org), _listRoles(event.org)]);
      emit(state.copyWith(
        status: UsersStatus.loaded,
        members: results[0] as List<Member>,
        roles: results[1] as List<Role>,
      ));
    } on Failure catch (f) {
      emit(state.copyWith(status: UsersStatus.failure, error: f.message));
    }
  }

  Future<void> _act(String org, Future<void> Function() action, Emitter<UsersState> emit) async {
    try {
      await action();
      add(MembersRequested(org));
    } on Failure catch (f) {
      emit(state.copyWith(status: UsersStatus.failure, error: f.message));
    }
  }

  Future<void> _onInvite(UserInviteRequested event, Emitter<UsersState> emit) async {
    try {
      final token = await _inviteUser(event.org, event.email, role: event.role);
      emit(state.copyWith(inviteToken: token));
    } on Failure catch (f) {
      emit(state.copyWith(status: UsersStatus.failure, error: f.message));
    }
  }
}

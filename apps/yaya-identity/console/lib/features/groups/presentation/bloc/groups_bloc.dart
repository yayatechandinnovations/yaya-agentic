import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/group.dart';
import '../../domain/usecases/group_usecases.dart';

part 'groups_event.dart';
part 'groups_state.dart';

class GroupsBloc extends Bloc<GroupsEvent, GroupsState> {
  GroupsBloc({
    required ListGroups listGroups,
    required CreateGroup createGroup,
    required AddGroupMember addGroupMember,
  })  : _listGroups = listGroups,
        _createGroup = createGroup,
        _addGroupMember = addGroupMember,
        super(const GroupsState()) {
    on<GroupsRequested>(_onRequested);
    on<GroupCreateRequested>(_onCreate);
    on<GroupMemberAddRequested>(_onAddMember);
  }

  final ListGroups _listGroups;
  final CreateGroup _createGroup;
  final AddGroupMember _addGroupMember;

  Future<void> _onRequested(GroupsRequested event, Emitter<GroupsState> emit) async {
    emit(state.copyWith(status: GroupsStatus.loading, org: event.org));
    try {
      emit(state.copyWith(status: GroupsStatus.loaded, groups: await _listGroups(event.org)));
    } on Failure catch (f) {
      emit(state.copyWith(status: GroupsStatus.failure, error: f.message));
    }
  }

  Future<void> _onCreate(GroupCreateRequested event, Emitter<GroupsState> emit) async {
    try {
      await _createGroup(event.org, event.name);
      add(GroupsRequested(event.org));
    } on Failure catch (f) {
      emit(state.copyWith(status: GroupsStatus.failure, error: f.message));
    }
  }

  Future<void> _onAddMember(GroupMemberAddRequested event, Emitter<GroupsState> emit) async {
    try {
      await _addGroupMember(event.groupId, event.userId);
      emit(state.copyWith(message: 'Added ${event.userId} to the group.'));
    } on Failure catch (f) {
      emit(state.copyWith(status: GroupsStatus.failure, error: f.message));
    }
  }
}

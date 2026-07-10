import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../../users/domain/entities/member.dart';
import '../../../users/domain/usecases/user_usecases.dart';
import '../../domain/entities/group.dart';
import '../../domain/usecases/group_usecases.dart';

part 'groups_event.dart';
part 'groups_state.dart';

/// Groups + their membership. Org members are loaded alongside the groups so
/// the add-member dialog can offer a picker instead of a raw user-id field.
class GroupsBloc extends Bloc<GroupsEvent, GroupsState> {
  GroupsBloc({
    required ListGroups listGroups,
    required CreateGroup createGroup,
    required ListGroupMembers listGroupMembers,
    required AddGroupMember addGroupMember,
    required RemoveGroupMember removeGroupMember,
    required ListMembers listMembers,
  })  : _listGroups = listGroups,
        _createGroup = createGroup,
        _listGroupMembers = listGroupMembers,
        _addGroupMember = addGroupMember,
        _removeGroupMember = removeGroupMember,
        _listMembers = listMembers,
        super(const GroupsState()) {
    on<GroupsRequested>(_onRequested);
    on<GroupCreateRequested>(_onCreate);
    on<GroupMembersRequested>(_onMembersRequested);
    on<GroupMemberAddRequested>(_onAddMember);
    on<GroupMemberRemoveRequested>(_onRemoveMember);
  }

  final ListGroups _listGroups;
  final CreateGroup _createGroup;
  final ListGroupMembers _listGroupMembers;
  final AddGroupMember _addGroupMember;
  final RemoveGroupMember _removeGroupMember;
  final ListMembers _listMembers;

  Future<void> _onRequested(GroupsRequested event, Emitter<GroupsState> emit) async {
    emit(state.copyWith(status: GroupsStatus.loading, org: event.org, groupMembers: const {}));
    try {
      final results = await Future.wait<Object>([
        _listGroups(event.org),
        _listMembers(event.org),
      ]);
      emit(state.copyWith(
        status: GroupsStatus.loaded,
        groups: results[0] as List<Group>,
        orgMembers: results[1] as List<Member>,
      ));
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

  Future<void> _onMembersRequested(
      GroupMembersRequested event, Emitter<GroupsState> emit) async {
    try {
      final members = await _listGroupMembers(event.groupId);
      emit(state.copyWith(groupMembers: {...state.groupMembers, event.groupId: members}));
    } on Failure catch (f) {
      emit(state.copyWith(message: f.message));
    }
  }

  Future<void> _onAddMember(GroupMemberAddRequested event, Emitter<GroupsState> emit) async {
    try {
      await _addGroupMember(event.groupId, event.userId);
      emit(state.copyWith(message: 'Member added to the group.'));
      await _refreshGroup(event.org, event.groupId, emit);
    } on Failure catch (f) {
      emit(state.copyWith(status: GroupsStatus.failure, error: f.message));
    }
  }

  Future<void> _onRemoveMember(
      GroupMemberRemoveRequested event, Emitter<GroupsState> emit) async {
    try {
      await _removeGroupMember(event.groupId, event.userId);
      emit(state.copyWith(message: 'Member removed from the group.'));
      await _refreshGroup(event.org, event.groupId, emit);
    } on Failure catch (f) {
      emit(state.copyWith(status: GroupsStatus.failure, error: f.message));
    }
  }

  /// Refresh one group's member list and the group list (member counts) without
  /// collapsing the page into a loading state.
  Future<void> _refreshGroup(String org, String groupId, Emitter<GroupsState> emit) async {
    final results = await Future.wait<Object>([
      _listGroupMembers(groupId),
      _listGroups(org),
    ]);
    emit(state.copyWith(
      groups: results[1] as List<Group>,
      groupMembers: {...state.groupMembers, groupId: results[0] as List<Member>},
    ));
  }
}

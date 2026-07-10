part of 'groups_bloc.dart';

sealed class GroupsEvent extends Equatable {
  const GroupsEvent();

  @override
  List<Object?> get props => [];
}

class GroupsRequested extends GroupsEvent {
  const GroupsRequested(this.org);
  final String org;
  @override
  List<Object?> get props => [org];
}

class GroupCreateRequested extends GroupsEvent {
  const GroupCreateRequested(this.org, this.name);
  final String org;
  final String name;
  @override
  List<Object?> get props => [org, name];
}

class GroupMembersRequested extends GroupsEvent {
  const GroupMembersRequested(this.groupId);
  final String groupId;
  @override
  List<Object?> get props => [groupId];
}

class GroupMemberAddRequested extends GroupsEvent {
  const GroupMemberAddRequested(this.org, this.groupId, this.userId);
  final String org;
  final String groupId;
  final String userId;
  @override
  List<Object?> get props => [org, groupId, userId];
}

class GroupMemberRemoveRequested extends GroupsEvent {
  const GroupMemberRemoveRequested(this.org, this.groupId, this.userId);
  final String org;
  final String groupId;
  final String userId;
  @override
  List<Object?> get props => [org, groupId, userId];
}

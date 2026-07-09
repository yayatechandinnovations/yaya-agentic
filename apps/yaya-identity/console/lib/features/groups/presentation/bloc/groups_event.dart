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

class GroupMemberAddRequested extends GroupsEvent {
  const GroupMemberAddRequested(this.groupId, this.userId);
  final String groupId;
  final String userId;
  @override
  List<Object?> get props => [groupId, userId];
}

part of 'groups_bloc.dart';

enum GroupsStatus { initial, loading, loaded, failure }

class GroupsState extends Equatable {
  const GroupsState({
    this.status = GroupsStatus.initial,
    this.groups = const [],
    this.orgMembers = const [],
    this.groupMembers = const {},
    this.org = '',
    this.error,
    this.message,
  });

  final GroupsStatus status;
  final List<Group> groups;

  /// The org's members — subjects for the add-member picker.
  final List<Member> orgMembers;

  /// Loaded member lists, keyed by group id (filled lazily on expansion).
  final Map<String, List<Member>> groupMembers;

  final String org;
  final String? error;
  final String? message;

  GroupsState copyWith({
    GroupsStatus? status,
    List<Group>? groups,
    List<Member>? orgMembers,
    Map<String, List<Member>>? groupMembers,
    String? org,
    String? error,
    String? message,
  }) =>
      GroupsState(
        status: status ?? this.status,
        groups: groups ?? this.groups,
        orgMembers: orgMembers ?? this.orgMembers,
        groupMembers: groupMembers ?? this.groupMembers,
        org: org ?? this.org,
        error: error,
        message: message,
      );

  @override
  List<Object?> get props =>
      [status, groups, orgMembers, groupMembers, org, error, message];
}

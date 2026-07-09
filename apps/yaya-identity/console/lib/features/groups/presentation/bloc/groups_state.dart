part of 'groups_bloc.dart';

enum GroupsStatus { initial, loading, loaded, failure }

class GroupsState extends Equatable {
  const GroupsState({
    this.status = GroupsStatus.initial,
    this.groups = const [],
    this.org = '',
    this.error,
    this.message,
  });

  final GroupsStatus status;
  final List<Group> groups;
  final String org;
  final String? error;
  final String? message;

  GroupsState copyWith({
    GroupsStatus? status,
    List<Group>? groups,
    String? org,
    String? error,
    String? message,
  }) =>
      GroupsState(
        status: status ?? this.status,
        groups: groups ?? this.groups,
        org: org ?? this.org,
        error: error,
        message: message,
      );

  @override
  List<Object?> get props => [status, groups, org, error, message];
}

import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/features/groups/domain/entities/group.dart';
import 'package:yaya_identity_console/features/groups/domain/repositories/group_repository.dart';
import 'package:yaya_identity_console/features/groups/domain/usecases/group_usecases.dart';
import 'package:yaya_identity_console/features/groups/presentation/bloc/groups_bloc.dart';
import 'package:yaya_identity_console/features/users/domain/entities/member.dart';
import 'package:yaya_identity_console/features/users/domain/repositories/user_repository.dart';
import 'package:yaya_identity_console/features/users/domain/usecases/user_usecases.dart';

class MockGroupRepository extends Mock implements GroupRepository {}

class MockUserRepository extends Mock implements UserRepository {}

void main() {
  late MockGroupRepository repo;
  late MockUserRepository userRepo;

  GroupsBloc build() => GroupsBloc(
        listGroups: ListGroups(repo),
        createGroup: CreateGroup(repo),
        listGroupMembers: ListGroupMembers(repo),
        addGroupMember: AddGroupMember(repo),
        removeGroupMember: RemoveGroupMember(repo),
        listMembers: ListMembers(userRepo),
      );

  const orgMembers = [
    Member(userId: 'usr_1', status: 'ACTIVE', email: 'a@acme.test', displayName: 'Ana')
  ];
  const groups = [Group(id: 'grp_1', name: 'downtown-fleet', memberCount: 1)];

  setUp(() {
    repo = MockGroupRepository();
    userRepo = MockUserRepository();
    when(() => userRepo.members(any())).thenAnswer((_) async => orgMembers);
  });

  blocTest<GroupsBloc, GroupsState>(
    'GroupsRequested → loading, loaded with groups and the org member pool',
    setUp: () => when(() => repo.list(any())).thenAnswer((_) async => groups),
    build: build,
    act: (b) => b.add(const GroupsRequested('org_1')),
    expect: () => [
      const GroupsState(status: GroupsStatus.loading, org: 'org_1'),
      isA<GroupsState>()
          .having((s) => s.status, 'status', GroupsStatus.loaded)
          .having((s) => s.groups, 'groups', groups)
          .having((s) => s.orgMembers, 'orgMembers', orgMembers),
    ],
  );

  blocTest<GroupsBloc, GroupsState>(
    'GroupMembersRequested fills the per-group member map',
    setUp: () => when(() => repo.members('grp_1')).thenAnswer((_) async => orgMembers),
    build: build,
    act: (b) => b.add(const GroupMembersRequested('grp_1')),
    expect: () => [
      isA<GroupsState>()
          .having((s) => s.groupMembers['grp_1'], 'grp_1 members', orgMembers),
    ],
  );

  blocTest<GroupsBloc, GroupsState>(
    'GroupMemberAddRequested adds, reports, and refreshes the group in place',
    setUp: () {
      when(() => repo.addMember(any(), any())).thenAnswer((_) async {});
      when(() => repo.members('grp_1')).thenAnswer((_) async => orgMembers);
      when(() => repo.list(any())).thenAnswer((_) async => groups);
    },
    build: build,
    act: (b) => b.add(const GroupMemberAddRequested('org_1', 'grp_1', 'usr_1')),
    expect: () => [
      isA<GroupsState>()
          .having((s) => s.message, 'message', 'Member added to the group.'),
      isA<GroupsState>()
          .having((s) => s.groupMembers['grp_1'], 'grp_1 members', orgMembers)
          .having((s) => s.groups, 'groups', groups),
    ],
    verify: (_) => verify(() => repo.addMember('grp_1', 'usr_1')).called(1),
  );

  blocTest<GroupsBloc, GroupsState>(
    'GroupMemberRemoveRequested removes and refreshes',
    setUp: () {
      when(() => repo.removeMember(any(), any())).thenAnswer((_) async {});
      when(() => repo.members('grp_1')).thenAnswer((_) async => const <Member>[]);
      when(() => repo.list(any())).thenAnswer((_) async => groups);
    },
    build: build,
    act: (b) => b.add(const GroupMemberRemoveRequested('org_1', 'grp_1', 'usr_1')),
    expect: () => [
      isA<GroupsState>()
          .having((s) => s.message, 'message', 'Member removed from the group.'),
      isA<GroupsState>()
          .having((s) => s.groupMembers['grp_1'], 'grp_1 members', isEmpty),
    ],
    verify: (_) => verify(() => repo.removeMember('grp_1', 'usr_1')).called(1),
  );
}

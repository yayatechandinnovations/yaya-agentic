import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/features/groups/domain/entities/group.dart';
import 'package:yaya_identity_console/features/groups/domain/repositories/group_repository.dart';
import 'package:yaya_identity_console/features/groups/domain/usecases/group_usecases.dart';
import 'package:yaya_identity_console/features/groups/presentation/bloc/groups_bloc.dart';

class MockGroupRepository extends Mock implements GroupRepository {}

void main() {
  late MockGroupRepository repo;

  GroupsBloc build() => GroupsBloc(
        listGroups: ListGroups(repo),
        createGroup: CreateGroup(repo),
        addGroupMember: AddGroupMember(repo),
      );

  setUp(() => repo = MockGroupRepository());

  const groups = [Group(id: 'grp_1', name: 'downtown-fleet')];

  blocTest<GroupsBloc, GroupsState>(
    'GroupsRequested → loading, loaded',
    setUp: () => when(() => repo.list(any())).thenAnswer((_) async => groups),
    build: build,
    act: (b) => b.add(const GroupsRequested('org_1')),
    expect: () => [
      const GroupsState(status: GroupsStatus.loading, org: 'org_1'),
      const GroupsState(status: GroupsStatus.loaded, groups: groups, org: 'org_1'),
    ],
  );

  blocTest<GroupsBloc, GroupsState>(
    'GroupMemberAddRequested reports success',
    setUp: () => when(() => repo.addMember(any(), any())).thenAnswer((_) async {}),
    build: build,
    act: (b) => b.add(const GroupMemberAddRequested('grp_1', 'usr_1')),
    expect: () =>
        [isA<GroupsState>().having((s) => s.message, 'message', 'Added usr_1 to the group.')],
    verify: (_) => verify(() => repo.addMember('grp_1', 'usr_1')).called(1),
  );
}

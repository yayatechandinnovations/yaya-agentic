import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/core/error/failure.dart';
import 'package:yaya_identity_console/features/groups/domain/entities/group.dart';
import 'package:yaya_identity_console/features/groups/domain/repositories/group_repository.dart';
import 'package:yaya_identity_console/features/groups/domain/usecases/group_usecases.dart';
import 'package:yaya_identity_console/features/roles/domain/entities/role.dart';
import 'package:yaya_identity_console/features/roles/domain/entities/role_assignment_request.dart';
import 'package:yaya_identity_console/features/roles/domain/repositories/role_repository.dart';
import 'package:yaya_identity_console/features/roles/domain/usecases/role_usecases.dart';
import 'package:yaya_identity_console/features/roles/presentation/bloc/roles_bloc.dart';
import 'package:yaya_identity_console/features/users/domain/entities/member.dart';
import 'package:yaya_identity_console/features/users/domain/repositories/user_repository.dart';
import 'package:yaya_identity_console/features/users/domain/usecases/user_usecases.dart';

class MockRoleRepository extends Mock implements RoleRepository {}

class MockUserRepository extends Mock implements UserRepository {}

class MockGroupRepository extends Mock implements GroupRepository {}

void main() {
  late MockRoleRepository repo;
  late MockUserRepository userRepo;
  late MockGroupRepository groupRepo;

  RolesBloc build() => RolesBloc(
        listRoles: ListRoles(repo),
        createRole: CreateRole(repo),
        assignRole: AssignRole(repo),
        getPermissionCatalog: GetPermissionCatalog(repo),
        listMembers: ListMembers(userRepo),
        listGroups: ListGroups(groupRepo),
      );

  setUpAll(() => registerFallbackValue(
      const RoleAssignmentRequest(org: 'o', subjectKind: 'USER', subjectId: 's', roleId: 'r')));

  const roles = [
    Role(id: 'role_1', name: 'store-manager', permissions: ['sales.order:read'])
  ];
  const catalog = ['sales.order:read', 'sales.order:refund'];
  const members = [Member(userId: 'usr_1', status: 'ACTIVE', displayName: 'Ana')];
  const groups = [Group(id: 'grp_1', name: 'fleet')];

  setUp(() {
    repo = MockRoleRepository();
    userRepo = MockUserRepository();
    groupRepo = MockGroupRepository();
    when(() => repo.list(any())).thenAnswer((_) async => roles);
    when(repo.permissionCatalog).thenAnswer((_) async => catalog);
    when(() => userRepo.members(any())).thenAnswer((_) async => members);
    when(() => groupRepo.list(any())).thenAnswer((_) async => groups);
  });

  blocTest<RolesBloc, RolesState>(
    'RolesRequested loads roles + catalog + assignment subjects together',
    build: build,
    act: (b) => b.add(const RolesRequested('org_1')),
    expect: () => [
      const RolesState(status: RolesStatus.loading, org: 'org_1'),
      isA<RolesState>()
          .having((s) => s.status, 'status', RolesStatus.loaded)
          .having((s) => s.roles, 'roles', roles)
          .having((s) => s.catalog, 'catalog', catalog)
          .having((s) => s.members, 'members', members)
          .having((s) => s.groups, 'groups', groups),
    ],
  );

  blocTest<RolesBloc, RolesState>(
    'RoleAssignRequested assigns and reports success',
    setUp: () => when(() => repo.assign(any())).thenAnswer((_) async {}),
    build: build,
    act: (b) => b.add(const RoleAssignRequested(RoleAssignmentRequest(
      org: 'org_1',
      subjectKind: 'USER',
      subjectId: 'usr_1',
      roleId: 'role_1',
      scopeType: 'store',
      scopeId: 'st_A',
    ))),
    expect: () => [isA<RolesState>().having((s) => s.message, 'message', 'Role assigned.')],
    verify: (_) => verify(() => repo.assign(any())).called(1),
  );

  blocTest<RolesBloc, RolesState>(
    'RoleAssignRequested surfaces a failure',
    setUp: () => when(() => repo.assign(any())).thenThrow(const ServerFailure('nope')),
    build: build,
    act: (b) => b.add(const RoleAssignRequested(RoleAssignmentRequest(
      org: 'org_1', subjectKind: 'USER', subjectId: 'usr_1', roleId: 'role_1'))),
    expect: () => [isA<RolesState>().having((s) => s.error, 'error', 'nope')],
  );
}

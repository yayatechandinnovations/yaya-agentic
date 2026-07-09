import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/core/error/failure.dart';
import 'package:yaya_identity_console/features/roles/domain/entities/role.dart';
import 'package:yaya_identity_console/features/roles/domain/entities/role_assignment_request.dart';
import 'package:yaya_identity_console/features/roles/domain/repositories/role_repository.dart';
import 'package:yaya_identity_console/features/roles/domain/usecases/role_usecases.dart';
import 'package:yaya_identity_console/features/roles/presentation/bloc/roles_bloc.dart';

class MockRoleRepository extends Mock implements RoleRepository {}

void main() {
  late MockRoleRepository repo;

  RolesBloc build() => RolesBloc(
        listRoles: ListRoles(repo),
        createRole: CreateRole(repo),
        assignRole: AssignRole(repo),
      );

  setUpAll(() => registerFallbackValue(
      const RoleAssignmentRequest(org: 'o', subjectKind: 'USER', subjectId: 's', roleId: 'r')));
  setUp(() => repo = MockRoleRepository());

  const roles = [Role(id: 'role_1', name: 'store-manager')];

  blocTest<RolesBloc, RolesState>(
    'RolesRequested → loading, loaded',
    setUp: () => when(() => repo.list(any())).thenAnswer((_) async => roles),
    build: build,
    act: (b) => b.add(const RolesRequested('org_1')),
    expect: () => [
      const RolesState(status: RolesStatus.loading, org: 'org_1'),
      const RolesState(status: RolesStatus.loaded, roles: roles, org: 'org_1'),
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

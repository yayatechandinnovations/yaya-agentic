import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/features/roles/domain/entities/role.dart';
import 'package:yaya_identity_console/features/roles/domain/repositories/role_repository.dart';
import 'package:yaya_identity_console/features/roles/domain/usecases/role_usecases.dart';
import 'package:yaya_identity_console/features/users/domain/entities/member.dart';
import 'package:yaya_identity_console/features/users/domain/repositories/user_repository.dart';
import 'package:yaya_identity_console/features/users/domain/usecases/user_usecases.dart';
import 'package:yaya_identity_console/features/users/presentation/bloc/users_bloc.dart';

class MockUserRepository extends Mock implements UserRepository {}

class MockRoleRepository extends Mock implements RoleRepository {}

void main() {
  late MockUserRepository repo;
  late MockRoleRepository roleRepo;

  UsersBloc build() => UsersBloc(
        listMembers: ListMembers(repo),
        lockUser: LockUser(repo),
        disableUser: DisableUser(repo),
        deleteUser: DeleteUser(repo),
        inviteUser: InviteUser(repo),
        listRoles: ListRoles(roleRepo),
      );

  setUp(() {
    repo = MockUserRepository();
    roleRepo = MockRoleRepository();
    when(() => roleRepo.list(any())).thenAnswer(
        (_) async => const [Role(id: 'role_sys_member', name: 'member', system: true)]);
  });

  const members = [
    Member(userId: 'usr_1', status: 'ACTIVE', email: 'a@acme.test', displayName: 'Ana')
  ];

  blocTest<UsersBloc, UsersState>(
    'MembersRequested → loading, loaded with members AND assignable roles',
    setUp: () => when(() => repo.members(any())).thenAnswer((_) async => members),
    build: build,
    act: (b) => b.add(const MembersRequested('org_1')),
    expect: () => [
      const UsersState(status: UsersStatus.loading, org: 'org_1'),
      isA<UsersState>()
          .having((s) => s.status, 'status', UsersStatus.loaded)
          .having((s) => s.members, 'members', members)
          .having((s) => s.roles.single.name, 'role', 'member'),
    ],
  );

  blocTest<UsersBloc, UsersState>(
    'UserDeleteRequested deletes then refreshes',
    setUp: () {
      when(() => repo.delete(any())).thenAnswer((_) async {});
      when(() => repo.members(any())).thenAnswer((_) async => const <Member>[]);
    },
    build: build,
    act: (b) => b.add(const UserDeleteRequested('org_1', 'usr_1')),
    expect: () => [
      const UsersState(status: UsersStatus.loading, org: 'org_1'),
      isA<UsersState>()
          .having((s) => s.status, 'status', UsersStatus.loaded)
          .having((s) => s.members, 'members', isEmpty),
    ],
    verify: (_) => verify(() => repo.delete('usr_1')).called(1),
  );

  blocTest<UsersBloc, UsersState>(
    'UserInviteRequested passes the chosen role and exposes the one-time token',
    setUp: () =>
        when(() => repo.invite(any(), any(), any())).thenAnswer((_) async => 'tok_123'),
    build: build,
    act: (b) => b.add(const UserInviteRequested('org_1', 'new@acme.test', role: 'admin')),
    expect: () => [isA<UsersState>().having((s) => s.inviteToken, 'inviteToken', 'tok_123')],
    verify: (_) => verify(() => repo.invite('org_1', 'new@acme.test', 'admin')).called(1),
  );
}

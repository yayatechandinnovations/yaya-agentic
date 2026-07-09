import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/features/users/domain/entities/member.dart';
import 'package:yaya_identity_console/features/users/domain/repositories/user_repository.dart';
import 'package:yaya_identity_console/features/users/domain/usecases/user_usecases.dart';
import 'package:yaya_identity_console/features/users/presentation/bloc/users_bloc.dart';

class MockUserRepository extends Mock implements UserRepository {}

void main() {
  late MockUserRepository repo;

  UsersBloc build() => UsersBloc(
        listMembers: ListMembers(repo),
        lockUser: LockUser(repo),
        disableUser: DisableUser(repo),
        deleteUser: DeleteUser(repo),
        inviteUser: InviteUser(repo),
      );

  setUp(() => repo = MockUserRepository());

  const members = [Member(userId: 'usr_1', status: 'ACTIVE')];

  blocTest<UsersBloc, UsersState>(
    'MembersRequested → loading, loaded',
    setUp: () => when(() => repo.members(any())).thenAnswer((_) async => members),
    build: build,
    act: (b) => b.add(const MembersRequested('org_1')),
    expect: () => [
      const UsersState(status: UsersStatus.loading, org: 'org_1'),
      const UsersState(status: UsersStatus.loaded, members: members, org: 'org_1'),
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
      const UsersState(status: UsersStatus.loaded, org: 'org_1'),
    ],
    verify: (_) => verify(() => repo.delete('usr_1')).called(1),
  );

  blocTest<UsersBloc, UsersState>(
    'UserInviteRequested exposes the one-time token',
    setUp: () => when(() => repo.invite(any(), any())).thenAnswer((_) async => 'tok_123'),
    build: build,
    act: (b) => b.add(const UserInviteRequested('org_1', 'new@acme.test')),
    expect: () => [isA<UsersState>().having((s) => s.inviteToken, 'inviteToken', 'tok_123')],
  );
}

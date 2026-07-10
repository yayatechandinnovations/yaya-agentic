import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/core/error/failure.dart';
import 'package:yaya_identity_console/features/signup/domain/entities/signup.dart';
import 'package:yaya_identity_console/features/signup/domain/repositories/signup_repository.dart';
import 'package:yaya_identity_console/features/signup/domain/usecases/signup_usecases.dart';
import 'package:yaya_identity_console/features/signup/presentation/bloc/accept_invite_bloc.dart';

class MockSignupRepository extends Mock implements SignupRepository {}

void main() {
  late MockSignupRepository repo;

  AcceptInviteBloc build() => AcceptInviteBloc(acceptInvitation: AcceptInvitation(repo));

  setUp(() => repo = MockSignupRepository());

  const event = AcceptInviteSubmitted(
    token: 'raw-invite-token',
    displayName: 'Joiner',
    password: 'correct-horse-battery',
  );

  blocTest<AcceptInviteBloc, AcceptInviteState>(
    'valid token activates the account',
    setUp: () => when(() => repo.acceptInvitation(
          token: any(named: 'token'),
          displayName: any(named: 'displayName'),
          password: any(named: 'password'),
        )).thenAnswer((_) async => const AcceptedInvitation(userId: 'usr_1', orgId: 'org_1')),
    build: build,
    act: (b) => b.add(event),
    expect: () => [
      const AcceptInviteState(status: AcceptInviteStatus.submitting),
      const AcceptInviteState(
        status: AcceptInviteStatus.completed,
        accepted: AcceptedInvitation(userId: 'usr_1', orgId: 'org_1'),
      ),
    ],
    verify: (_) => verify(() => repo.acceptInvitation(
          token: 'raw-invite-token',
          displayName: 'Joiner',
          password: 'correct-horse-battery',
        )).called(1),
  );

  blocTest<AcceptInviteBloc, AcceptInviteState>(
    'used or unknown token returns to the form with the message',
    setUp: () => when(() => repo.acceptInvitation(
          token: any(named: 'token'),
          displayName: any(named: 'displayName'),
          password: any(named: 'password'),
        )).thenThrow(const ServerFailure('unknown or already-used invitation')),
    build: build,
    act: (b) => b.add(event),
    expect: () => [
      const AcceptInviteState(status: AcceptInviteStatus.submitting),
      const AcceptInviteState(
        status: AcceptInviteStatus.form,
        error: 'unknown or already-used invitation',
      ),
    ],
  );
}

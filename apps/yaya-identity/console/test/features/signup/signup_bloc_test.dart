import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/core/error/failure.dart';
import 'package:yaya_identity_console/features/signup/domain/entities/signup.dart';
import 'package:yaya_identity_console/features/signup/domain/repositories/signup_repository.dart';
import 'package:yaya_identity_console/features/signup/domain/usecases/signup_usecases.dart';
import 'package:yaya_identity_console/features/signup/presentation/bloc/signup_bloc.dart';

class MockSignupRepository extends Mock implements SignupRepository {}

void main() {
  late MockSignupRepository repo;

  SignupBloc build() => SignupBloc(
        registerSignup: RegisterSignup(repo),
        verifySignup: VerifySignup(repo),
        resendSignupOtp: ResendSignupOtp(repo),
      );

  setUp(() => repo = MockSignupRepository());

  const submitted = SignupSubmitted(
    orgName: 'Colmado',
    displayName: 'Ana',
    email: 'ana@acme.test',
    phone: '+18095551234',
    password: 'correct-horse-battery',
  );

  void stubRegister({String? devOtp}) =>
      when(() => repo.register(
            orgName: any(named: 'orgName'),
            displayName: any(named: 'displayName'),
            email: any(named: 'email'),
            phone: any(named: 'phone'),
            password: any(named: 'password'),
          )).thenAnswer((_) async => RegisteredSignup(signupId: 'sgn_1', devOtp: devOtp));

  blocTest<SignupBloc, SignupState>(
    'submit → submitting, codeSent with signupId (and dev OTP when echoed)',
    setUp: () => stubRegister(devOtp: '123456'),
    build: build,
    act: (b) => b.add(submitted),
    expect: () => [
      const SignupState(status: SignupStatus.submitting),
      const SignupState(
        status: SignupStatus.codeSent,
        signupId: 'sgn_1',
        phone: '+18095551234',
        devOtp: '123456',
      ),
    ],
  );

  blocTest<SignupBloc, SignupState>(
    'server refusal (e.g. signup disabled, phone in use) returns to the form with the message',
    setUp: () => when(() => repo.register(
          orgName: any(named: 'orgName'),
          displayName: any(named: 'displayName'),
          email: any(named: 'email'),
          phone: any(named: 'phone'),
          password: any(named: 'password'),
        )).thenThrow(const ServerFailure('phone number already in use')),
    build: build,
    act: (b) => b.add(submitted),
    expect: () => [
      const SignupState(status: SignupStatus.submitting),
      const SignupState(status: SignupStatus.form, error: 'phone number already in use'),
    ],
  );

  blocTest<SignupBloc, SignupState>(
    'correct code completes the signup',
    setUp: () {
      stubRegister();
      when(() => repo.verify('sgn_1', '654321')).thenAnswer((_) async =>
          const VerifiedSignup(userId: 'usr_1', orgId: 'org_1', orgName: 'Colmado'));
    },
    build: build,
    act: (b) => b
      ..add(submitted)
      ..add(const SignupCodeSubmitted('654321')),
    skip: 2,
    expect: () => [
      const SignupState(
          status: SignupStatus.verifying, signupId: 'sgn_1', phone: '+18095551234'),
      const SignupState(
        status: SignupStatus.completed,
        signupId: 'sgn_1',
        phone: '+18095551234',
        verified: VerifiedSignup(userId: 'usr_1', orgId: 'org_1', orgName: 'Colmado'),
      ),
    ],
  );

  blocTest<SignupBloc, SignupState>(
    'wrong code stays on code entry with the error',
    setUp: () {
      stubRegister();
      when(() => repo.verify('sgn_1', '000000'))
          .thenThrow(const ServerFailure('invalid or expired verification code'));
    },
    build: build,
    act: (b) => b
      ..add(submitted)
      ..add(const SignupCodeSubmitted('000000')),
    skip: 2,
    expect: () => [
      const SignupState(
          status: SignupStatus.verifying, signupId: 'sgn_1', phone: '+18095551234'),
      const SignupState(
        status: SignupStatus.codeSent,
        signupId: 'sgn_1',
        phone: '+18095551234',
        error: 'invalid or expired verification code',
      ),
    ],
  );

  blocTest<SignupBloc, SignupState>(
    'resend reports success and surfaces cooldown refusals',
    setUp: () {
      stubRegister();
      when(() => repo.resend('sgn_1'))
          .thenAnswer((_) async => const RegisteredSignup(signupId: 'sgn_1'));
    },
    build: build,
    act: (b) => b
      ..add(submitted)
      ..add(const SignupResendRequested()),
    skip: 2,
    expect: () => [
      const SignupState(
        status: SignupStatus.codeSent,
        signupId: 'sgn_1',
        phone: '+18095551234',
        message: 'A new code was sent.',
      ),
    ],
  );
}

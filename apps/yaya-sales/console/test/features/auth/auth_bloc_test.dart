import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_sales_console/core/error/failure.dart';
import 'package:yaya_sales_console/features/auth/domain/entities/auth_session.dart';
import 'package:yaya_sales_console/features/auth/domain/repositories/auth_repository.dart';
import 'package:yaya_sales_console/features/auth/domain/usecases/auth_usecases.dart';
import 'package:yaya_sales_console/features/auth/presentation/bloc/auth_bloc.dart';

class MockAuthRepository extends Mock implements AuthRepository {}

void main() {
  late MockAuthRepository repo;

  AuthBloc build() => AuthBloc(
        loadSession: LoadSession(repo),
        beginLogin: BeginLogin(repo),
        completeLogin: CompleteLogin(repo),
        logout: Logout(repo),
      );

  setUp(() => repo = MockAuthRepository());

  group('AuthBloc', () {
    blocTest<AuthBloc, AuthState>(
      'AuthStarted with a session → authenticated',
      setUp: () => when(repo.currentSession)
          .thenAnswer((_) async => const AuthSession(accessToken: 't')),
      build: build,
      act: (b) => b.add(const AuthStarted()),
      expect: () => [const AuthState(status: AuthStatus.authenticated)],
    );

    blocTest<AuthBloc, AuthState>(
      'AuthStarted with no session → unauthenticated',
      setUp: () => when(repo.currentSession).thenAnswer((_) async => null),
      build: build,
      act: (b) => b.add(const AuthStarted()),
      expect: () => [const AuthState(status: AuthStatus.unauthenticated)],
    );

    blocTest<AuthBloc, AuthState>(
      'AuthCodeReceived exchanges the code → authenticating, authenticated',
      setUp: () => when(() => repo.completeLogin(any()))
          .thenAnswer((_) async => const AuthSession(accessToken: 't')),
      build: build,
      act: (b) => b.add(const AuthCodeReceived('abc')),
      expect: () => [
        const AuthState(status: AuthStatus.authenticating),
        const AuthState(status: AuthStatus.authenticated),
      ],
      verify: (_) => verify(() => repo.completeLogin('abc')).called(1),
    );

    blocTest<AuthBloc, AuthState>(
      'AuthCodeReceived surfaces a failure',
      setUp: () => when(() => repo.completeLogin(any()))
          .thenThrow(const AuthFailure('token exchange failed')),
      build: build,
      act: (b) => b.add(const AuthCodeReceived('bad')),
      expect: () => [
        const AuthState(status: AuthStatus.authenticating),
        const AuthState(status: AuthStatus.failure, error: 'token exchange failed'),
      ],
    );

    blocTest<AuthBloc, AuthState>(
      'AuthLoginRequested redirects to the authorization endpoint',
      setUp: () => when(repo.beginLogin).thenAnswer((_) async {}),
      build: build,
      act: (b) => b.add(const AuthLoginRequested()),
      expect: () => [const AuthState(status: AuthStatus.authenticating)],
      verify: (_) => verify(repo.beginLogin).called(1),
    );

    blocTest<AuthBloc, AuthState>(
      'AuthLogoutRequested clears the session',
      setUp: () => when(repo.logout).thenAnswer((_) async {}),
      build: build,
      act: (b) => b.add(const AuthLogoutRequested()),
      expect: () => [const AuthState(status: AuthStatus.unauthenticated)],
      verify: (_) => verify(repo.logout).called(1),
    );
  });
}

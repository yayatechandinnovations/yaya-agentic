import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'package:yayaagenticweb/features/auth/data/auth_api.dart';
import 'package:yayaagenticweb/features/auth/presentation/verify_email_screen.dart';

/// Drives the {@code VerifyEmailPendingScreen} resend interaction with a
/// hand-rolled {@code AuthApi} fake. Tests the three branches that matter
/// for closing the PRD FR-1.5 loop:
///   * happy path → success copy appears, button hides
///   * 429       → countdown copy appears, button disabled
///   * blank email → inline validation, no API call
void main() {
  testWidgets('resend success shows enumeration-safe confirmation copy',
      (tester) async {
    final api = _FakeAuthApi(onResend: (email) async {
      // happy path: 202 no-op
    });

    await tester.pumpWidget(_pumpPendingScreen(api: api, query: ''));
    await tester.pumpAndSettle();

    // Type the email and hit the CTA.
    await tester.enterText(find.byType(TextField), 'someone@example.com');
    await tester.tap(find.widgetWithText(FilledButton, 'Resend verification email'));
    await tester.pumpAndSettle();

    expect(find.textContaining('If an account is awaiting verification'),
        findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Resend verification email'),
        findsNothing);
    expect(api.calls, ['someone@example.com']);
  });

  testWidgets('resend 429 disables button and shows countdown', (tester) async {
    final api = _FakeAuthApi(onResend: (email) async {
      throw ResendThrottledException(
        retryAfterSeconds: 42,
        message: 'Too many requests. Try again later.',
      );
    });

    await tester.pumpWidget(
        _pumpPendingScreen(api: api, query: '?email=throttled%40example.com'));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(FilledButton, 'Resend verification email'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Too many requests'), findsOneWidget);
    expect(find.text('Resend in 42s'), findsOneWidget);

    // Button is disabled while cooldown is non-zero.
    final FilledButton btn = tester.widget(find.widgetWithText(FilledButton, 'Resend in 42s'));
    expect(btn.onPressed, isNull);
  });

  testWidgets('blank email surfaces inline validation, no API call',
      (tester) async {
    final api = _FakeAuthApi(onResend: (_) async => fail('should not call'));

    await tester.pumpWidget(_pumpPendingScreen(api: api, query: ''));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(FilledButton, 'Resend verification email'));
    await tester.pumpAndSettle();

    expect(find.text('Enter the email you signed up with.'), findsOneWidget);
    expect(api.calls, isEmpty);
  });

  testWidgets('?email= prefill hides the email field and pre-arms the CTA',
      (tester) async {
    final api = _FakeAuthApi(onResend: (_) async {});

    await tester.pumpWidget(
        _pumpPendingScreen(api: api, query: '?email=pending%40example.com'));
    await tester.pumpAndSettle();

    // No textfield — the email is locked to the URL value.
    expect(find.byType(TextField), findsNothing);
    expect(find.textContaining('pending@example.com'), findsOneWidget);

    await tester.tap(find.widgetWithText(FilledButton, 'Resend verification email'));
    await tester.pumpAndSettle();
    expect(api.calls, ['pending@example.com']);
  });
}

/// Pumps the pending screen behind a minimal GoRouter so
/// `GoRouterState.of(context)` resolves. `query` is the leading "?…" path
/// fragment used for the initial location.
Widget _pumpPendingScreen({required AuthApi api, required String query}) {
  final router = GoRouter(
    initialLocation: '/verify/pending$query',
    routes: [
      GoRoute(
        path: '/verify/pending',
        builder: (_, __) => const VerifyEmailPendingScreen(),
      ),
      GoRoute(
        path: '/login',
        builder: (_, __) => const Scaffold(body: Center(child: Text('login'))),
      ),
    ],
  );
  return ProviderScope(
    overrides: [
      authApiProvider.overrideWith((ref) async => api),
    ],
    child: MaterialApp.router(routerConfig: router),
  );
}

/// Minimal AuthApi fake. We don't need a real Dio — every call surface
/// the test exercises is overridden, so the inherited _dio is never used.
class _FakeAuthApi extends AuthApi {
  _FakeAuthApi({required this.onResend}) : super(Dio());

  final Future<void> Function(String email) onResend;
  final List<String> calls = [];

  @override
  Future<void> resendVerification(String email) async {
    calls.add(email);
    await onResend(email);
  }
}

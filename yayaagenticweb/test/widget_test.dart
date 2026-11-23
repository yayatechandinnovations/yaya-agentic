import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:yayaagenticweb/features/auth/application/auth_controller.dart';
import 'package:yayaagenticweb/features/auth/models/auth_state.dart';
import 'package:yayaagenticweb/main.dart';

class _SignedOut extends AuthController {
  @override
  AuthState build() => const AuthUnauthenticated();
}

void main() {
  // A visitor without a session lands on sign-in, not on a blank or
  // loading screen.
  testWidgets('app boots to the sign-in screen when there is no session', (WidgetTester tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [authProvider.overrideWith(_SignedOut.new)],
      child: const YayaAgenticWebApp(),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('Yaya Agentic'), findsOneWidget);
    expect(find.text('Sign in'), findsOneWidget);
  });
}

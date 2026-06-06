import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:yayaagenticweb/main.dart';

void main() {
  testWidgets('app boots and shows the playground entry point',
      (WidgetTester tester) async {
    await tester.pumpWidget(const ProviderScope(child: YayaAgenticWebApp()));
    await tester.pump();

    expect(find.text('Yaya Agentic — Admin'), findsOneWidget);
    // The playground now shows a profile picker that loads from the
    // admin API. The static title is always present even before the
    // network call completes.
    expect(find.text('Start a playground session'), findsOneWidget);
  });
}

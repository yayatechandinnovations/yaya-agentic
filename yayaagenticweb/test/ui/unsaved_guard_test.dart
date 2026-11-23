import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/ui/data/y_sheet.dart';
import 'package:yayaagenticweb/ui/form/unsaved_guard.dart';
import 'package:yayaagenticweb/ui/form/y_form_controller.dart';

void main() {
  testWidgets('closing a sheet asks only about the sheet’s own edits', (tester) async {
    var pageValue = 'a';
    var sheetValue = 'x';
    final page = YFormController(snapshot: () => {'v': pageValue}, validate: () => {});
    final sheet = YFormController(snapshot: () => {'v': sheetValue}, validate: () => {});
    late BuildContext pageCtx;

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: YUnsavedGuard(form: page, child: Builder(builder: (c) {
          pageCtx = c;
          return const SizedBox();
        })),
      ),
    ));
    pageValue = 'b'; // the page behind is dirty
    showYSheet<void>(pageCtx, builder: (_) => YUnsavedGuard(form: sheet, child: const Text('sheet body')));
    await tester.pumpAndSettle();

    // Sheet is clean: Esc closes it without a dialog, even though the page is dirty.
    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await tester.pumpAndSettle();
    expect(find.text('sheet body'), findsNothing);
    expect(find.textContaining('Discard'), findsNothing);

    // Sheet dirty: Esc asks, and Keep editing keeps it open.
    showYSheet<void>(pageCtx, builder: (_) => YUnsavedGuard(form: sheet, child: const Text('sheet body')));
    await tester.pumpAndSettle();
    sheetValue = 'y';
    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await tester.pumpAndSettle();
    expect(find.text('Discard 1 unsaved change?'), findsOneWidget);
    await tester.tap(find.text('Keep editing'));
    await tester.pumpAndSettle();
    expect(find.text('sheet body'), findsOneWidget);
  });
}

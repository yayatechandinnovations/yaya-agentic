import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/ui/feedback/global_shortcut.dart';

void main() {
  testWidgets('⌘K and Ctrl+K fire with nothing focused; plain K does not', (tester) async {
    var fired = 0;
    await tester.pumpWidget(MaterialApp(
      home: GlobalShortcut(keyK: LogicalKeyboardKey.keyK, onTrigger: () => fired++, child: const Scaffold()),
    ));
    expect(FocusManager.instance.primaryFocus?.context?.widget, isNot(isA<TextField>()));

    await tester.sendKeyDownEvent(LogicalKeyboardKey.metaLeft);
    await tester.sendKeyEvent(LogicalKeyboardKey.keyK);
    await tester.sendKeyUpEvent(LogicalKeyboardKey.metaLeft);
    expect(fired, 1);

    await tester.sendKeyDownEvent(LogicalKeyboardKey.controlLeft);
    await tester.sendKeyEvent(LogicalKeyboardKey.keyK);
    await tester.sendKeyUpEvent(LogicalKeyboardKey.controlLeft);
    expect(fired, 2);

    await tester.sendKeyEvent(LogicalKeyboardKey.keyK);
    expect(fired, 2);
  });
}

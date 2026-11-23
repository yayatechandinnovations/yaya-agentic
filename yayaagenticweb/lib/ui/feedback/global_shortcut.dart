import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

/// Fires [onTrigger] for ⌘[key] / Ctrl+[key] wherever focus is — even when
/// nothing has focus, which a Shortcuts widget can't see.
class GlobalShortcut extends StatefulWidget {
  const GlobalShortcut({super.key, required this.keyK, required this.onTrigger, required this.child});
  final LogicalKeyboardKey keyK;
  final VoidCallback onTrigger;
  final Widget child;

  @override
  State<GlobalShortcut> createState() => _GlobalShortcutState();
}

class _GlobalShortcutState extends State<GlobalShortcut> {
  @override
  void initState() {
    super.initState();
    HardwareKeyboard.instance.addHandler(_onKey);
  }

  @override
  void dispose() {
    HardwareKeyboard.instance.removeHandler(_onKey);
    super.dispose();
  }

  bool _onKey(KeyEvent e) {
    if (e is! KeyDownEvent || e.logicalKey != widget.keyK) return false;
    final kb = HardwareKeyboard.instance;
    if (!(kb.isMetaPressed || kb.isControlPressed)) return false;
    widget.onTrigger();
    return true;
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

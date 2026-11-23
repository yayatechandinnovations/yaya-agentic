import 'package:flutter/material.dart';

import '../feedback/confirm.dart';
import 'before_unload_stub.dart' if (dart.library.html) 'before_unload_web.dart' as unload;
import 'y_form_controller.dart';

/// Every editor that's open registers here; navigation (nav rail, ⌘K, back,
/// closing a sheet) asks before throwing away unsaved work.
class UnsavedChanges {
  UnsavedChanges._();
  static final instance = UnsavedChanges._();

  final Set<YFormController> _forms = {};
  final Map<YFormController, BuildContext> _contexts = {};

  void register(YFormController form, [BuildContext? context]) {
    _forms.add(form);
    if (context != null) _contexts[form] = context;
    _sync();
    form.addListener(_sync);
  }

  void unregister(YFormController form) {
    form.removeListener(_sync);
    _forms.remove(form);
    _contexts.remove(form);
    _sync();
  }

  int get dirtyCount => _dirtyIn(_forms);

  int _dirtyIn(Iterable<YFormController> forms) =>
      forms.fold(0, (n, f) => n + (f.isDirty && !f.saving ? f.dirtyCount : 0));

  /// Forms mounted under the same route as [context] — a sheet's own editor,
  /// not the page behind it.
  Iterable<YFormController> _onRouteOf(BuildContext context) {
    final route = ModalRoute.of(context);
    return _forms.where((f) {
      final c = _contexts[f];
      return c != null && c.mounted && ModalRoute.of(c) == route;
    });
  }

  bool get any => dirtyCount > 0;

  void _sync() => unload.setBeforeUnloadPrompt(any);

  /// True when it's fine to leave: nothing unsaved, or the operator chose to discard.
  /// [routeOnly]: only count editors on [context]'s route (closing a sheet
  /// shouldn't ask about the page underneath it).
  Future<bool> confirmLeave(BuildContext context, {bool routeOnly = false}) async {
    final n = routeOnly ? _dirtyIn(_onRouteOf(context)) : dirtyCount;
    if (n == 0) return true;
    final ok = await confirmAction(
      context,
      title: n == 1 ? 'Discard 1 unsaved change?' : 'Discard $n unsaved changes?',
      message: 'You’ll lose what you changed since the last save. A draft stays on this device until you discard it in the editor.',
      confirmLabel: 'Discard',
      cancelLabel: 'Keep editing',
      destructive: true,
    );
    return ok;
  }
}

/// Registers [form] with [UnsavedChanges] while mounted and intercepts the
/// system back gesture.
class YUnsavedGuard extends StatefulWidget {
  const YUnsavedGuard({super.key, required this.form, required this.child});
  final YFormController form;
  final Widget child;

  @override
  State<YUnsavedGuard> createState() => _YUnsavedGuardState();
}

class _YUnsavedGuardState extends State<YUnsavedGuard> {
  @override
  void initState() {
    super.initState();
    UnsavedChanges.instance.register(widget.form, context);
  }

  @override
  void didUpdateWidget(YUnsavedGuard old) {
    super.didUpdateWidget(old);
    if (old.form != widget.form) {
      UnsavedChanges.instance.unregister(old.form);
      UnsavedChanges.instance.register(widget.form, context);
    }
  }

  @override
  void dispose() {
    UnsavedChanges.instance.unregister(widget.form);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.form,
      builder: (context, child) => PopScope(
        canPop: !widget.form.isDirty,
        onPopInvokedWithResult: (didPop, _) async {
          if (didPop) return;
          final nav = Navigator.of(context);
          if (await UnsavedChanges.instance.confirmLeave(context, routeOnly: true)) {
            widget.form.markClean();
            nav.pop();
          }
        },
        child: child!,
      ),
      child: widget.child,
    );
  }
}

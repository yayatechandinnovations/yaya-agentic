import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../form/unsaved_guard.dart';

/// Opens [builder] as a right-hand side sheet over the current page. Esc and
/// the scrim close it — after asking, if an editor inside has unsaved work.
Future<R?> showYSheet<R>(
  BuildContext context, {
  required WidgetBuilder builder,
  double width = 640,
  String label = 'Editor',
}) {
  return showGeneralDialog<R>(
    context: context,
    barrierDismissible: false,
    barrierLabel: label,
    barrierColor: Colors.black.withValues(alpha: 0.32),
    transitionDuration: MediaQuery.of(context).disableAnimations ? Duration.zero : const Duration(milliseconds: 180),
    pageBuilder: (ctx, _, _) => _SheetFrame(width: width, label: label, child: builder(ctx)),
    transitionBuilder: (ctx, anim, _, child) => SlideTransition(
      position: Tween(begin: const Offset(0.08, 0), end: Offset.zero).animate(CurvedAnimation(parent: anim, curve: Curves.easeOutCubic)),
      child: FadeTransition(opacity: anim, child: child),
    ),
  );
}

/// Close the sheet that contains [context], asking first when there's unsaved work.
Future<void> closeYSheet(BuildContext context, [Object? result]) async {
  final nav = Navigator.of(context);
  if (await UnsavedChanges.instance.confirmLeave(context, routeOnly: true)) nav.pop(result);
}

class _SheetFrame extends StatelessWidget {
  const _SheetFrame({required this.width, required this.label, required this.child});
  final double width;
  final String label;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return CallbackShortcuts(
      bindings: {const SingleActivator(LogicalKeyboardKey.escape): () => closeYSheet(context)},
      child: Stack(children: [
        Positioned.fill(
          child: GestureDetector(behavior: HitTestBehavior.opaque, onTap: () => closeYSheet(context)),
        ),
        Align(
          alignment: Alignment.centerRight,
          child: Semantics(
            label: label,
            scopesRoute: true,
            namesRoute: true,
            explicitChildNodes: true,
            child: Material(
              color: scheme.surface,
              elevation: 12,
              child: SizedBox(
                width: width.clamp(0, MediaQuery.of(context).size.width).toDouble(),
                height: double.infinity,
                child: FocusScope(autofocus: true, child: child),
              ),
            ),
          ),
        ),
      ]),
    );
  }
}

/// Sheet chrome: title row with close, scrolling body, sticky footer.
class YSheetScaffold extends StatelessWidget {
  const YSheetScaffold({
    super.key,
    required this.title,
    required this.body,
    this.subtitle,
    this.footer,
    this.headerActions = const [],
  });

  final String title;
  final String? subtitle;
  final Widget body;
  final Widget? footer;
  final List<Widget> headerActions;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Container(
        padding: const EdgeInsets.fromLTRB(28, 20, 16, 16),
        decoration: BoxDecoration(border: Border(bottom: BorderSide(color: scheme.outlineVariant))),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Semantics(header: true, child: Text(title, style: Theme.of(context).textTheme.titleLarge)),
              if (subtitle != null)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(subtitle!, style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant)),
                ),
            ]),
          ),
          ...headerActions,
          IconButton(tooltip: 'Close (Esc)', icon: const Icon(Icons.close), onPressed: () => closeYSheet(context)),
        ]),
      ),
      Expanded(child: body),
      ?footer,
    ]);
  }
}

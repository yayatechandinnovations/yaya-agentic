import 'package:flutter/material.dart';

import '../tokens.dart';

/// A full-width admin page: title, one-line explanation, primary actions,
/// then the content. Replaces the old list/form split.
class YPage extends StatelessWidget {
  const YPage({
    super.key,
    required this.title,
    required this.child,
    this.subtitle,
    this.actions = const [],
    this.toolbar,
    this.maxWidth = 1280,
    this.scrollable = false,
  });

  final String title;
  final String? subtitle;
  final List<Widget> actions;
  final Widget? toolbar;
  final Widget child;
  final double maxWidth;

  /// Wrap everything in one scroll view (forms, settings). Lists manage their own scrolling.
  final bool scrollable;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final header = Wrap(
      spacing: 16,
      runSpacing: 12,
      crossAxisAlignment: WrapCrossAlignment.end,
      alignment: WrapAlignment.spaceBetween,
      children: [
        ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 760),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
            Semantics(header: true, child: Text(title, style: Theme.of(context).textTheme.headlineSmall)),
            if (subtitle != null)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text(subtitle!, style: TextStyle(fontSize: 14, height: 1.5, color: scheme.onSurfaceVariant)),
              ),
          ]),
        ),
        if (actions.isNotEmpty) Wrap(spacing: 8, runSpacing: 8, children: actions),
      ],
    );
    final content = Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      header,
      if (toolbar != null) ...[const SizedBox(height: 20), toolbar!],
      const SizedBox(height: 16),
      if (scrollable) child else Expanded(child: child),
    ]);
    return Align(
      alignment: Alignment.topCenter,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: maxWidth),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(Gap.pagePadding, Gap.xl, Gap.pagePadding, 0),
          child: scrollable ? SingleChildScrollView(padding: const EdgeInsets.only(bottom: 32), child: content) : content,
        ),
      ),
    );
  }
}

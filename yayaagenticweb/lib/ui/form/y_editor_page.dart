import 'package:flutter/material.dart';

import '../tokens.dart';
import 'unsaved_guard.dart';
import 'y_form_controller.dart';
import 'y_form_layout.dart';

class YSectionLink {
  const YSectionLink(this.label, this.key, {this.fields = const []});
  final String label;
  final GlobalKey key;

  /// Form fields in this section — the index shows "1 error" / "Changed" from them.
  final List<String> fields;
}

/// A full-page editor (deep-linkable route): header, banners, an optional
/// section index, the form, an optional side panel, and the sticky action bar.
class YEditorPage extends StatelessWidget {
  const YEditorPage({
    super.key,
    required this.form,
    required this.title,
    required this.children,
    required this.primaryLabel,
    required this.onPrimary,
    required this.onCancel,
    this.subtitle,
    this.badges = const [],
    this.headerActions = const [],
    this.banners = const [],
    this.sections = const [],
    this.aside,
    this.backLabel,
    this.onBack,
    this.actionVerb = 'saving',
  });

  final YFormController form;
  final String title;
  final String? subtitle;
  final List<Widget> badges;
  final List<Widget> headerActions;
  final List<Widget> banners;
  final List<YSectionLink> sections;
  final List<Widget> children;
  final Widget? aside;
  final String primaryLabel;
  final VoidCallback onPrimary;
  final VoidCallback onCancel;
  final String? backLabel;
  final VoidCallback? onBack;

  /// For the error summary: "… before {actionVerb}".
  final String actionVerb;

  static const _maxWidth = 1320.0;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return YUnsavedGuard(
      form: form,
      child: YSaveShortcut(
        onSave: onPrimary,
        child: ListenableBuilder(
          listenable: form,
          builder: (context, _) => Column(children: [
            Expanded(
              child: SingleChildScrollView(
                child: Center(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: _maxWidth),
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(Gap.pagePadding, Gap.lg, Gap.pagePadding, Gap.xxl),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                        if (onBack != null)
                          Align(
                            alignment: Alignment.centerLeft,
                            child: TextButton.icon(
                              onPressed: onBack,
                              icon: const Icon(Icons.arrow_back, size: 18),
                              label: Text(backLabel ?? 'Back'),
                            ),
                          ),
                        const SizedBox(height: 4),
                        Wrap(spacing: 16, runSpacing: 12, alignment: WrapAlignment.spaceBetween, crossAxisAlignment: WrapCrossAlignment.end, children: [
                          ConstrainedBox(
                            constraints: const BoxConstraints(maxWidth: 820),
                            child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
                              Wrap(spacing: 10, runSpacing: 6, crossAxisAlignment: WrapCrossAlignment.center, children: [
                                Semantics(header: true, child: Text(title, style: Theme.of(context).textTheme.headlineSmall)),
                                ...badges,
                              ]),
                              if (subtitle != null)
                                Padding(
                                  padding: const EdgeInsets.only(top: 6),
                                  child: Text(subtitle!, style: TextStyle(fontSize: 14, height: 1.5, color: scheme.onSurfaceVariant)),
                                ),
                            ]),
                          ),
                          if (headerActions.isNotEmpty) Wrap(spacing: 8, runSpacing: 8, children: headerActions),
                        ]),
                        for (final b in banners) ...[const SizedBox(height: 12), b],
                        if (form.summary.isNotEmpty || form.formError != null) ...[
                          const SizedBox(height: 12),
                          YErrorSummary(form: form, action: actionVerb),
                        ],
                        const SizedBox(height: Gap.xl),
                        LayoutBuilder(builder: (context, c) {
                          final showIndex = sections.isNotEmpty && c.maxWidth >= 1000;
                          final showAside = aside != null && c.maxWidth >= 860;
                          final main = Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                            for (var i = 0; i < children.length; i++) ...[if (i > 0) const SizedBox(height: 20), children[i]],
                            if (aside != null && !showAside) ...[const SizedBox(height: 20), aside!],
                          ]);
                          return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                            if (showIndex) ...[SizedBox(width: 200, child: _SectionIndex(form: form, sections: sections)), const SizedBox(width: 24)],
                            Expanded(child: main),
                            if (showAside) ...[const SizedBox(width: 24), SizedBox(width: 320, child: aside!)],
                          ]);
                        }),
                      ]),
                    ),
                  ),
                ),
              ),
            ),
            YActionBar(form: form, primaryLabel: primaryLabel, onPrimary: onPrimary, onCancel: onCancel, maxWidth: _maxWidth),
          ]),
        ),
      ),
    );
  }
}

class _SectionIndex extends StatelessWidget {
  const _SectionIndex({required this.form, required this.sections});
  final YFormController form;
  final List<YSectionLink> sections;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    final changed = form.changedFields;
    final errored = {for (final e in form.summary) e.field};
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
        child: Text('ON THIS PAGE', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, letterSpacing: 0.4, color: scheme.onSurfaceVariant)),
      ),
      for (final s in sections)
        InkWell(
          borderRadius: BorderRadius.circular(8),
          onTap: () {
            final ctx = s.key.currentContext;
            if (ctx != null) Scrollable.ensureVisible(ctx, duration: const Duration(milliseconds: 200), alignment: 0.05);
          },
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
            child: Row(children: [
              Expanded(child: Text(s.label, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: scheme.onSurface))),
              if (s.fields.any(errored.contains))
                _tag('${s.fields.where(errored.contains).length} error${s.fields.where(errored.contains).length == 1 ? '' : 's'}', scheme.errorContainer, scheme.onErrorContainer)
              else if (s.fields.any(changed.contains))
                _tag('Changed', status.warningContainer, status.onWarningContainer),
            ]),
          ),
        ),
    ]);
  }

  Widget _tag(String text, Color bg, Color fg) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(999)),
        child: Text(text, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: fg)),
      );
}

/// "Changed" chip + Revert next to a field label, for edits of existing records.
class YChangedTag extends StatelessWidget {
  const YChangedTag({super.key, required this.onRevert});
  final VoidCallback onRevert;

  @override
  Widget build(BuildContext context) {
    final status = StatusColors.of(context);
    return Row(mainAxisSize: MainAxisSize.min, children: [
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(color: status.warningContainer, borderRadius: BorderRadius.circular(999)),
        child: Text('Changed', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: status.onWarningContainer)),
      ),
      TextButton(
        style: TextButton.styleFrom(minimumSize: const Size(0, 28), padding: const EdgeInsets.symmetric(horizontal: 8)),
        onPressed: onRevert,
        child: const Text('Revert', style: TextStyle(fontSize: 13)),
      ),
    ]);
  }
}

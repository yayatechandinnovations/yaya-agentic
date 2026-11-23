import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../tokens.dart';
import 'y_form_controller.dart';

/// A titled block of related fields; [collapsible] sections start closed
/// unless [initiallyExpanded] (Advanced settings).
class YFormSection extends StatefulWidget {
  const YFormSection({
    super.key,
    required this.title,
    required this.children,
    this.description,
    this.collapsible = false,
    this.initiallyExpanded = true,
    this.card = true,
    this.trailing,
    this.sectionKey,
  });

  final String title;
  final String? description;
  final List<Widget> children;
  final bool collapsible;
  final bool initiallyExpanded;
  final bool card;
  final Widget? trailing;

  /// Lets a section index scroll here.
  final GlobalKey? sectionKey;

  @override
  State<YFormSection> createState() => _YFormSectionState();
}

class _YFormSectionState extends State<YFormSection> {
  late bool _open = !widget.collapsible || widget.initiallyExpanded;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final header = Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Expanded(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Semantics(
            header: true,
            child: Text(widget.title, style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: scheme.onSurface)),
          ),
          if (widget.description != null)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(widget.description!, style: TextStyle(fontSize: 14, height: 1.5, color: scheme.onSurfaceVariant)),
            ),
        ]),
      ),
      if (widget.trailing != null) widget.trailing!,
      if (widget.collapsible)
        Icon(_open ? Icons.expand_less : Icons.expand_more, color: scheme.onSurfaceVariant),
    ]);
    final body = Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      widget.collapsible
          ? InkWell(
              borderRadius: BorderRadius.circular(8),
              onTap: () => setState(() => _open = !_open),
              child: Semantics(button: true, expanded: _open, child: header),
            )
          : header,
      if (_open) ...[
        const SizedBox(height: Gap.fieldGap),
        for (var i = 0; i < widget.children.length; i++) ...[
          if (i > 0) const SizedBox(height: Gap.fieldGap),
          widget.children[i],
        ],
      ],
    ]);
    return KeyedSubtree(
      key: widget.sectionKey,
      child: widget.card
          ? Card(child: Padding(padding: const EdgeInsets.all(Gap.xl), child: body))
          : Padding(padding: const EdgeInsets.symmetric(vertical: Gap.lg), child: body),
    );
  }
}

/// "2 things need attention" with a link to each — shown after a submit attempt.
class YErrorSummary extends StatelessWidget {
  const YErrorSummary({super.key, required this.form, this.action = 'saving'});
  final YFormController form;
  final String action;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final items = form.summary;
    final formError = form.formError;
    if (items.isEmpty && formError == null) return const SizedBox.shrink();
    return Semantics(
      key: form.summaryKey,
      liveRegion: true,
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: scheme.errorContainer,
          border: Border.all(color: scheme.error.withValues(alpha: 0.35)),
          borderRadius: BorderRadius.circular(10),
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Icon(Icons.error_outline, color: scheme.onErrorContainer, size: 20),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                items.isEmpty
                    ? 'Couldn’t save'
                    : items.length == 1
                        ? '1 thing needs attention before $action'
                        : '${items.length} things need attention before $action',
                style: TextStyle(fontWeight: FontWeight.w600, fontSize: 14, color: scheme.onErrorContainer),
              ),
            ),
          ]),
          if (formError != null)
            Padding(
              padding: const EdgeInsets.only(top: 6, left: 28),
              child: Text(formError, style: TextStyle(fontSize: 14, color: scheme.onErrorContainer)),
            ),
          for (final e in items)
            Padding(
              padding: const EdgeInsets.only(top: 6, left: 28),
              child: InkWell(
                onTap: () => form.revealField(e.field),
                child: Text.rich(
                  TextSpan(children: [
                    TextSpan(
                      text: e.label,
                      style: const TextStyle(fontWeight: FontWeight.w600, decoration: TextDecoration.underline),
                    ),
                    TextSpan(text: ' — ${e.message}'),
                  ]),
                  style: TextStyle(fontSize: 14, color: scheme.onErrorContainer),
                ),
              ),
            ),
        ]),
      ),
    );
  }
}

enum YBannerTone { info, warning, error, success }

class YBanner extends StatelessWidget {
  const YBanner({super.key, required this.tone, required this.message, this.title, this.actions = const []});
  final YBannerTone tone;
  final String? title;
  final String message;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final s = StatusColors.of(context);
    final (bg, fg, icon) = switch (tone) {
      YBannerTone.info => (s.infoContainer, s.onInfoContainer, Icons.info_outline),
      YBannerTone.warning => (s.warningContainer, s.onWarningContainer, Icons.warning_amber_rounded),
      YBannerTone.error => (scheme.errorContainer, scheme.onErrorContainer, Icons.error_outline),
      YBannerTone.success => (s.successContainer, s.onSuccessContainer, Icons.check_circle_outline),
    };
    return Semantics(
      liveRegion: tone == YBannerTone.warning || tone == YBannerTone.error,
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(10), border: Border.all(color: fg.withValues(alpha: 0.25))),
        child: Wrap(spacing: 12, runSpacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: Row(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Icon(icon, color: fg, size: 20),
              const SizedBox(width: 10),
              Flexible(
                child: Text.rich(
                  TextSpan(children: [
                    if (title != null) TextSpan(text: '$title ', style: const TextStyle(fontWeight: FontWeight.w600)),
                    TextSpan(text: message),
                  ]),
                  style: TextStyle(fontSize: 14, height: 1.45, color: fg),
                ),
              ),
            ]),
          ),
          ...actions,
        ]),
      ),
    );
  }
}

/// Sticky footer: what's unsaved, Cancel, and a primary button that names
/// the outcome ("Create tool", "Publish v5"). Never disabled for invalid
/// input — pressing it is what reveals the error summary.
class YActionBar extends StatelessWidget {
  const YActionBar({
    super.key,
    required this.form,
    required this.primaryLabel,
    required this.onPrimary,
    this.onCancel,
    this.cancelLabel = 'Cancel',
    this.secondary = const [],
    this.maxWidth,
  });

  final YFormController form;
  final String primaryLabel;
  final VoidCallback onPrimary;
  final VoidCallback? onCancel;
  final String cancelLabel;
  final List<Widget> secondary;
  final double? maxWidth;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    return ListenableBuilder(
      listenable: form,
      builder: (context, _) {
        final dirty = form.isDirty;
        final saved = form.draftSavedAt;
        final row = Wrap(
          alignment: WrapAlignment.end,
          crossAxisAlignment: WrapCrossAlignment.center,
          spacing: 10,
          runSpacing: 8,
          children: [
            Row(mainAxisSize: MainAxisSize.min, children: [
              Container(
                width: 8,
                height: 8,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: dirty ? status.warning : scheme.outlineVariant,
                ),
              ),
              const SizedBox(width: 8),
              Text(
                dirty
                    ? '${form.dirtyLabel}${saved != null && form.draftKey != null ? ' · draft saved' : ''}'
                    : 'No unsaved changes',
                style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant),
              ),
              const SizedBox(width: 16),
            ]),
            ...secondary,
            if (onCancel != null)
              OutlinedButton(onPressed: form.saving ? null : onCancel, child: Text(cancelLabel)),
            Tooltip(
              message: '⌘S / Ctrl+S',
              child: FilledButton(
                onPressed: form.saving ? null : onPrimary,
                child: form.saving
                    ? Row(mainAxisSize: MainAxisSize.min, children: [
                        SizedBox.square(
                            dimension: 16,
                            child: CircularProgressIndicator(strokeWidth: 2, color: scheme.onPrimary)),
                        const SizedBox(width: 10),
                        const Text('Saving…'),
                      ])
                    : Text(primaryLabel),
              ),
            ),
          ],
        );
        return Material(
          color: scheme.surface,
          elevation: 0,
          child: Container(
            decoration: BoxDecoration(border: Border(top: BorderSide(color: scheme.outlineVariant))),
            padding: const EdgeInsets.symmetric(horizontal: Gap.pagePadding, vertical: 12),
            child: maxWidth == null
                ? SizedBox(width: double.infinity, child: row)
                : Center(
                    child: ConstrainedBox(
                      constraints: BoxConstraints(maxWidth: maxWidth!),
                      child: SizedBox(width: double.infinity, child: row),
                    ),
                  ),
          ),
        );
      },
    );
  }
}

/// ⌘S / Ctrl+S anywhere inside the editor.
class YSaveShortcut extends StatelessWidget {
  const YSaveShortcut({super.key, required this.onSave, required this.child});
  final VoidCallback onSave;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return CallbackShortcuts(
      bindings: {
        const SingleActivator(LogicalKeyboardKey.keyS, meta: true): onSave,
        const SingleActivator(LogicalKeyboardKey.keyS, control: true): onSave,
      },
      child: Focus(autofocus: false, child: child),
    );
  }
}

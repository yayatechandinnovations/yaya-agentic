import 'package:dio/dio.dart';
import 'package:flutter/material.dart';

/// Pulls the human-readable message out of a Dio error. The backend's
/// AdminExceptionHandler emits {error, message}; everything else falls
/// back to the exception's own message.
String formatError(Object e) {
  if (e is DioException) {
    final data = e.response?.data;
    if (data is Map && data['message'] is String) {
      return data['message'] as String;
    }
    return e.message ?? 'request failed';
  }
  return e.toString();
}

/// Standard "list on the left, create form on the right" layout used by
/// every admin screen.
class AdminTwoPane extends StatelessWidget {
  const AdminTwoPane({
    super.key,
    required this.title,
    required this.list,
    required this.form,
    this.subtitle,
  });

  final String title;
  final String? subtitle;
  final Widget list;
  final Widget form;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: Theme.of(context).textTheme.headlineSmall),
                    if (subtitle != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 4),
                        child: Text(subtitle!,
                            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                color: Theme.of(context).colorScheme.outline)),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Expanded(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Expanded(flex: 3, child: Padding(padding: const EdgeInsets.all(24), child: list)),
              const VerticalDivider(width: 1),
              Expanded(flex: 2, child: Padding(padding: const EdgeInsets.all(24), child: form)),
            ],
          ),
        ),
      ],
    );
  }
}

class FormCard extends StatelessWidget {
  const FormCard({super.key, required this.title, required this.child, this.actions = const []});
  final String title;
  final Widget child;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            child,
            if (actions.isNotEmpty) ...[
              const SizedBox(height: 16),
              Row(mainAxisAlignment: MainAxisAlignment.end, children: actions),
            ],
          ],
        ),
      ),
    );
  }
}

void showSnack(BuildContext context, String message, {bool error = false}) {
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(
    content: Text(message),
    backgroundColor: error ? Theme.of(context).colorScheme.errorContainer : null,
  ));
}

/// Expandable admin record card. Header row is always visible; tapping it
/// reveals the body (full record details) and an action row with a
/// "Clone & edit" button. The clone callback should populate the parent
/// screen's create-form fields and switch the form's save action to
/// "save as v(N+1)" mode.
///
/// The card is deliberately not coupled to any specific DTO — caller
/// supplies the header, the body, and the clone callback. Keeps the
/// pattern reusable across tools / capabilities / profiles / sources.
class EditableRecordCard extends StatefulWidget {
  const EditableRecordCard({
    super.key,
    required this.header,
    required this.body,
    required this.onCloneEdit,
    this.leading,
    this.trailing,
  });

  final Widget header;
  final Widget body;
  final VoidCallback onCloneEdit;
  final Widget? leading;
  final Widget? trailing;

  @override
  State<EditableRecordCard> createState() => _EditableRecordCardState();
}

class _EditableRecordCardState extends State<EditableRecordCard> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    return Card(
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          InkWell(
            onTap: () => setState(() => _expanded = !_expanded),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
              child: Row(
                children: [
                  if (widget.leading != null) ...[
                    widget.leading!,
                    const SizedBox(width: 12),
                  ],
                  Expanded(child: widget.header),
                  if (widget.trailing != null) ...[
                    const SizedBox(width: 8),
                    widget.trailing!,
                  ],
                  const SizedBox(width: 4),
                  Icon(_expanded ? Icons.expand_less : Icons.expand_more),
                ],
              ),
            ),
          ),
          if (_expanded) ...[
            const Divider(height: 1),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
              child: widget.body,
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 0, 8, 8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  FilledButton.tonalIcon(
                    onPressed: widget.onCloneEdit,
                    icon: const Icon(Icons.edit_outlined, size: 18),
                    label: const Text('Clone & edit'),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// Labeled key/value row used inside an [EditableRecordCard] body. Multi-
/// line strings get a monospace block; everything else is rendered inline.
class DetailRow extends StatelessWidget {
  const DetailRow({super.key, required this.label, required this.value, this.mono = false});

  final String label;
  final String value;
  final bool mono;

  @override
  Widget build(BuildContext context) {
    final isBlock = mono || value.contains('\n') || value.length > 80;
    final mutedStyle = Theme.of(context).textTheme.labelSmall?.copyWith(
        color: Theme.of(context).colorScheme.outline);
    if (isBlock) {
      return Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: mutedStyle),
            const SizedBox(height: 2),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(4),
              ),
              child: SelectableText(
                value,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
              ),
            ),
          ],
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(width: 120, child: Text(label, style: mutedStyle)),
          Expanded(
            child: SelectableText(value,
                style: const TextStyle(fontSize: 13)),
          ),
        ],
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../models/admin/api_key.dart';

/// Show-once secret panel (design §3.1). The secret is rendered exactly once,
/// here, right after create. The Close button stays disabled until the
/// operator ticks "I've stored this key" — a small forcing function so nobody
/// dismisses it by accident and then asks us to recover a key we never stored.
class ShowSecretDialog extends StatefulWidget {
  const ShowSecretDialog({super.key, required this.created});
  final ApiKeyCreated created;

  static Future<void> show(BuildContext context, ApiKeyCreated created) {
    return showDialog<void>(
      context: context,
      barrierDismissible: false, // can't click away from a one-time secret
      builder: (_) => ShowSecretDialog(created: created),
    );
  }

  @override
  State<ShowSecretDialog> createState() => _ShowSecretDialogState();
}

class _ShowSecretDialogState extends State<ShowSecretDialog> {
  bool _stored = false;
  bool _copied = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final secret = widget.created.secret;
    final curl =
        'curl -H "Authorization: Bearer $secret" \\\n     <backend>/v1/admin/profiles';

    return AlertDialog(
      title: Row(
        children: [
          Icon(Icons.warning_amber_rounded, color: theme.colorScheme.tertiary),
          const SizedBox(width: 8),
          const Expanded(child: Text("Save this key now — you won't see it again")),
        ],
      ),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('“${widget.created.name}” · ${widget.created.role}',
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: theme.colorScheme.outline)),
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: theme.colorScheme.outlineVariant),
              ),
              child: Row(
                children: [
                  Expanded(
                    child: SelectableText(
                      secret,
                      style: const TextStyle(
                          fontFamily: 'monospace', fontSize: 13),
                    ),
                  ),
                  const SizedBox(width: 8),
                  FilledButton.tonalIcon(
                    onPressed: () async {
                      await Clipboard.setData(ClipboardData(text: secret));
                      setState(() => _copied = true);
                    },
                    icon: Icon(_copied ? Icons.check : Icons.copy, size: 18),
                    label: Text(_copied ? 'Copied' : 'Copy'),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            Text('Test it', style: theme.textTheme.labelLarge),
            const SizedBox(height: 4),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHigh,
                borderRadius: BorderRadius.circular(8),
              ),
              child: SelectableText(curl,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
            ),
            const SizedBox(height: 8),
            Text(
              'Never put an API key in client-side code or a mobile app. '
              'Keys are for server-to-server use.',
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.error),
            ),
            const SizedBox(height: 8),
            CheckboxListTile(
              value: _stored,
              onChanged: (v) => setState(() => _stored = v ?? false),
              controlAffinity: ListTileControlAffinity.leading,
              contentPadding: EdgeInsets.zero,
              dense: true,
              title: const Text("I've stored this key"),
            ),
          ],
        ),
      ),
      actions: [
        FilledButton(
          onPressed: _stored ? () => Navigator.of(context).pop() : null,
          child: const Text('Close'),
        ),
      ],
    );
  }
}

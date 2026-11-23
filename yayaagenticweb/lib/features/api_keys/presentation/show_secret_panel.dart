import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../models/admin/api_key.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../../workspaces/presentation/role_chip.dart';

/// The one and only time a key's secret is shown. Done stays disabled until
/// the operator confirms they stored it; the dialog can't be clicked away.
class ShowSecretDialog extends StatefulWidget {
  const ShowSecretDialog({super.key, required this.created});
  final ApiKeyCreated created;

  static Future<void> show(BuildContext context, ApiKeyCreated created) {
    return showDialog<void>(
      context: context,
      barrierDismissible: false,
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
    final scheme = Theme.of(context).colorScheme;
    final secret = widget.created.secret;
    final curl = 'curl -H "Authorization: Bearer $secret" \\\n     <backend>/v1/admin/profiles';

    return PopScope(
      canPop: _stored,
      child: AlertDialog(
        title: const Text('Copy your new key now'),
        content: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 600),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            const YBanner(
              tone: YBannerTone.warning,
              title: 'You won’t see it again.',
              message: 'We only store a hash. If you lose this secret, revoke the key and create a new one.',
            ),
            const SizedBox(height: Gap.lg),
            Wrap(spacing: 8, runSpacing: 6, crossAxisAlignment: WrapCrossAlignment.center, children: [
              Text(widget.created.name, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
              RoleChip(widget.created.role),
              Text('${widget.created.keyPrefix}…', style: monoStyle(context, color: scheme.onSurfaceVariant)),
            ]),
            const SizedBox(height: Gap.md),
            Container(
              padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
              decoration: BoxDecoration(
                color: scheme.surfaceContainerHigh,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: scheme.outlineVariant),
              ),
              child: Row(children: [
                Expanded(child: SelectableText(secret, style: monoStyle(context, size: 13.5))),
                const SizedBox(width: 8),
                FilledButton.icon(
                  onPressed: () async {
                    await Clipboard.setData(ClipboardData(text: secret));
                    if (mounted) setState(() => _copied = true);
                  },
                  icon: Icon(_copied ? Icons.check : Icons.copy, size: 18),
                  label: Text(_copied ? 'Copied' : 'Copy secret'),
                ),
              ]),
            ),
            const SizedBox(height: Gap.lg),
            Text('Try it', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
            const SizedBox(height: 6),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(color: scheme.surfaceContainerLow, borderRadius: BorderRadius.circular(8)),
              child: SelectableText(curl, style: monoStyle(context, size: 12.5)),
            ),
            const SizedBox(height: Gap.sm),
            Text(
              'Keys are for server-to-server use. Never put one in a web page or mobile app.',
              style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant),
            ),
            const SizedBox(height: Gap.sm),
            CheckboxListTile(
              value: _stored,
              onChanged: (v) => setState(() => _stored = v ?? false),
              controlAffinity: ListTileControlAffinity.leading,
              contentPadding: EdgeInsets.zero,
              title: const Text('I’ve stored this key somewhere safe', style: TextStyle(fontSize: 14)),
            ),
          ]),
        ),
        actions: [
          Tooltip(
            message: _stored ? '' : 'Tick the box first — the secret can’t be shown again.',
            child: FilledButton(
              onPressed: _stored ? () => Navigator.of(context).pop() : null,
              child: const Text('Done'),
            ),
          ),
        ],
      ),
    );
  }
}

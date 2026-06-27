import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../models/admin/api_key.dart';
import '../application/api_keys_controller.dart';

/// Recent audit events for one key (design §8 audit endpoint, F-K6). Rendered
/// as a dialog; ADMIN+ only (the screen gates the entry point).
class ApiKeyAuditDialog extends ConsumerWidget {
  const ApiKeyAuditDialog({
    super.key,
    required this.workspaceCode,
    required this.apiKey,
  });

  final String workspaceCode;
  final ApiKey apiKey;

  static Future<void> show(
      BuildContext context, String workspaceCode, ApiKey key) {
    return showDialog<void>(
      context: context,
      builder: (_) =>
          ApiKeyAuditDialog(workspaceCode: workspaceCode, apiKey: key),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final events =
        ref.watch(apiKeyAuditProvider((workspaceCode, apiKey.id)));

    return AlertDialog(
      title: Text('Audit · ${apiKey.name}'),
      content: SizedBox(
        width: 520,
        child: events.when(
          loading: () => const Padding(
            padding: EdgeInsets.all(32),
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (e, _) => Text('Could not load audit: $e',
              style: TextStyle(color: theme.colorScheme.error)),
          data: (rows) {
            if (rows.isEmpty) {
              return const Padding(
                padding: EdgeInsets.all(24),
                child: Text('No events recorded yet.'),
              );
            }
            return ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 420),
              child: ListView.separated(
                shrinkWrap: true,
                itemCount: rows.length,
                separatorBuilder: (_, __) => const Divider(height: 1),
                itemBuilder: (_, i) => _AuditRow(rows[i]),
              ),
            );
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Close'),
        ),
      ],
    );
  }
}

class _AuditRow extends StatelessWidget {
  const _AuditRow(this.event);
  final ApiKeyAuditEvent event;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final when = event.occurredAt ?? '';
    final subtitleParts = <String>[
      if (event.detail != null && event.detail!.isNotEmpty) event.detail!,
      if (event.clientIp != null && event.clientIp!.isNotEmpty)
        'from ${event.clientIp}',
    ];
    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: _kindBadge(theme, event.kind),
      title: Text(when, style: theme.textTheme.bodySmall),
      subtitle: subtitleParts.isEmpty
          ? null
          : Text(subtitleParts.join('  ·  '),
              style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
    );
  }

  Widget _kindBadge(ThemeData theme, String kind) {
    final c = theme.colorScheme;
    final (bg, fg) = switch (kind) {
      'CREATE' => (c.primaryContainer, c.onPrimaryContainer),
      'REVOKE' => (c.errorContainer, c.onErrorContainer),
      'RENAME' => (c.secondaryContainer, c.onSecondaryContainer),
      'USE_DENIED' => (c.tertiaryContainer, c.onTertiaryContainer),
      _ => (c.surfaceContainerHigh, c.onSurfaceVariant),
    };
    return Container(
      width: 96,
      alignment: Alignment.center,
      padding: const EdgeInsets.symmetric(vertical: 4),
      decoration:
          BoxDecoration(color: bg, borderRadius: BorderRadius.circular(6)),
      child: Text(kind,
          style: TextStyle(
              color: fg, fontSize: 10, fontWeight: FontWeight.w700)),
    );
  }
}

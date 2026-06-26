import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/whatsapp_sender.dart';
import '../shared/admin_shared.dart';
import 'whatsapp_sender_wizard.dart';

/// Refetched on every write to keep status chips fresh.
final whatsAppSendersProvider =
    FutureProvider.family<List<WhatsAppSenderResponse>, String>(
  (ref, tenantId) async {
    final api = await ref.watch(adminApiProvider.future);
    return api.listWhatsAppSenders(tenantId);
  },
);

/// Tenants register WhatsApp senders here. The list is the canonical view;
/// adding a sender drops into a 4-step wizard. Twilio is not mentioned
/// anywhere in this screen — the operator describes a business and a phone
/// number, the platform handles the rest.
class WhatsAppSendersScreen extends ConsumerStatefulWidget {
  const WhatsAppSendersScreen({super.key});

  @override
  ConsumerState<WhatsAppSendersScreen> createState() =>
      _WhatsAppSendersScreenState();
}

class _WhatsAppSendersScreenState extends ConsumerState<WhatsAppSendersScreen> {
  void _newSender() {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => const WhatsAppSenderWizard(),
    ));
  }

  void _editSender(WhatsAppSenderResponse sender) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => WhatsAppSenderWizard(existing: sender),
    ));
  }

  Future<void> _runTransition({
    required String tenant,
    required WhatsAppSenderResponse sender,
    required Future<WhatsAppSenderResponse> Function(AdminApi api) call,
    required String successCopy,
  }) async {
    try {
      final api = await ref.read(adminApiProvider.future);
      await call(api);
      ref.invalidate(whatsAppSendersProvider(tenant));
      if (mounted) showSnack(context, successCopy);
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  Future<bool> _confirm(String title, String body, String confirmLabel) async {
    final result = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(title),
        content: Text(body),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: Text(confirmLabel)),
        ],
      ),
    );
    return result ?? false;
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'WhatsApp senders');
    }
    final senders = ref.watch(whatsAppSendersProvider(tenant));

    return AdminTwoPane(
      title: 'WhatsApp senders',
      subtitle:
          'Phone numbers your customers message. Each sender belongs to one workspace '
          'and can be bound to a profile so messages on that number reach the right bot.',
      list: senders.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e),
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (list) => list.isEmpty
            ? _EmptySendersState(onAdd: _newSender)
            : ListView.separated(
                itemBuilder: (_, i) => _SenderTile(
                  sender: list[i],
                  onEdit: () => _editSender(list[i]),
                  onSubmit: () async {
                    final confirmed = await _confirm(
                        'Submit for WhatsApp review?',
                        'Once submitted, the sender cannot be edited until WhatsApp '
                            'returns a verdict. This typically takes 1–3 business days.',
                        'Submit');
                    if (!confirmed) return;
                    await _runTransition(
                      tenant: tenant,
                      sender: list[i],
                      call: (api) => api.submitWhatsAppSender(tenant, list[i].id),
                      successCopy: 'Sender submitted',
                    );
                  },
                  onSuspend: () async {
                    final confirmed = await _confirm(
                        'Pause this sender?',
                        'Incoming messages will be acknowledged but no replies will be sent. '
                            'You can resume any time.',
                        'Pause');
                    if (!confirmed) return;
                    await _runTransition(
                      tenant: tenant,
                      sender: list[i],
                      call: (api) => api.suspendWhatsAppSender(tenant, list[i].id),
                      successCopy: 'Sender paused',
                    );
                  },
                  onResume: () async {
                    await _runTransition(
                      tenant: tenant,
                      sender: list[i],
                      call: (api) => api.resumeWhatsAppSender(tenant, list[i].id),
                      successCopy: 'Sender resumed',
                    );
                  },
                  onRevoke: () async {
                    final confirmed = await _confirm(
                        'Remove this sender?',
                        'The sender will be retired. Historical conversation records '
                            'are preserved, but the phone number can no longer send or receive.',
                        'Remove');
                    if (!confirmed) return;
                    await _runTransition(
                      tenant: tenant,
                      sender: list[i],
                      call: (api) => api.revokeWhatsAppSender(tenant, list[i].id),
                      successCopy: 'Sender removed',
                    );
                  },
                  onSync: () => _runTransition(
                    tenant: tenant,
                    sender: list[i],
                    call: (api) => api.syncWhatsAppSender(tenant, list[i].id),
                    successCopy: 'Status refreshed',
                  ),
                ),
                separatorBuilder: (_, i) => const SizedBox(height: 8),
                itemCount: list.length,
              ),
      ),
      form: SingleChildScrollView(
        child: FormCard(
          title: 'Add a WhatsApp sender',
          actions: [
            FilledButton.icon(
              onPressed: _newSender,
              icon: const Icon(Icons.add),
              label: const Text('Start sender registration'),
            ),
          ],
          child: const Text(
            'Register a phone number so your customers can message your business on '
            'WhatsApp. The wizard guides you through your business profile, the phone '
            'number, and the public display name, then submits for WhatsApp review.',
          ),
        ),
      ),
    );
  }
}

class _EmptySendersState extends StatelessWidget {
  const _EmptySendersState({required this.onAdd});
  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 480),
        child: Card(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.chat_outlined, size: 36, color: scheme.primary),
                const SizedBox(height: 12),
                Text('No WhatsApp senders yet',
                    style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                const Text(
                    'Register a phone number to let your customers message your business on '
                    'WhatsApp. WhatsApp will review your business and display name '
                    'before the sender goes live — usually within 1–3 business days.'),
                const SizedBox(height: 16),
                FilledButton.icon(
                  onPressed: onAdd,
                  icon: const Icon(Icons.add),
                  label: const Text('Register a sender'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _SenderTile extends StatelessWidget {
  const _SenderTile({
    required this.sender,
    required this.onEdit,
    required this.onSubmit,
    required this.onSuspend,
    required this.onResume,
    required this.onRevoke,
    required this.onSync,
  });

  final WhatsAppSenderResponse sender;
  final VoidCallback onEdit;
  final VoidCallback onSubmit;
  final VoidCallback onSuspend;
  final VoidCallback onResume;
  final VoidCallback onRevoke;
  final VoidCallback onSync;

  @override
  Widget build(BuildContext context) {
    final info = SenderStatusInfo.of(sender.status);
    return EditableRecordCard(
      onCloneEdit: onEdit,
      leading: _StatusChip(status: sender.status),
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(sender.displayName,
              style: Theme.of(context).textTheme.titleSmall),
          Text(sender.phoneE164,
              style: const TextStyle(fontSize: 13, fontFeatures: [])),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'Status', value: '${info.label} — ${info.description}'),
          DetailRow(label: 'Phone', value: sender.phoneE164, mono: true),
          DetailRow(label: 'Display name', value: sender.displayName),
          if (sender.approvedDisplayName != null)
            DetailRow(
                label: 'Approved display name',
                value: sender.approvedDisplayName!),
          DetailRow(
              label: 'Business',
              value:
                  '${sender.businessProfile.legalName} (${sender.businessProfile.vertical})'),
          DetailRow(
              label: 'Default locale', value: sender.defaultLocale, mono: true),
          if (sender.onboarding.lastPolledAt != null)
            DetailRow(
                label: 'Last checked', value: sender.onboarding.lastPolledAt!),
          if (sender.onboarding.failureMessage != null)
            DetailRow(
                label: 'Review notes',
                value: sender.onboarding.failureMessage!),
          if (sender.createdAt != null)
            DetailRow(label: 'Created', value: sender.createdAt!),
        ],
      ),
      extraActions: [
        if (sender.status == 'SUBMITTING' || sender.status == 'PENDING_REVIEW')
          TextButton.icon(
            onPressed: onSync,
            icon: const Icon(Icons.refresh, size: 18),
            label: const Text('Refresh status'),
          ),
        if (sender.status == 'DRAFT')
          FilledButton.tonalIcon(
            onPressed: onSubmit,
            icon: const Icon(Icons.send, size: 18),
            label: const Text('Submit for review'),
          ),
        if (sender.status == 'ACTIVE')
          TextButton.icon(
            onPressed: onSuspend,
            icon: const Icon(Icons.pause_circle_outline, size: 18),
            label: const Text('Pause'),
          ),
        if (sender.status == 'SUSPENDED')
          FilledButton.tonalIcon(
            onPressed: onResume,
            icon: const Icon(Icons.play_circle_outline, size: 18),
            label: const Text('Resume'),
          ),
        if (sender.status != 'REVOKED')
          TextButton.icon(
            onPressed: onRevoke,
            icon: const Icon(Icons.delete_outline, size: 18),
            label: const Text('Remove'),
            style: TextButton.styleFrom(
                foregroundColor: Theme.of(context).colorScheme.error),
          ),
      ],
    );
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.status});
  final String status;

  Color _bg(ColorScheme s) {
    switch (status) {
      case 'ACTIVE':
        return s.primaryContainer;
      case 'PENDING_REVIEW':
      case 'SUBMITTING':
        return s.tertiaryContainer;
      case 'FAILED':
      case 'REVOKED':
        return s.errorContainer;
      case 'SUSPENDED':
        return s.secondaryContainer;
      default:
        return s.surfaceContainerHighest;
    }
  }

  Color _fg(ColorScheme s) {
    switch (status) {
      case 'ACTIVE':
        return s.onPrimaryContainer;
      case 'PENDING_REVIEW':
      case 'SUBMITTING':
        return s.onTertiaryContainer;
      case 'FAILED':
      case 'REVOKED':
        return s.onErrorContainer;
      case 'SUSPENDED':
        return s.onSecondaryContainer;
      default:
        return s.onSurfaceVariant;
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: _bg(scheme),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        SenderStatusInfo.of(status).label,
        style: TextStyle(
            fontSize: 11, fontWeight: FontWeight.w700, color: _fg(scheme)),
      ),
    );
  }
}

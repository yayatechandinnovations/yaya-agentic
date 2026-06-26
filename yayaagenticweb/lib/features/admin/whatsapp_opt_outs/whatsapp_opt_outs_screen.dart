import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/channel_opt_out.dart';
import '../shared/admin_shared.dart';
import '../whatsapp_senders/whatsapp_senders_screen.dart' show whatsAppSendersProvider;

final whatsAppOptOutsProvider = FutureProvider.family<
    List<ChannelOptOutResponse>, ({String tenant, String sender})>(
  (ref, key) async {
    final api = await ref.watch(adminApiProvider.future);
    return api.listOptOuts(key.tenant, key.sender);
  },
);

class WhatsAppOptOutsScreen extends ConsumerStatefulWidget {
  const WhatsAppOptOutsScreen({super.key});

  @override
  ConsumerState<WhatsAppOptOutsScreen> createState() =>
      _WhatsAppOptOutsScreenState();
}

class _WhatsAppOptOutsScreenState extends ConsumerState<WhatsAppOptOutsScreen> {
  String? _senderId;

  Future<void> _remove(ChannelOptOutResponse row) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final confirmed = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('Remove opt-out?'),
            content: Text(
                'Removing the opt-out for ${row.endUserPhone} requires that the user '
                'has consented to be contacted again. Operators are responsible for '
                'compliance — this action is audited.'),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Cancel')),
              FilledButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: const Text('Remove opt-out')),
            ],
          ),
        ) ??
        false;
    if (!confirmed) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.removeOptOut(tenant, _senderId!, row.endUserPhone);
      ref.invalidate(
          whatsAppOptOutsProvider((tenant: tenant, sender: _senderId!)));
      if (mounted) showSnack(context, 'Opt-out removed');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'WhatsApp opt-outs');
    }
    final senders = ref.watch(whatsAppSendersProvider(tenant));

    return AdminTwoPane(
      title: 'WhatsApp opt-outs',
      subtitle:
          'Users who replied STOP, UNSUB, CANCEL, or QUIT to this sender. '
          'Mandated by Meta — they cannot be re-contacted until they reply START.',
      list: senders.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e),
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (senderList) {
          final eligible =
              senderList.where((s) => s.status != 'REVOKED').toList();
          if (eligible.isEmpty) {
            return const Center(
                child: Text('Register a WhatsApp sender first.'));
          }
          if (_senderId == null && eligible.isNotEmpty) {
            _senderId = eligible.first.id;
          }
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              DropdownButtonFormField<String>(
                initialValue: _senderId,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Sender'),
                items: eligible
                    .map((s) => DropdownMenuItem(
                          value: s.id,
                          child: Text('${s.displayName} (${s.phoneE164})'),
                        ))
                    .toList(),
                onChanged: (v) => setState(() => _senderId = v),
              ),
              const SizedBox(height: 12),
              Expanded(child: _optOutList(tenant)),
            ],
          );
        },
      ),
      form: SingleChildScrollView(
        child: FormCard(
          title: 'About opt-outs',
          child: const Text(
            'Opt-outs are stored as a non-negotiable Meta-policy requirement. '
            'Outbound dispatch consults the opt-out roster before every send — '
            'including templates. Removing a row should only happen when you have '
            'documented evidence the user consented to be re-contacted (e.g. an '
            'in-person opt-in form). Every removal is audited.',
          ),
        ),
      ),
    );
  }

  Widget _optOutList(String tenant) {
    if (_senderId == null) return const SizedBox.shrink();
    final optOuts = ref.watch(
        whatsAppOptOutsProvider((tenant: tenant, sender: _senderId!)));
    return optOuts.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Text(formatError(e),
          style: TextStyle(color: Theme.of(context).colorScheme.error)),
      data: (list) {
        if (list.isEmpty) {
          return const Center(child: Text('No opt-outs on this sender.'));
        }
        return ListView.separated(
          itemBuilder: (_, i) => Card(
            child: ListTile(
              title: Text(list[i].endUserPhone),
              subtitle: Text(
                  '${list[i].optedOutAt ?? '—'} · reason: ${list[i].reason ?? 'user_stop'}'),
              trailing: TextButton.icon(
                onPressed: () => _remove(list[i]),
                icon: const Icon(Icons.restart_alt, size: 18),
                label: const Text('Remove'),
                style: TextButton.styleFrom(
                    foregroundColor: Theme.of(context).colorScheme.error),
              ),
            ),
          ),
          separatorBuilder: (_, i) => const SizedBox(height: 4),
          itemCount: list.length,
        );
      },
    );
  }
}

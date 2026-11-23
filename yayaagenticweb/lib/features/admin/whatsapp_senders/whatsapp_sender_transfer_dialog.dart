import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../models/admin/profile.dart';
import '../../../models/admin/whatsapp_sender.dart';
import '../shared/admin_shared.dart';
import '../tenants/tenants_screen.dart' show tenantsProvider;

/// The assistants of a workspace, one entry per profile at its latest version.
final _destinationProfilesProvider =
    FutureProvider.family<List<ProfileResponse>, String>((ref, tenant) async {
  final api = await ref.watch(adminApiProvider.future);
  final latest = <String, ProfileResponse>{};
  for (final p in await api.listProfiles(tenant: tenant)) {
    final seen = latest[p.id];
    if (seen == null || p.version > seen.version) latest[p.id] = p;
  }
  return latest.values.toList()..sort((a, b) => a.displayName.compareTo(b.displayName));
});

/// Moves a live number to another workspace (whatsapp-sender-transfer-design.md).
/// Previews the move with a dry run first; pops the applied result.
class WhatsAppSenderTransferDialog extends ConsumerStatefulWidget {
  const WhatsAppSenderTransferDialog({
    super.key,
    required this.tenant,
    required this.sender,
  });

  final String tenant;
  final WhatsAppSenderResponse sender;

  @override
  ConsumerState<WhatsAppSenderTransferDialog> createState() =>
      _WhatsAppSenderTransferDialogState();
}

class _WhatsAppSenderTransferDialogState
    extends ConsumerState<WhatsAppSenderTransferDialog> {
  String? _destination;
  String? _profileId;
  bool _copyOptOuts = true;
  bool _busy = false;
  String? _error;
  WhatsAppSenderTransferResponse? _plan;

  WhatsAppSenderTransferRequest get _request => WhatsAppSenderTransferRequest(
        destinationTenant: _destination!,
        profileId: _profileId,
        copyOptOuts: _copyOptOuts,
      );

  /// Any change invalidates the preview: the move applies what was previewed.
  void _change(VoidCallback update) => setState(() {
        update();
        _plan = null;
        _error = null;
      });

  Future<void> _run({required bool dryRun}) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final api = await ref.read(adminApiProvider.future);
      final result = await api.transferWhatsAppSender(
          widget.tenant, widget.sender.id, _request, dryRun: dryRun);
      if (!mounted) return;
      if (dryRun) {
        setState(() => _plan = result);
      } else {
        Navigator.pop(context, result);
      }
    } catch (e) {
      if (mounted) setState(() => _error = formatError(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final tenants = ref.watch(tenantsProvider);
    final destinations = tenants.maybeWhen(
      data: (list) => list
          .where((t) => t.id != widget.tenant && t.status == 'ACTIVE')
          .toList()
        ..sort((a, b) => a.displayName.compareTo(b.displayName)),
      orElse: () => const [],
    );

    return AlertDialog(
      title: Text('Move ${widget.sender.phoneE164} to another workspace'),
      content: SizedBox(
        width: 520,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                'The number keeps its WhatsApp registration and approved name — no new '
                'code and no new review. Its conversation history stays in this workspace.',
              ),
              const SizedBox(height: 16),
              DropdownButtonFormField<String>(
                key: const Key('transfer-destination'),
                initialValue: _destination,
                isExpanded: true,
                decoration: const InputDecoration(
                  labelText: 'Destination workspace',
                  border: OutlineInputBorder(),
                ),
                items: [
                  for (final t in destinations)
                    DropdownMenuItem(
                      value: t.id,
                      child: Text('${t.displayName} (${t.id})', overflow: TextOverflow.ellipsis),
                    ),
                ],
                onChanged: _busy
                    ? null
                    : (v) => _change(() {
                          _destination = v;
                          _profileId = null;
                        }),
              ),
              if (tenants.isLoading)
                const Padding(
                  padding: EdgeInsets.only(top: 8),
                  child: LinearProgressIndicator(),
                ),
              const SizedBox(height: 12),
              if (_destination != null) _assistantField(_destination!),
              CheckboxListTile(
                key: const Key('transfer-copy-opt-outs'),
                contentPadding: EdgeInsets.zero,
                value: _copyOptOuts,
                onChanged: _busy ? null : (v) => _change(() => _copyOptOuts = v ?? true),
                title: const Text('Keep opt-outs'),
                subtitle: const Text(
                    'People who opted out of this number won\'t hear from it in the new '
                    'workspace either.'),
              ),
              if (_plan != null) _PlanSummary(plan: _plan!),
              if (_error != null) ...[
                const SizedBox(height: 8),
                Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        if (_plan == null)
          FilledButton.tonal(
            onPressed: _busy || _destination == null ? null : () => _run(dryRun: true),
            child: const Text('Preview'),
          )
        else
          FilledButton(
            key: const Key('transfer-apply'),
            onPressed: _busy ? null : () => _run(dryRun: false),
            child: const Text('Move number'),
          ),
      ],
    );
  }

  Widget _assistantField(String destination) {
    final profiles = ref.watch(_destinationProfilesProvider(destination));
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: profiles.when(
        loading: () => const LinearProgressIndicator(),
        error: (e, _) => Text(formatError(e),
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (list) => DropdownButtonFormField<String?>(
          key: Key('transfer-assistant-$destination'),
          initialValue: _profileId,
          isExpanded: true,
          decoration: const InputDecoration(
            labelText: 'Assistant that answers it',
            helperText: 'You can also add a route later in the destination workspace',
            border: OutlineInputBorder(),
          ),
          items: [
            const DropdownMenuItem<String?>(value: null, child: Text('None yet')),
            for (final p in list)
              DropdownMenuItem<String?>(
                value: p.id,
                child: Text(p.displayName, overflow: TextOverflow.ellipsis),
              ),
          ],
          onChanged: _busy ? null : (v) => _change(() => _profileId = v),
        ),
      ),
    );
  }
}

class _PlanSummary extends StatelessWidget {
  const _PlanSummary({required this.plan});
  final WhatsAppSenderTransferResponse plan;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final e = plan.effects;
    String count(int n, String one, String many) => '$n ${n == 1 ? one : many}';
    final lines = <String>[
      if (e.optOutsCopied > 0) '${count(e.optOutsCopied, 'opt-out', 'opt-outs')} carried over',
      if (e.openSessionsClosed > 0)
        '${count(e.openSessionsClosed, 'open conversation', 'open conversations')} closed here',
      if (e.parkedOutboundDropped > 0)
        '${count(e.parkedOutboundDropped, 'queued reply', 'queued replies')} dropped',
      if (e.bindingsDeactivated > 0)
        '${count(e.bindingsDeactivated, 'route', 'routes')} in this workspace switched off',
      'Existing contacts must write first (or get a template) before free-form replies — '
          'WhatsApp\'s 24-hour window starts over in the new workspace',
    ];
    return Container(
      key: const Key('transfer-plan'),
      width: double.infinity,
      margin: const EdgeInsets.only(top: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('What happens', style: theme.textTheme.labelLarge),
          const SizedBox(height: 6),
          for (final line in lines)
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text('• $line', style: const TextStyle(fontSize: 13)),
            ),
          for (final w in plan.warnings)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(Icons.warning_amber_rounded, size: 16, color: theme.colorScheme.error),
                  const SizedBox(width: 6),
                  Expanded(child: Text(w, style: const TextStyle(fontSize: 13))),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

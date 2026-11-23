import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../models/admin/profile.dart';
import '../../../models/admin/whatsapp_sender.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../tenants/tenants_screen.dart' show tenantsProvider;

/// The assistants of a workspace, one entry per profile at its latest version.
final _destinationProfilesProvider =
    FutureProvider.family<List<ProfileResponse>, String>((ref, tenant) async {
  liveResource(ref, AdminResourceKind.profile);
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
      if (mounted) setState(() => _error = ApiErrorInfo.from(e).message);
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
      title: Text('Move ${widget.sender.phoneE164} to another workspace?'),
      content: SizedBox(
        width: 560,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                'The number keeps its WhatsApp registration and approved name — no new code and no new review. '
                'Its conversation history stays in this workspace, and this workspace stops answering it.',
                style: TextStyle(fontSize: 14, height: 1.5, color: theme.colorScheme.onSurface),
              ),
              const SizedBox(height: Gap.lg),
              YFieldShell(
                label: 'Destination workspace',
                wireKey: 'destinationTenant',
                required: true,
                helper: tenants.isLoading ? 'Loading workspaces…' : 'Only other active workspaces are listed.',
                child: DropdownButtonFormField<String>(
                  key: const Key('transfer-destination'),
                  initialValue: _destination,
                  isExpanded: true,
                  hint: const Text('Choose a workspace'),
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
              ),
              const SizedBox(height: Gap.fieldGap),
              if (_destination != null) ...[
                _assistantField(_destination!),
                const SizedBox(height: Gap.fieldGap),
              ],
              KeyedSubtree(
                key: const Key('transfer-copy-opt-outs'),
                child: YSwitchField(
                  title: 'Keep opt-outs',
                  wireKey: 'copyOptOuts',
                  description: 'People who opted out of this number won’t hear from it in the new workspace either.',
                  value: _copyOptOuts,
                  onChanged: (v) {
                    if (!_busy) _change(() => _copyOptOuts = v);
                  },
                ),
              ),
              if (_plan != null) _PlanSummary(plan: _plan!),
              if (_error != null) ...[
                const SizedBox(height: Gap.md),
                YBanner(tone: YBannerTone.error, title: 'Couldn’t move the number.', message: _error!),
              ],
            ],
          ),
        ),
      ),
      actions: [
        OutlinedButton(
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
            child: Text('Move ${widget.sender.phoneE164}'),
          ),
      ],
    );
  }

  Widget _assistantField(String destination) {
    final profiles = ref.watch(_destinationProfilesProvider(destination));
    return YFieldShell(
      label: 'Assistant that answers it',
      wireKey: 'profileId',
      optional: true,
      helper: profiles.hasError
          ? 'Couldn’t load that workspace’s assistants: ${ApiErrorInfo.from(profiles.error!).message}'
          : 'You can also add a route later in the destination workspace.',
      child: profiles.isLoading && !profiles.hasValue
          ? const Padding(padding: EdgeInsets.symmetric(vertical: 18), child: LinearProgressIndicator())
          : DropdownButtonFormField<String?>(
              key: Key('transfer-assistant-$destination'),
              initialValue: _profileId,
              isExpanded: true,
              items: [
                const DropdownMenuItem<String?>(value: null, child: Text('None yet')),
                for (final p in profiles.valueOrNull ?? const <ProfileResponse>[])
                  DropdownMenuItem<String?>(
                    value: p.id,
                    child: Text(p.displayName, overflow: TextOverflow.ellipsis),
                  ),
              ],
              onChanged: _busy ? null : (v) => _change(() => _profileId = v),
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
        color: theme.colorScheme.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('What happens', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: theme.colorScheme.onSurface)),
          const SizedBox(height: 6),
          for (final line in lines)
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text('• $line', style: TextStyle(fontSize: 13, height: 1.45, color: theme.colorScheme.onSurface)),
            ),
          for (final w in plan.warnings)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(Icons.warning_amber_rounded, size: 16, color: StatusColors.of(context).onWarningContainer),
                  const SizedBox(width: 6),
                  Expanded(child: Text(w, style: TextStyle(fontSize: 13, height: 1.45, color: theme.colorScheme.onSurface))),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

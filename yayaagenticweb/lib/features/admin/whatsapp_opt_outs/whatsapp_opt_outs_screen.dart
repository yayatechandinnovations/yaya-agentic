import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/channel_opt_out.dart';
import '../../../models/admin/whatsapp_sender.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/form/y_resource_picker.dart';
import '../../../ui/tokens.dart';
import '../shared/admin_shared.dart';
import '../whatsapp_senders/whatsapp_senders_screen.dart' show whatsAppSendersProvider;

final whatsAppOptOutsProvider = FutureProvider.family<
    List<ChannelOptOutResponse>, ({String tenant, String sender})>(
  (ref, key) async {
    liveResource(ref, AdminResourceKind.optOut);
    final api = await ref.watch(adminApiProvider.future);
    return api.listOptOuts(key.tenant, key.sender);
  },
);

/// Phone digits only, so "809 555" finds "+18095551234".
String _digits(String s) => s.replaceAll(RegExp(r'\D'), '');

String _reasonLabel(String? reason) => switch (reason) {
      null || '' || 'user_stop' => 'Replied STOP',
      final r => r,
    };

/// People who told a WhatsApp number to stop messaging them. Outbound
/// dispatch checks this roster before every send, templates included; the
/// only write here is re-subscribing someone, which is audited.
class WhatsAppOptOutsScreen extends ConsumerStatefulWidget {
  const WhatsAppOptOutsScreen({super.key});

  @override
  ConsumerState<WhatsAppOptOutsScreen> createState() => _WhatsAppOptOutsScreenState();
}

class _WhatsAppOptOutsScreenState extends ConsumerState<WhatsAppOptOutsScreen> {
  String? _senderId;
  final Set<String> _removing = {};

  Future<void> _resubscribe(String tenant, WhatsAppSenderResponse sender, ChannelOptOutResponse row) async {
    final ok = await confirmAction(
      context,
      title: 'Re-subscribe ${row.endUserPhone}?',
      message: '${row.endUserPhone} will start receiving WhatsApp messages from '
          '${sender.displayName} (${sender.phoneE164}) again — replies, templates and broadcasts.\n\n'
          'Only do this when they have asked for messages again (for example a signed opt-in form). '
          'Meta holds you responsible for consent, and this change is recorded in the audit log.',
      confirmLabel: 'Re-subscribe',
      destructive: true,
    );
    if (!ok || !mounted) return;
    setState(() => _removing.add(row.endUserPhone));
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.removeOptOut(tenant, sender.id, row.endUserPhone);
      ref.invalidate(whatsAppOptOutsProvider((tenant: tenant, sender: sender.id)));
      if (mounted) showToast(context, '${row.endUserPhone} can receive messages from ${sender.displayName} again');
    } catch (e) {
      if (mounted) showToast(context, 'Couldn’t re-subscribe ${row.endUserPhone}: ${ApiErrorInfo.from(e).message}', error: true);
    } finally {
      if (mounted) setState(() => _removing.remove(row.endUserPhone));
    }
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'WhatsApp opt-outs');
    }
    final senders = ref.watch(whatsAppSendersProvider(tenant));
    final eligible = (senders.valueOrNull ?? const <WhatsAppSenderResponse>[]).where((s) => s.status != 'REVOKED').toList();
    if (_senderId == null || !eligible.any((s) => s.id == _senderId)) {
      _senderId = eligible.firstOrNull?.id;
    }
    final sender = eligible.where((s) => s.id == _senderId).firstOrNull;

    return YPage(
      title: 'WhatsApp opt-outs',
      subtitle: 'People who replied STOP, UNSUB, CANCEL or QUIT to a number. Meta requires that they get no more '
          'messages from it — not even templates — until they reply START.',
      toolbar: eligible.isEmpty
          ? null
          : ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 480),
              child: YResourcePicker(
                label: 'WhatsApp number',
                wireKey: 'senderId',
                value: _senderId,
                resources: [
                  for (final s in eligible) YResource(id: s.id, name: s.displayName, meta: '${s.phoneE164} · ${s.status.toLowerCase()}'),
                ],
                onChanged: (v) => setState(() => _senderId = v ?? _senderId),
              ),
            ),
      child: LiveAsyncView<List<WhatsAppSenderResponse>>(
        value: senders,
        what: 'WhatsApp numbers',
        onRetry: () => ref.invalidate(whatsAppSendersProvider(tenant)),
        data: (context, _) {
          if (sender == null) {
            return const YEmptyState(
              icon: Icons.block_outlined,
              title: 'No WhatsApp number yet',
              message: 'Opt-outs are kept per WhatsApp number. Connect a number under WhatsApp senders; '
                  'anyone who replies STOP to it will be listed here.',
            );
          }
          return _OptOutTable(
            tenant: tenant,
            sender: sender,
            removing: _removing,
            onResubscribe: (row) => _resubscribe(tenant, sender, row),
          );
        },
      ),
    );
  }
}

class _OptOutTable extends ConsumerWidget {
  const _OptOutTable({required this.tenant, required this.sender, required this.removing, required this.onResubscribe});
  final String tenant;
  final WhatsAppSenderResponse sender;
  final Set<String> removing;
  final ValueChanged<ChannelOptOutResponse> onResubscribe;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final key = (tenant: tenant, sender: sender.id);
    final scheme = Theme.of(context).colorScheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const YBanner(
        tone: YBannerTone.info,
        message: 'Every send from this number checks this list first. Re-subscribe someone only with documented '
            'consent — for example an in-person opt-in form. Every re-subscription is audited.',
      ),
      const SizedBox(height: Gap.lg),
      Expanded(
        child: LiveAsyncView<List<ChannelOptOutResponse>>(
          value: ref.watch(whatsAppOptOutsProvider(key)),
          what: 'opt-outs',
          onRetry: () => ref.invalidate(whatsAppOptOutsProvider(key)),
          data: (context, list) => YTable<ChannelOptOutResponse>(
            rows: list,
            noun: 'opt-outs',
            idOf: (r) => r.endUserPhone,
            searchHint: 'Search by phone number',
            searchTextOf: (r) => '${r.endUserPhone} ${_digits(r.endUserPhone)} ${_reasonLabel(r.reason)}',
            filters: [
              YFilter('Last 7 days', (r) {
                final t = DateTime.tryParse(r.optedOutAt ?? '');
                return t != null && DateTime.now().difference(t.toLocal()).inDays < 7;
              }),
            ],
            empty: YEmptyState(
              icon: Icons.check_circle_outline,
              title: 'Nobody has opted out of ${sender.displayName}',
              message: 'When someone replies STOP to ${sender.phoneE164}, they’re listed here and no further messages reach them.',
            ),
            columns: [
              YColumn(
                label: 'Phone number',
                flex: 3,
                sortKey: (r) => r.endUserPhone,
                cell: (context, r) => Text(r.endUserPhone, style: monoStyle(context, size: 13.5, weight: FontWeight.w500)),
              ),
              YColumn(
                label: 'Reason',
                flex: 2,
                sortKey: (r) => _reasonLabel(r.reason),
                cell: (context, r) => YBadge(_reasonLabel(r.reason), tone: YTone.warning, icon: Icons.block_outlined),
              ),
              YColumn(
                label: 'Opted out',
                flex: 2,
                sortKey: (r) => r.optedOutAt ?? '',
                cell: (context, r) => Tooltip(
                  message: r.optedOutAt ?? 'Unknown',
                  child: Text(r.optedOutAt == null ? 'Unknown' : formatRelativeIso(r.optedOutAt),
                      style: TextStyle(color: r.optedOutAt == null ? scheme.onSurfaceVariant : null)),
                ),
              ),
              YColumn(
                label: '',
                width: 170,
                alignEnd: true,
                cell: (context, r) => removing.contains(r.endUserPhone)
                    ? const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : TextButton.icon(
                        onPressed: () => onResubscribe(r),
                        icon: const Icon(Icons.restart_alt, size: 18),
                        label: const Text('Re-subscribe'),
                        style: TextButton.styleFrom(foregroundColor: scheme.error),
                      ),
              ),
            ],
          ),
        ),
      ),
    ]);
  }
}

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/live/live_resource.dart';
import '../../../models/admin/platform_sender.dart';
import '../../../models/admin/tenant.dart';
import '../../../models/admin/whatsapp_sender.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../channel_bindings/channel_ui.dart';
import '../tenants/tenants_screen.dart' show tenantsProvider;
import '../whatsapp_senders/sender_registration_repository.dart';
import '../whatsapp_senders/whatsapp_sender_transfer_dialog.dart';
import '../whatsapp_senders/whatsapp_sender_wizard.dart';
import '../whatsapp_senders/whatsapp_senders_screen.dart'
    show SenderLifecycleActions, SenderStatusSummary, SenderSubmitDialog, SenderVerificationCodeDialog, senderBadgeLabel, senderTone;
import 'platform_sender_providers.dart';

/// Platform admins: WhatsApp numbers the platform owns and shares with
/// workspaces (whatsapp-shared-sender-design.md §5, §12). Each assigned
/// workspace sends on the number under its own label; only its registered
/// staff reach its assistants.
class PlatformSendersScreen extends ConsumerStatefulWidget {
  const PlatformSendersScreen({super.key});

  @override
  ConsumerState<PlatformSendersScreen> createState() => _PlatformSendersScreenState();
}

class _PlatformSendersScreenState extends ConsumerState<PlatformSendersScreen> {
  String? _openId;

  void _register() {
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => const WhatsAppSenderWizard(platform: true)));
  }

  Future<void> _open(PlatformSenderResponse p) async {
    setState(() => _openId = p.id);
    await showYSheet<void>(
      context,
      label: 'Platform sender',
      width: 760,
      builder: (_) => PlatformSenderSheet(senderId: p.id),
    );
    if (mounted) setState(() => _openId = null);
  }

  @override
  Widget build(BuildContext context) {
    final senders = ref.watch(platformSendersProvider);
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.whatsappSender));
    final register = FilledButton.icon(
      onPressed: _register,
      icon: const Icon(Icons.add, size: 18),
      label: const Text('Register a platform sender'),
    );
    return YPage(
      title: 'Platform senders',
      subtitle: 'WhatsApp numbers the platform owns and shares. Assigned workspaces send on them under their own '
          'business name; only each workspace’s registered staff reach its assistants.',
      actions: [register],
      child: LiveAsyncView<List<PlatformSenderResponse>>(
        value: senders,
        what: 'platform senders',
        onRetry: () => ref.invalidate(platformSendersProvider),
        data: (context, list) => YTable<PlatformSenderResponse>(
          rows: list,
          noun: 'senders',
          idOf: (p) => p.id,
          selectedId: _openId,
          highlightIds: fresh,
          searchHint: 'Search by name or number',
          searchTextOf: (p) => '${p.title} ${p.sender.displayName} ${p.sender.phoneE164} ${SenderStatusInfo.of(p.sender.status).label}',
          onRowTap: _open,
          filters: [
            YFilter('Live', (p) => p.sender.status == 'ACTIVE'),
            YFilter('In review', (p) => p.sender.status == 'SUBMITTING' || p.sender.status == 'PENDING_REVIEW'),
            YFilter('Unassigned', (p) => p.assignmentCount == 0 && p.sender.status != 'REVOKED'),
          ],
          empty: YEmptyState(
            icon: Icons.hub_outlined,
            title: 'No platform senders yet',
            message: 'A platform sender is a number several workspaces share — for example a notices number. Register '
                'one, or move a workspace’s number in from its sender card.',
            action: register,
          ),
          columns: [
            YColumn(
              label: 'Sender',
              flex: 3,
              sortKey: (p) => p.title.toLowerCase(),
              cell: (context, p) => YCellStack(primary: p.title, secondary: p.sender.phoneE164),
            ),
            YColumn(
              label: 'Status',
              flex: 2,
              sortKey: (p) => p.sender.status,
              cell: (context, p) => Align(
                alignment: Alignment.centerLeft,
                child: YBadge(senderBadgeLabel(p.sender), tone: senderTone(p.sender), icon: iconForStatus(p.sender.status)),
              ),
            ),
            YColumn(
              label: 'Workspaces',
              width: 150,
              sortKey: (p) => p.assignmentCount.toString().padLeft(6, '0'),
              cell: (context, p) => Text('${p.assignmentCount}', key: ValueKey('assignments-${p.id}'), style: monoStyle(context, size: 13)),
            ),
            YColumn(
              label: 'Updated',
              width: 120,
              sortKey: (p) => p.sender.updatedAt ?? p.sender.createdAt ?? '',
              cell: (context, p) =>
                  Text(fresh.contains(p.id) ? 'Just now' : formatRelativeIso(p.sender.updatedAt ?? p.sender.createdAt)),
            ),
          ],
        ),
      ),
    );
  }
}

/// One platform sender: its lifecycle, the workspaces it serves, and what
/// arrived that no workspace could claim.
class PlatformSenderSheet extends ConsumerStatefulWidget {
  const PlatformSenderSheet({super.key, required this.senderId});

  final String senderId;

  @override
  ConsumerState<PlatformSenderSheet> createState() => _PlatformSenderSheetState();
}

class _PlatformSenderSheetState extends ConsumerState<PlatformSenderSheet> {
  bool _busy = false;

  Future<void> _run(Future<void> Function(AdminApi api) call, String successCopy) async {
    setState(() => _busy = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await call(api);
      ref.invalidate(platformSendersProvider);
      if (mounted) showToast(context, successCopy);
    } catch (e) {
      if (mounted) showToast(context, platformSenderErrorMessage(e), error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _edit(PlatformSenderResponse p) {
    Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => WhatsAppSenderWizard(existing: p.sender, platform: true, unroutedReply: p.unroutedReply)));
  }

  Future<void> _submit(WhatsAppSenderResponse s) async {
    final method = await showDialog<String>(context: context, builder: (_) => SenderSubmitDialog(phoneE164: s.phoneE164));
    if (method == null) return;
    await _run((api) => PlatformSenderRepository(api).submit(s.id, verificationMethod: method),
        'Submitted ${s.phoneE164}. If WhatsApp sends a code to the phone, enter it here.');
  }

  Future<void> _verify(WhatsAppSenderResponse s) async {
    final code =
        await showDialog<String>(context: context, builder: (_) => SenderVerificationCodeDialog(phoneE164: s.phoneE164));
    if (code == null) return;
    await _run((api) => PlatformSenderRepository(api).verify(s.id, code),
        'Code accepted — WhatsApp is finishing the registration.');
  }

  Future<void> _suspend(PlatformSenderResponse p) async {
    final ok = await confirmAction(
      context,
      title: 'Pause ${p.sender.phoneE164}?',
      message: 'Every workspace on this number stops sending and answering until you resume it. Assignments, members '
          'and keywords are kept.',
      confirmLabel: 'Pause sender',
    );
    if (!ok) return;
    await _run((api) => api.suspendPlatformSender(p.id), 'Paused ${p.sender.phoneE164}');
  }

  Future<void> _revoke(PlatformSenderResponse p) async {
    final s = p.sender;
    final live = s.status == 'ACTIVE' || s.status == 'SUSPENDED';
    final bool ok;
    if (live) {
      ok = await confirmTyped(
        context,
        title: 'Remove ${s.phoneE164}?',
        message: 'The number stops sending and receiving immediately. It can be registered again later.',
        expected: s.phoneE164,
        confirmLabel: 'Remove sender',
      );
    } else {
      ok = await confirmAction(
        context,
        title: 'Remove ${s.phoneE164}?',
        message: 'The ${s.status == 'DRAFT' ? 'draft' : 'sender'} is retired. The number can be registered again later.',
        confirmLabel: 'Remove sender',
        destructive: true,
      );
    }
    if (!ok || !mounted) return;
    setState(() => _busy = true);
    Object? failure;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.revokePlatformSender(s.id);
      ref.invalidate(platformSendersProvider);
    } catch (e) {
      failure = e;
    }
    if (!mounted) return;
    setState(() => _busy = false);
    if (failure == null) {
      showToast(context, 'Removed ${s.phoneE164}');
    } else if (ApiErrorInfo.from(failure).code == 'sender_has_assignments') {
      ref.invalidate(platformSenderAssignmentsProvider(s.id));
      await showDialog<void>(context: context, builder: (_) => const _HasAssignmentsDialog(action: 'remove'));
    } else {
      showToast(context, platformSenderErrorMessage(failure), error: true);
    }
  }

  /// The server refuses while assignments are live (`sender_has_assignments`);
  /// the dialog says so in place.
  Future<void> _transfer(PlatformSenderResponse p) async {
    final result = await showDialog<WhatsAppSenderTransferResponse>(
      context: context,
      builder: (_) => WhatsAppSenderTransferDialog(tenant: null, sender: p.sender),
    );
    if (result == null) return;
    ref.invalidate(platformSendersProvider);
    if (mounted) showToast(context, 'Moved to ${result.destination?.tenantId ?? 'the workspace'}.');
  }

  @override
  Widget build(BuildContext context) {
    final list = ref.watch(platformSendersProvider).valueOrNull ?? const <PlatformSenderResponse>[];
    final p = list.where((s) => s.id == widget.senderId).firstOrNull;
    if (p == null) {
      return YSheetScaffold(
        title: 'Platform sender',
        body: const YEmptyState(
          icon: Icons.search_off,
          title: 'This sender is gone',
          message: 'It was removed or moved to a workspace while this was open.',
        ),
      );
    }
    final s = p.sender;
    return YSheetScaffold(
      title: p.title,
      subtitle: '${s.phoneE164} · ${SenderStatusInfo.of(s.status).label} · '
          '${p.assignmentCount} workspace${p.assignmentCount == 1 ? '' : 's'}',
      headerActions: [
        IconButton(
          key: const Key('platform-sender-sync'),
          tooltip: 'Ask WhatsApp for the latest status',
          onPressed: _busy ? null : () => _run((api) => api.syncPlatformSender(s.id), 'Status refreshed'),
          icon: const Icon(Icons.refresh),
        ),
      ],
      body: DefaultTabController(
        length: 3,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          const TabBar(
            isScrollable: true,
            tabAlignment: TabAlignment.start,
            tabs: [Tab(text: 'Details'), Tab(text: 'Workspaces'), Tab(text: 'Unrouted')],
          ),
          Expanded(
            child: TabBarView(children: [
              _DetailsTab(sender: p, busy: _busy, onVerify: () => _verify(s)),
              PlatformSenderWorkspacesTab(sender: p),
              _UnroutedTab(senderId: s.id),
            ]),
          ),
        ]),
      ),
      footer: SenderLifecycleActions(
        sender: s,
        busy: _busy,
        canSubmit: true,
        platformAdmin: true,
        onEdit: () => _edit(p),
        onSubmit: () => _submit(s),
        onSuspend: () => _suspend(p),
        onResume: () => _run((api) => api.resumePlatformSender(s.id), 'Resumed ${s.phoneE164}'),
        onRevoke: () => _revoke(p),
        onTransfer: () => _transfer(p),
        onFinishRemoval: () => _run((api) => api.revokePlatformSender(s.id),
            'Removal finished — ${s.phoneE164} can be registered again'),
      ),
    );
  }
}

/// Platform-sender error codes in plain words.
String platformSenderErrorMessage(Object e) {
  final info = ApiErrorInfo.from(e);
  return switch (info.code) {
    'sender_has_assignments' => 'Workspaces are still assigned to this number. End every assignment first.',
    'sender_not_assignable' => 'Only a live or paused number can be assigned to a workspace.',
    'destination_not_active' => 'That workspace isn’t active.',
    'reserved_phone' => 'That number is reserved for the Playground simulator.',
    'platform_sender_requires_platform_account' => 'A platform sender must be on the platform’s WhatsApp account.',
    _ => info.message,
  };
}

class _HasAssignmentsDialog extends StatelessWidget {
  const _HasAssignmentsDialog({required this.action});

  /// `remove` or `move`.
  final String action;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      key: const Key('sender-has-assignments'),
      title: Text(action == 'move' ? 'End the assignments first' : 'Can’t remove a number in use'),
      content: SizedBox(
        width: 460,
        child: Text(
          'Workspaces are still assigned to this number. End each one on the «Workspaces» tab first — that '
          'closes out its routes, keywords, members and links on purpose — then ${action == 'move' ? 'move' : 'remove'} '
          'the number.',
          style: const TextStyle(fontSize: 14, height: 1.5),
        ),
      ),
      actions: [FilledButton(onPressed: () => Navigator.pop(context), child: const Text('OK'))],
    );
  }
}

class _DetailsTab extends StatelessWidget {
  const _DetailsTab({required this.sender, required this.busy, required this.onVerify});

  final PlatformSenderResponse sender;
  final bool busy;
  final VoidCallback onVerify;

  @override
  Widget build(BuildContext context) {
    final s = sender.sender;
    return ListView(
      padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
      children: [
        SenderStatusSummary(sender: s, busy: busy, platformAdmin: true, onVerify: onVerify),
        const ChannelSheetHeading('Details'),
        ChannelDetailRow(label: 'Phone', value: s.phoneE164, mono: true),
        ChannelDetailRow(label: 'Display name', value: s.displayName),
        if (s.approvedDisplayName != null) ChannelDetailRow(label: 'Approved display name', value: s.approvedDisplayName!),
        ChannelDetailRow(label: 'Business', value: s.businessProfile.legalName),
        ChannelDetailRow(label: 'Default locale', value: s.defaultLocale, mono: true),
        ChannelDetailRow(label: 'Workspaces', value: '${sender.assignmentCount}'),
        if (s.transferredFromTenant != null) ChannelDetailRow(label: 'Moved from', value: s.transferredFromTenant!, mono: true),
        if (s.createdAt != null) ChannelDetailRow(label: 'Created', value: formatRelativeIso(s.createdAt)),
        ChannelDetailRow(label: 'Sender ID', value: s.id, mono: true),
        if (s.status != 'REVOKED') UnroutedReplyEditor(sender: sender),
      ],
    );
  }
}

/// The generic reply for a phone no assigned workspace can claim (§8.3).
class UnroutedReplyEditor extends ConsumerStatefulWidget {
  const UnroutedReplyEditor({super.key, required this.sender});

  final PlatformSenderResponse sender;

  @override
  ConsumerState<UnroutedReplyEditor> createState() => _UnroutedReplyEditorState();
}

class _UnroutedReplyEditorState extends ConsumerState<UnroutedReplyEditor> {
  static const _max = 1000;
  late final _text = TextEditingController(text: widget.sender.unroutedReply ?? '');
  late String _saved = widget.sender.unroutedReply ?? '';
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final api = await ref.read(adminApiProvider.future);
      final value = _text.text.trim();
      final saved = await api.updatePlatformSender(
          widget.sender.id, PlatformSenderRequest.of(widget.sender.sender, unroutedReply: value));
      _saved = saved.unroutedReply ?? value;
      ref.invalidate(platformSendersProvider);
      if (mounted) showToast(context, 'Reply saved');
    } catch (e) {
      if (mounted) setState(() => _error = platformSenderErrorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final dirty = _text.text.trim() != _saved.trim();
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const ChannelSheetHeading('Reply when no business matches'),
      TextField(
        key: const Key('unrouted-reply'),
        controller: _text,
        enabled: !_busy,
        minLines: 2,
        maxLines: 4,
        inputFormatters: [LengthLimitingTextInputFormatter(_max)],
        onChanged: (_) => setState(() => _error = null),
        decoration: InputDecoration(
          hintText: 'e.g. Este número solo envía avisos de los negocios que lo usan. Responde STOP para no recibir más.',
          errorText: _error,
          helperText: 'Sent at most once a day to someone no assigned business can claim. Never name a business here.',
          helperMaxLines: 3,
        ),
      ),
      const SizedBox(height: 8),
      Align(
        alignment: Alignment.centerRight,
        child: FilledButton.tonal(
          key: const Key('unrouted-reply-save'),
          onPressed: _busy || !dirty ? null : _save,
          child: Text(_busy ? 'Saving…' : 'Save reply'),
        ),
      ),
    ]);
  }
}

/// «Workspaces»: the assignments with their per-workspace traffic, and the
/// assign / pause / resume / end actions.
class PlatformSenderWorkspacesTab extends ConsumerStatefulWidget {
  const PlatformSenderWorkspacesTab({super.key, required this.sender});

  final PlatformSenderResponse sender;

  @override
  ConsumerState<PlatformSenderWorkspacesTab> createState() => _PlatformSenderWorkspacesTabState();
}

class _PlatformSenderWorkspacesTabState extends ConsumerState<PlatformSenderWorkspacesTab> {
  String? _busyTenant;

  String get _senderId => widget.sender.id;

  void _refresh() {
    ref.invalidate(platformSenderAssignmentsProvider(_senderId));
    ref.invalidate(platformSendersProvider);
  }

  Future<void> _assign({PlatformSenderAssignment? existing, required List<PlatformSenderAssignment> current}) async {
    final saved = await showDialog<PlatformSenderAssignment>(
      context: context,
      builder: (_) => AssignWorkspaceDialog(
        senderId: _senderId,
        existing: existing,
        takenTenants: {for (final a in current) if (a.isLive) a.tenantId},
      ),
    );
    if (saved == null) return;
    _refresh();
    if (mounted) showToast(context, existing == null ? 'Assigned ${saved.tenantId} as “${saved.label}”' : 'Saved “${saved.label}”');
  }

  Future<void> _toggle(PlatformSenderAssignment a) async {
    final pause = a.status == 'ACTIVE';
    if (pause) {
      final ok = await confirmAction(
        context,
        title: 'Pause ${a.label}?',
        message: '${a.tenantId} can’t send on this number until you resume it. Its members, keywords and routes are '
            'kept, and the other workspaces are unaffected.',
        confirmLabel: 'Pause',
      );
      if (!ok) return;
    }
    setState(() => _busyTenant = a.tenantId);
    try {
      final api = await ref.read(adminApiProvider.future);
      if (pause) {
        await api.pausePlatformSenderAssignment(_senderId, a.tenantId);
      } else {
        await api.resumePlatformSenderAssignment(_senderId, a.tenantId);
      }
      _refresh();
      if (mounted) showToast(context, pause ? 'Paused ${a.label}' : 'Resumed ${a.label}');
    } catch (e) {
      if (mounted) showToast(context, platformSenderErrorMessage(e), error: true);
    } finally {
      if (mounted) setState(() => _busyTenant = null);
    }
  }

  Future<void> _end(PlatformSenderAssignment a) async {
    final ended = await showDialog<bool>(
      context: context,
      builder: (_) => EndAssignmentDialog(senderId: _senderId, assignment: a),
    );
    if (ended != true) return;
    _refresh();
    if (mounted) showToast(context, 'Ended ${a.label} on this number');
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final assignments = ref.watch(platformSenderAssignmentsProvider(_senderId));
    final rows = [...?assignments.valueOrNull]..sort((a, b) {
        final live = (b.isLive ? 1 : 0) - (a.isLive ? 1 : 0);
        return live != 0 ? live : a.label.toLowerCase().compareTo(b.label.toLowerCase());
      });
    final assignable = widget.sender.sender.status == 'ACTIVE' || widget.sender.sender.status == 'SUSPENDED';
    return ListView(
      padding: const EdgeInsets.fromLTRB(28, 16, 28, 28),
      children: [
        Row(children: [
          Expanded(
            child: Text(
              'Each workspace sends under its own business name. The cap counts its template sends per rolling 24 h.',
              style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant),
            ),
          ),
          const SizedBox(width: 12),
          Tooltip(
            message: assignable ? '' : 'Only a live or paused number can be assigned',
            child: FilledButton.icon(
              key: const Key('assign-workspace'),
              onPressed: assignable && assignments.hasValue ? () => _assign(current: rows) : null,
              icon: const Icon(Icons.add, size: 18),
              label: const Text('Assign workspace…'),
            ),
          ),
        ]),
        const SizedBox(height: Gap.md),
        if (assignments.hasError && !assignments.hasValue)
          YBanner(
            tone: YBannerTone.error,
            title: 'Couldn’t load the workspaces.',
            message: ApiErrorInfo.from(assignments.error!).message,
            actions: [TextButton(onPressed: _refresh, child: const Text('Retry'))],
          )
        else if (!assignments.hasValue)
          const LinearProgressIndicator()
        else if (rows.isEmpty)
          const YEmptyState(
            icon: Icons.workspaces_outline,
            title: 'No workspaces yet',
            message: 'Assign a workspace to let it send on this number under its business name.',
          )
        else
          for (final a in rows)
            _AssignmentRow(
              assignment: a,
              busy: _busyTenant == a.tenantId,
              onEdit: () => _assign(existing: a, current: rows),
              onToggle: () => _toggle(a),
              onEnd: () => _end(a),
            ),
      ],
    );
  }
}

class _AssignmentRow extends StatelessWidget {
  const _AssignmentRow({
    required this.assignment,
    required this.busy,
    required this.onEdit,
    required this.onToggle,
    required this.onEnd,
  });

  final PlatformSenderAssignment assignment;
  final bool busy;
  final VoidCallback onEdit;
  final VoidCallback onToggle;
  final VoidCallback onEnd;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final a = assignment;
    Widget stat(String label, String value, {bool warn = false}) => Padding(
          padding: const EdgeInsets.only(right: 18),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
            Text(label, style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant)),
            Text(value,
                style: monoStyle(context,
                    size: 13.5, weight: FontWeight.w600, color: warn ? StatusColors.of(context).onWarningContainer : null)),
          ]),
        );
    return Container(
      key: ValueKey('assignment-${a.tenantId}'),
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.fromLTRB(14, 12, 8, 12),
      decoration: BoxDecoration(border: Border.all(color: scheme.outlineVariant), borderRadius: BorderRadius.circular(10)),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Wrap(spacing: 8, runSpacing: 4, crossAxisAlignment: WrapCrossAlignment.center, children: [
              Text(a.label, style: TextStyle(fontSize: 14.5, fontWeight: FontWeight.w600, color: scheme.onSurface)),
              Text(a.tenantId, style: monoStyle(context, size: 12.5, color: scheme.onSurfaceVariant)),
              YBadge(humanizeWire(a.status), tone: toneForStatus(a.status), icon: iconForStatus(a.status)),
            ]),
            const SizedBox(height: 8),
            Wrap(runSpacing: 6, children: [
              stat('Cap / 24 h', '${a.dailyOutboundCap}'),
              stat('Template sends, 24 h', '${a.stats.templateSends24h}', warn: a.atCap),
              stat('Opt-outs, 7 d', '${a.stats.optOuts7d}', warn: a.stats.optOuts7d > 0),
              stat('Members', '${a.stats.members}'),
            ]),
            if (a.endedAt != null)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text('Ended ${formatRelativeIso(a.endedAt)}', style: TextStyle(fontSize: 12.5, color: scheme.onSurfaceVariant)),
              ),
          ]),
        ),
        if (a.isLive)
          busy
              ? const Padding(
                  padding: EdgeInsets.all(12),
                  child: SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2)),
                )
              : Wrap(children: [
                  IconButton(tooltip: 'Edit label or cap', icon: const Icon(Icons.edit_outlined, size: 18), onPressed: onEdit),
                  IconButton(
                    tooltip: a.status == 'ACTIVE' ? 'Pause' : 'Resume',
                    icon: Icon(a.status == 'ACTIVE' ? Icons.pause_circle_outline : Icons.play_circle_outline, size: 18),
                    onPressed: onToggle,
                  ),
                  TextButton(
                    key: ValueKey('end-${a.tenantId}'),
                    onPressed: onEnd,
                    style: TextButton.styleFrom(foregroundColor: scheme.error),
                    child: const Text('End…'),
                  ),
                ]),
      ]),
    );
  }
}

const _assignLabels = {'tenant': 'Workspace', 'label': 'Business name', 'cap': 'Daily template cap'};

/// Assign a workspace to the sender, or edit an assignment's label and cap.
/// Pops the saved assignment.
class AssignWorkspaceDialog extends ConsumerStatefulWidget {
  const AssignWorkspaceDialog({super.key, required this.senderId, this.existing, this.takenTenants = const {}});

  final String senderId;
  final PlatformSenderAssignment? existing;

  /// Workspaces already assigned (live) — not offered again.
  final Set<String> takenTenants;

  @override
  ConsumerState<AssignWorkspaceDialog> createState() => _AssignWorkspaceDialogState();
}

class _AssignWorkspaceDialogState extends ConsumerState<AssignWorkspaceDialog> {
  static const _maxLabel = 60;
  late String? _tenant = widget.existing?.tenantId;
  late final _label = TextEditingController(text: widget.existing?.label ?? '');
  late final _cap = TextEditingController(text: '${widget.existing?.dailyOutboundCap ?? 1000}');
  late final YFormController _form = YFormController(
    snapshot: () => {'tenant': _tenant, 'label': _label.text.trim(), 'cap': _cap.text.trim()},
    validate: () {
      final label = _label.text.trim();
      return {
        'tenant': _tenant == null ? 'Choose the workspace.' : null,
        'label': label.isEmpty
            ? 'Enter the business name recipients will read.'
            : label.length > _maxLabel
                ? 'Keep it to $_maxLabel characters.'
                : null,
        'cap': Validators.intInRange(_cap.text, 1, 1000000, optional: false),
      };
    },
    labels: _assignLabels,
  )..addListener(_rebuild);

  bool get _editing => widget.existing != null;

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    _label.dispose();
    _cap.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final saved = await api.putPlatformSenderAssignment(
        widget.senderId,
        _tenant!,
        PlatformSenderAssignmentRequest(label: _label.text.trim(), dailyOutboundCap: int.parse(_cap.text.trim())),
      );
      _form.saving = false;
      if (mounted) Navigator.pop(context, saved);
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(assignmentFieldError(e));
    }
  }

  @override
  Widget build(BuildContext context) {
    final tenants = ref.watch(tenantsProvider);
    final options = <TenantResponse>[
      ...?tenants.valueOrNull?.where(
          (t) => t.status == 'ACTIVE' && (!widget.takenTenants.contains(t.id) || t.id == widget.existing?.tenantId)),
    ]..sort((a, b) => a.displayName.compareTo(b.displayName));
    return AlertDialog(
      title: Text(_editing ? 'Edit ${widget.existing!.label}' : 'Assign a workspace'),
      content: SizedBox(
        width: 520,
        child: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            if (_form.formError != null) ...[
              YBanner(tone: YBannerTone.error, title: 'Couldn’t save.', message: _form.formError!),
              const SizedBox(height: Gap.md),
            ],
            if (_editing)
              ChannelDetailRow(label: 'Workspace', value: widget.existing!.tenantId, mono: true)
            else
              YSelectField<String>(
                key: const Key('assign-tenant'),
                label: 'Workspace',
                wireKey: 'tenantId',
                form: _form,
                name: 'tenant',
                required: true,
                value: _tenant,
                hint: tenants.isLoading ? 'Loading workspaces…' : 'Choose a workspace',
                helper: 'Only active workspaces not already on this number.',
                options: [for (final t in options) YOption(t.id, '${t.displayName} (${t.id})')],
                onChanged: (v) {
                  setState(() => _tenant = v);
                  _form.changed('tenant');
                },
              ),
            const SizedBox(height: Gap.fieldGap),
            YTextField(
              key: const Key('assign-label'),
              label: 'Business name',
              wireKey: 'label',
              controller: _label,
              form: _form,
              name: 'label',
              required: true,
              inputFormatters: [LengthLimitingTextInputFormatter(_maxLabel)],
              hint: 'e.g. Banca La Suerte',
              helper: 'What recipients read on every message this workspace sends here. Unique on the number; only '
                  'platform admins set it.',
              onChanged: (_) => _form.changed('label'),
            ),
            const SizedBox(height: Gap.fieldGap),
            YTextField(
              key: const Key('assign-cap'),
              label: 'Daily template cap',
              wireKey: 'dailyOutboundCap',
              controller: _cap,
              form: _form,
              name: 'cap',
              required: true,
              mono: true,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              hint: 'e.g. 1000',
              helper: 'Business-initiated (template) sends per rolling 24 h. Above it, sends fall back. Replies inside a '
                  'conversation don’t count.',
              onChanged: (_) => _form.changed('cap'),
            ),
          ]),
        ),
      ),
      actions: [
        OutlinedButton(onPressed: _form.saving ? null : () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          key: const Key('assign-save'),
          onPressed: _form.saving ? null : _save,
          child: Text(_editing ? 'Save' : 'Assign'),
        ),
      ],
    );
  }
}

/// Puts the assignment error codes next to the fields they are about.
ApiErrorInfo assignmentFieldError(Object e) {
  final info = ApiErrorInfo.from(e);
  if (info.fieldErrors.isNotEmpty) return info;
  final field = switch (info.code) {
    'label_taken' => ('label', 'Another workspace on this number already uses this name. Pick a different one.'),
    'destination_not_active' => ('tenant', 'That workspace isn’t active.'),
    _ => null,
  };
  if (field == null) {
    return ApiErrorInfo(message: platformSenderErrorMessage(e), code: info.code, status: info.status, path: info.path);
  }
  return ApiErrorInfo(
    message: info.message,
    code: info.code,
    status: info.status,
    path: info.path,
    fieldErrors: {field.$1: field.$2},
  );
}

/// Ending an assignment: previews what it closes out (dry run), then ends
/// it. Pops `true` once ended.
class EndAssignmentDialog extends ConsumerStatefulWidget {
  const EndAssignmentDialog({super.key, required this.senderId, required this.assignment});

  final String senderId;
  final PlatformSenderAssignment assignment;

  @override
  ConsumerState<EndAssignmentDialog> createState() => _EndAssignmentDialogState();
}

class _EndAssignmentDialogState extends ConsumerState<EndAssignmentDialog> {
  AssignmentEndEffects? _effects;
  bool _busy = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _preview();
  }

  Future<void> _preview() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final api = await ref.read(adminApiProvider.future);
      final plan = await api.endPlatformSenderAssignment(widget.senderId, widget.assignment.tenantId, dryRun: true);
      if (mounted) setState(() => _effects = plan.effects);
    } catch (e) {
      if (mounted) setState(() => _error = platformSenderErrorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _confirm() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.endPlatformSenderAssignment(widget.senderId, widget.assignment.tenantId);
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) setState(() => _error = platformSenderErrorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final a = widget.assignment;
    final effects = _effects;
    return AlertDialog(
      title: Text('End ${a.label} on this number?'),
      content: SizedBox(
        width: 520,
        child: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(
              '${a.tenantId} stops sending and receiving on this number. Its integrations see “no WhatsApp number” on '
              'their next send.',
              style: TextStyle(fontSize: 14, height: 1.5, color: scheme.onSurface),
            ),
            const SizedBox(height: Gap.md),
            if (effects == null && _busy) const LinearProgressIndicator(key: Key('end-preview-loading')),
            if (effects != null)
              Container(
                key: const Key('end-effects'),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(color: scheme.surfaceContainerHigh, borderRadius: BorderRadius.circular(10)),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('What happens',
                      style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
                  const SizedBox(height: 6),
                  for (final line in effects.describe())
                    Padding(
                      padding: const EdgeInsets.only(bottom: 4),
                      child: Text('• $line', style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurface)),
                    ),
                ]),
              ),
            if (_error != null) ...[
              const SizedBox(height: Gap.md),
              YBanner(
                tone: YBannerTone.error,
                title: effects == null ? 'Couldn’t preview ending it.' : 'Couldn’t end it.',
                message: _error!,
                actions: [
                  if (effects == null) TextButton(onPressed: _busy ? null : _preview, child: const Text('Retry')),
                ],
              ),
            ],
          ]),
        ),
      ),
      actions: [
        OutlinedButton(onPressed: _busy && effects != null ? null : () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          key: const Key('end-confirm'),
          style: FilledButton.styleFrom(backgroundColor: scheme.error, foregroundColor: scheme.onError),
          onPressed: _busy || effects == null ? null : _confirm,
          child: const Text('End assignment'),
        ),
      ],
    );
  }
}

class _UnroutedTab extends ConsumerWidget {
  const _UnroutedTab({required this.senderId});

  final String senderId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final rows = ref.watch(platformSenderUnroutedProvider(senderId));
    return ListView(
      padding: const EdgeInsets.fromLTRB(28, 16, 28, 28),
      children: [
        Row(children: [
          Expanded(
            child: Text(
              'Messages that matched no workspace — they got the generic reply or a «which business?» question.',
              style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant),
            ),
          ),
          IconButton(
            tooltip: 'Refresh',
            onPressed: () => ref.invalidate(platformSenderUnroutedProvider(senderId)),
            icon: const Icon(Icons.refresh, size: 18),
          ),
        ]),
        const SizedBox(height: Gap.sm),
        if (rows.hasError && !rows.hasValue)
          YBanner(
            tone: YBannerTone.error,
            title: 'Couldn’t load unrouted messages.',
            message: ApiErrorInfo.from(rows.error!).message,
          )
        else if (!rows.hasValue)
          const LinearProgressIndicator()
        else if (rows.value!.isEmpty)
          const YEmptyState(
            icon: Icons.inbox_outlined,
            title: 'Nothing unrouted',
            message: 'Every recent message on this number reached a workspace.',
          )
        else
          for (final m in rows.value!)
            Container(
              key: ValueKey('unrouted-${m.messageSid}'),
              margin: const EdgeInsets.only(bottom: 6),
              padding: const EdgeInsets.all(12),
              decoration:
                  BoxDecoration(border: Border.all(color: scheme.outlineVariant), borderRadius: BorderRadius.circular(10)),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Wrap(spacing: 8, runSpacing: 4, crossAxisAlignment: WrapCrossAlignment.center, children: [
                  Text(m.phoneMasked, style: monoStyle(context, size: 13, weight: FontWeight.w600)),
                  if (m.reason != null) YBadge(humanizeWire(m.reason!.toUpperCase()), mono: false),
                  Text(formatRelativeIso(m.receivedAt), style: TextStyle(fontSize: 12.5, color: scheme.onSurfaceVariant)),
                ]),
                if (m.body != null && m.body!.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: SelectableText(m.body!, style: TextStyle(fontSize: 14, height: 1.45, color: scheme.onSurface)),
                  ),
              ]),
            ),
      ],
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/whatsapp_embedded_signup.dart';
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
import '../../auth/application/auth_controller.dart';
import '../../auth/models/auth_state.dart';
import '../channel_bindings/channel_ui.dart';
import '../channel_bindings/sender_routes_section.dart';
import '../shared/admin_shared.dart' show TenantScopedEmptyState;
import 'whatsapp_connect_wizard.dart';
import 'whatsapp_sender_transfer_dialog.dart';
import 'whatsapp_sender_wizard.dart';

/// Registration and moves settle outside the admin API (WhatsApp review,
/// webhook re-pointing), so the list re-asks while any sender is in flight.
bool senderIsSettling(WhatsAppSenderResponse s) =>
    s.status == 'SUBMITTING' || s.status == 'PENDING_REVIEW' || s.transferStillForwarding;

final whatsAppSendersProvider =
    FutureProvider.family<List<WhatsAppSenderResponse>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.whatsappSender);
  final api = await ref.watch(adminApiProvider.future);
  final list = await api.listWhatsAppSenders(tenantId);
  // Submitting and re-pointing settle in seconds; WhatsApp review can take days.
  final quick = list.any((s) => s.status == 'SUBMITTING' || s.transferStillForwarding);
  pollWhile(ref, list.any(senderIsSettling), every: Duration(seconds: quick ? 3 : 10));
  return list;
});

/// Whether sender registration can hit the upstream provisioning service.
/// When false, the page says WhatsApp provisioning isn't enabled and Submit
/// is unavailable — drafts can still be edited and removed.
final whatsAppSubmitEnabledProvider = FutureProvider.family<bool, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.isWhatsAppSubmitEnabled(tenantId);
});

/// Meta Embedded Signup availability + browser-safe ids. Gates the
/// self-service "Connect WhatsApp" wizard.
final whatsAppEmbeddedSignupConfigProvider =
    FutureProvider.family<EmbeddedSignupConfig, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.getWhatsAppEmbeddedSignupConfig(tenantId);
});

/// The badge word for a sender — "Code needed" outranks the lifecycle status
/// because it is the one thing blocking go-live that only the operator can do.
String senderBadgeLabel(WhatsAppSenderResponse s) =>
    s.needsVerificationCode ? 'Code needed' : SenderStatusInfo.of(s.status).label;

YTone senderTone(WhatsAppSenderResponse s) =>
    s.needsVerificationCode ? YTone.warning : toneForStatus(s.status);

/// Tenants register WhatsApp senders here. Twilio is never named — the
/// operator describes a business and a phone number; the platform does the rest.
class WhatsAppSendersScreen extends ConsumerStatefulWidget {
  const WhatsAppSendersScreen({super.key});

  @override
  ConsumerState<WhatsAppSendersScreen> createState() => _WhatsAppSendersScreenState();
}

class _WhatsAppSendersScreenState extends ConsumerState<WhatsAppSendersScreen> {
  String? _handledLink;
  String? _openId;

  void _newSender() {
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => const WhatsAppSenderWizard()));
  }

  void _connectWhatsApp(EmbeddedSignupConfig config) {
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => WhatsAppConnectWizard(config: config)));
  }

  Future<void> _open(String tenant, WhatsAppSenderResponse sender) async {
    setState(() => _openId = sender.id);
    await showYSheet<void>(
      context,
      label: 'WhatsApp sender',
      width: 680,
      builder: (_) => WhatsAppSenderSheet(tenant: tenant, senderId: sender.id),
    );
    if (mounted) setState(() => _openId = null);
  }

  void _handleDeepLink(String tenant, List<WhatsAppSenderResponse> list) {
    final params = routeQuery(context);
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      if (link.isEmpty) {
        await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const WhatsAppSenderWizard()));
      } else {
        final match = list.where((s) => s.id == link || s.phoneE164 == link).firstOrNull;
        if (match == null) {
          showToast(context, 'No WhatsApp sender “$link” in this workspace', error: true);
        } else {
          await _open(tenant, match);
        }
      }
      // Cleared after the editor closes, as the capabilities page does.
      if (mounted) clearRouteQuery(context, '/admin/whatsapp-senders');
      _handledLink = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'WhatsApp senders');
    }
    final senders = ref.watch(whatsAppSendersProvider(tenant));
    final canSubmit = ref.watch(whatsAppSubmitEnabledProvider(tenant)).maybeWhen(
          data: (ready) => ready,
          orElse: () => true, // optimistic until the probe lands
        );
    final signup = ref
        .watch(whatsAppEmbeddedSignupConfigProvider(tenant))
        .maybeWhen(data: (c) => c, orElse: EmbeddedSignupConfig.disabled);
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.whatsappSender));
    senders.whenData((list) => _handleDeepLink(tenant, list));

    final register = FilledButton.icon(
      onPressed: _newSender,
      icon: const Icon(Icons.add, size: 18),
      label: const Text('Register a sender'),
    );

    return YPage(
      title: 'WhatsApp senders',
      subtitle: 'Phone numbers your customers message. Each sender belongs to one workspace, and its routes '
          'decide which assistant answers.',
      actions: [
        if (signup.enabled)
          Tooltip(
            message: 'Connect your own WhatsApp Business number through the Meta popup',
            child: OutlinedButton.icon(
              onPressed: () => _connectWhatsApp(signup),
              icon: const Icon(Icons.link, size: 18),
              label: const Text('Connect WhatsApp'),
            ),
          ),
        register,
      ],
      toolbar: canSubmit
          ? null
          : const YBanner(
              tone: YBannerTone.warning,
              title: 'WhatsApp provisioning is unavailable.',
              message: 'You can still draft, edit and remove senders in this workspace. Submitting for review '
                  'stays off until your platform administrator finishes setup.',
            ),
      child: LiveAsyncView<List<WhatsAppSenderResponse>>(
        value: senders,
        what: 'WhatsApp senders',
        onRetry: () => ref.invalidate(whatsAppSendersProvider(tenant)),
        data: (context, list) => YTable<WhatsAppSenderResponse>(
          rows: list,
          noun: 'senders',
          idOf: (s) => s.id,
          selectedId: _openId,
          highlightIds: fresh,
          searchHint: 'Search by name, number or business',
          searchTextOf: (s) =>
              '${s.displayName} ${s.phoneE164} ${s.businessProfile.legalName} ${SenderStatusInfo.of(s.status).label}',
          onRowTap: (s) => _open(tenant, s),
          filters: [
            YFilter('Needs action', _needsAction),
            YFilter('In review', (s) => s.status == 'SUBMITTING' || s.status == 'PENDING_REVIEW'),
            YFilter('Live', (s) => s.status == 'ACTIVE'),
            YFilter('Drafts', (s) => s.status == 'DRAFT'),
          ],
          empty: YEmptyState(
            icon: Icons.chat_outlined,
            title: 'No WhatsApp senders yet',
            message: 'A sender is a phone number your customers message on WhatsApp. WhatsApp reviews the business '
                'and display name before it goes live — usually within 1–3 business days.',
            action: Wrap(spacing: 8, runSpacing: 8, alignment: WrapAlignment.center, children: [
              register,
              if (signup.enabled)
                OutlinedButton.icon(
                  onPressed: () => _connectWhatsApp(signup),
                  icon: const Icon(Icons.link, size: 18),
                  label: const Text('Connect WhatsApp'),
                ),
            ]),
          ),
          columns: [
            YColumn(
              label: 'Sender',
              flex: 3,
              sortKey: (s) => s.displayName.toLowerCase(),
              cell: (context, s) => Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
                Text(s.displayName,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: Theme.of(context).colorScheme.onSurface)),
                Text(s.phoneE164, style: monoStyle(context, size: 12.5, color: Theme.of(context).colorScheme.onSurfaceVariant)),
              ]),
            ),
            YColumn(
              label: 'Status',
              flex: 4,
              sortKey: (s) => '${_needsAction(s) ? 0 : 1}${s.status}',
              cell: (context, s) => _StatusCell(sender: s),
            ),
            YColumn(
              label: 'Business',
              flex: 3,
              sortKey: (s) => s.businessProfile.legalName.toLowerCase(),
              cell: (context, s) => YCellStack(
                primary: s.businessProfile.legalName,
                secondary: s.businessProfile.vertical.isEmpty ? null : businessVerticalLabel(s.businessProfile.vertical),
              ),
            ),
            YColumn(
              label: 'Locale',
              width: 90,
              sortKey: (s) => s.defaultLocale,
              cell: (context, s) => Text(s.defaultLocale, style: monoStyle(context, size: 12.5)),
            ),
            YColumn(
              label: 'Updated',
              width: 120,
              sortKey: (s) => s.updatedAt ?? s.createdAt ?? '',
              cell: (context, s) => Text(fresh.contains(s.id) ? 'Just now' : formatRelativeIso(s.updatedAt ?? s.createdAt)),
            ),
          ],
        ),
      ),
    );
  }
}

bool _needsAction(WhatsAppSenderResponse s) =>
    s.needsVerificationCode ||
    s.canEnterVerificationCode ||
    s.status == 'FAILED' ||
    (s.status == 'REVOKED' && s.removalIncomplete) ||
    s.transferStillForwarding;

/// Badge plus the one line that tells the operator what to do next — visible
/// in the list so nobody has to open a sender to find out it is blocked.
class _StatusCell extends StatelessWidget {
  const _StatusCell({required this.sender});
  final WhatsAppSenderResponse sender;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    final s = sender;
    final rejection = s.status == 'FAILED' ? s.onboarding.failureMessage : null;
    final (String? line, Color? color) = s.needsVerificationCode
        ? ('Action needed — enter the code WhatsApp sent to the phone', status.onWarningContainer)
        : s.canEnterVerificationCode
            ? ('Got a code from WhatsApp? Open to enter it', scheme.onSurfaceVariant)
            : rejection != null
                ? ('Rejected: $rejection', scheme.error)
                : s.status == 'REVOKED' && s.removalIncomplete
                    ? ('Removal needs a retry', scheme.error)
                    : s.transferStillForwarding
                        ? ('Move in progress', scheme.onSurfaceVariant)
                        : (null, null);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
      YBadge(senderBadgeLabel(s), tone: senderTone(s), icon: iconForStatus(s.needsVerificationCode ? 'PENDING_VERIFICATION' : s.status)),
      if (line != null)
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text(line,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: color)),
        ),
    ]);
  }
}

/// One sender: what state it's in, what WhatsApp said, the one action that
/// moves it forward, its routes. Reads the live list so a status change from
/// a WhatsApp callback shows up while it's open.
class WhatsAppSenderSheet extends ConsumerStatefulWidget {
  const WhatsAppSenderSheet({super.key, required this.tenant, required this.senderId});
  final String tenant;
  final String senderId;

  @override
  ConsumerState<WhatsAppSenderSheet> createState() => _WhatsAppSenderSheetState();
}

class _WhatsAppSenderSheetState extends ConsumerState<WhatsAppSenderSheet> {
  /// The BSP call behind a transition can take seconds; the buttons say so.
  bool _busy = false;

  String get _tenant => widget.tenant;

  Future<void> _run(Future<WhatsAppSenderResponse> Function(AdminApi api) call, String successCopy) async {
    setState(() => _busy = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await call(api);
      ref.invalidate(whatsAppSendersProvider(_tenant));
      if (mounted) showToast(context, successCopy);
    } catch (e) {
      if (mounted) showToast(context, ApiErrorInfo.from(e).message, error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _edit(WhatsAppSenderResponse s) {
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => WhatsAppSenderWizard(existing: s)));
  }

  Future<void> _submit(WhatsAppSenderResponse s) async {
    final method = await showDialog<String>(context: context, builder: (_) => _SubmitDialog(phoneE164: s.phoneE164));
    if (method == null) return;
    await _run((api) => api.submitWhatsAppSender(_tenant, s.id, verificationMethod: method),
        'Submitted ${s.phoneE164}. If WhatsApp sends a code to the phone, enter it here.');
  }

  Future<void> _verify(WhatsAppSenderResponse s) async {
    final code = await showDialog<String>(context: context, builder: (_) => _VerificationCodeDialog(phoneE164: s.phoneE164));
    if (code == null) return;
    await _run((api) => api.verifyWhatsAppSender(_tenant, s.id, code),
        'Code accepted — WhatsApp is finishing the registration.');
  }

  Future<void> _suspend(WhatsAppSenderResponse s) async {
    final ok = await confirmAction(
      context,
      title: 'Pause ${s.phoneE164}?',
      message: 'Messages to “${s.displayName}” are still received but nobody answers until you resume it. '
          'Nothing is deleted.',
      confirmLabel: 'Pause sender',
    );
    if (!ok) return;
    await _run((api) => api.suspendWhatsAppSender(_tenant, s.id), 'Paused ${s.phoneE164}');
  }

  Future<void> _revoke(WhatsAppSenderResponse s) async {
    final inFlight = s.status == 'SUBMITTING' || s.status == 'PENDING_REVIEW';
    final live = s.status == 'ACTIVE' || s.status == 'SUSPENDED';
    final bool ok;
    if (live) {
      // A live number going dark is the high-blast-radius case: customers
      // writing to it get no answer from the moment this runs.
      ok = await confirmTyped(
        context,
        title: 'Remove ${s.phoneE164}?',
        message: '“${s.displayName}” stops sending and receiving immediately — customers who write to it get no '
            'reply. Conversation history is kept, and the number can be registered again later.',
        expected: s.phoneE164,
        confirmLabel: 'Remove sender',
      );
    } else {
      ok = await confirmAction(
        context,
        title: inFlight ? 'Cancel the registration of ${s.phoneE164}?' : 'Remove ${s.phoneE164}?',
        message: inFlight
            ? 'The registration is withdrawn from WhatsApp review. You can register the same number again '
                'afterwards — for example after fixing the business details.'
            : 'The ${s.status == 'DRAFT' ? 'draft' : 'sender'} is retired. The number can be registered again later.',
        confirmLabel: inFlight ? 'Cancel registration' : 'Remove sender',
        cancelLabel: inFlight ? 'Keep it' : 'Cancel',
        destructive: true,
      );
    }
    if (!ok) return;
    await _run((api) => api.revokeWhatsAppSender(_tenant, s.id),
        inFlight ? 'Registration of ${s.phoneE164} cancelled' : 'Removed ${s.phoneE164}');
  }

  Future<void> _transfer(WhatsAppSenderResponse s) async {
    final result = await showDialog<WhatsAppSenderTransferResponse>(
      context: context,
      builder: (_) => WhatsAppSenderTransferDialog(tenant: _tenant, sender: s),
    );
    if (result == null) return;
    final dest = result.destination;
    ref.invalidate(whatsAppSendersProvider(_tenant));
    if (dest != null) ref.invalidate(whatsAppSendersProvider(dest.tenantId));
    if (!mounted) return;
    showToast(
        context,
        result.webhookRepoint == 'DONE'
            ? 'Moved to ${dest?.tenantId}.'
            : 'Moved to ${dest?.tenantId}. WhatsApp is still being pointed at it — messages arrive there in the meantime.');
  }

  @override
  Widget build(BuildContext context) {
    final list = ref.watch(whatsAppSendersProvider(_tenant)).valueOrNull ?? const <WhatsAppSenderResponse>[];
    final sender = list.where((s) => s.id == widget.senderId).firstOrNull;
    if (sender == null) {
      return YSheetScaffold(
        title: 'WhatsApp sender',
        body: const YEmptyState(
          icon: Icons.search_off,
          title: 'This sender is gone',
          message: 'It was removed or moved to another workspace while this was open.',
        ),
      );
    }
    final auth = ref.watch(authProvider);
    final platformAdmin = auth is AuthAuthenticated && auth.operator.platformAdmin;
    final canSubmit = ref.watch(whatsAppSubmitEnabledProvider(_tenant)).maybeWhen(data: (r) => r, orElse: () => true);
    return YSheetScaffold(
      title: sender.displayName,
      subtitle: '${sender.phoneE164} · ${SenderStatusInfo.of(sender.status).label}',
      headerActions: [
        if (senderIsSettling(sender))
          IconButton(
            tooltip: 'Ask WhatsApp for the latest status',
            onPressed: _busy
                ? null
                : () => _run((api) => api.syncWhatsAppSender(_tenant, sender.id), 'Status refreshed'),
            icon: const Icon(Icons.refresh),
          ),
      ],
      body: _SenderDetails(sender: sender, busy: _busy, platformAdmin: platformAdmin, onVerify: () => _verify(sender)),
      footer: _SenderActions(
        sender: sender,
        busy: _busy,
        canSubmit: canSubmit,
        platformAdmin: platformAdmin,
        onEdit: () => _edit(sender),
        onSubmit: () => _submit(sender),
        onSuspend: () => _suspend(sender),
        onResume: () => _run((api) => api.resumeWhatsAppSender(_tenant, sender.id), 'Resumed ${sender.phoneE164}'),
        onRevoke: () => _revoke(sender),
        onTransfer: () => _transfer(sender),
        onRetryRepoint: () => _run((api) => api.repointWhatsAppSenderWebhooks(_tenant, sender.id),
            'WhatsApp now delivers to this workspace directly'),
        onFinishRemoval: () => _run((api) => api.revokeWhatsAppSender(_tenant, sender.id),
            'Removal finished — ${sender.phoneE164} can be registered again'),
      ),
    );
  }
}

class _SenderDetails extends StatelessWidget {
  const _SenderDetails({required this.sender, required this.busy, required this.platformAdmin, required this.onVerify});
  final WhatsAppSenderResponse sender;
  final bool busy;
  final bool platformAdmin;
  final VoidCallback onVerify;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final s = sender;
    final info = SenderStatusInfo.of(s.status);
    final inFlight = s.status == 'SUBMITTING' || s.status == 'PENDING_REVIEW';
    final progress = inFlight ? registrationProgressLabel(s.onboarding.status) : null;
    final rejection = s.status == 'FAILED' ? s.onboarding.failureMessage : null;
    // SUBMITTING failures are our own retries, not something WhatsApp said.
    final inFlightProblem = s.status == 'PENDING_REVIEW' ? s.onboarding.failureMessage : null;
    return ListView(
      padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
      children: [
        Wrap(spacing: 10, runSpacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
          YBadge(info.label, tone: toneForStatus(s.status), icon: iconForStatus(s.status)),
          if (progress != null)
            Text(progress, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: scheme.onSurface)),
        ]),
        if (info.description.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(info.description, style: TextStyle(fontSize: 14, height: 1.5, color: scheme.onSurfaceVariant)),
          ),
        if (rejection != null)
          _ProblemNote(
            title: 'Why it was rejected',
            message: rejection,
            hint: 'Use “Fix & resubmit” to correct it and send it again.',
          ),
        if (inFlightProblem != null)
          _ProblemNote(
            title: 'WhatsApp reported a problem',
            message: inFlightProblem,
            hint: 'If WhatsApp sent a code to the phone, enter it below. If no code arrived or it expired, cancel '
                'the registration and register the number again to get a new one.',
          ),
        if (s.transferStillForwarding)
          _ProblemNote(
            tone: YTone.warning,
            title: 'Still finishing the move',
            message: s.transferWebhooks == 'FAILED'
                ? 'WhatsApp could not be pointed at this workspace yet.'
                : 'WhatsApp is being pointed at this workspace.',
            hint: 'Messages to this number reach this workspace in the meantime'
                '${platformAdmin ? '. Use “Retry move” to try again now.' : '.'}',
          ),
        if (s.status == 'REVOKED' && s.removalIncomplete)
          const _ProblemNote(
            title: 'Removal not finished',
            message: 'WhatsApp could not be reached to remove this number.',
            hint: 'Until it is, the number can’t be registered in another workspace. Use “Finish removal” to try again.',
          ),
        if (s.canEnterVerificationCode || s.needsVerificationCode)
          _VerificationPrompt(phoneE164: s.phoneE164, confirmed: s.needsVerificationCode, busy: busy, onVerify: onVerify),
        const ChannelSheetHeading('Details'),
        ChannelDetailRow(label: 'Phone', value: s.phoneE164, mono: true),
        ChannelDetailRow(label: 'Display name', value: s.displayName),
        if (s.approvedDisplayName != null) ChannelDetailRow(label: 'Approved display name', value: s.approvedDisplayName!),
        ChannelDetailRow(
          label: 'Business',
          value: s.businessProfile.vertical.isEmpty
              ? s.businessProfile.legalName
              : '${s.businessProfile.legalName} (${businessVerticalLabel(s.businessProfile.vertical)})',
        ),
        ChannelDetailRow(label: 'Default locale', value: s.defaultLocale, mono: true),
        if (s.onboarding.lastPolledAt != null)
          ChannelDetailRow(label: 'Last checked', value: formatRelativeIso(s.onboarding.lastPolledAt)),
        if (s.onboarding.failureMessage != null && rejection == null && inFlightProblem == null)
          ChannelDetailRow(label: 'Review notes', value: s.onboarding.failureMessage!),
        if (s.transferredFromTenant != null) ChannelDetailRow(label: 'Moved from', value: s.transferredFromTenant!, mono: true),
        if (s.createdAt != null) ChannelDetailRow(label: 'Created', value: formatRelativeIso(s.createdAt)),
        ChannelDetailRow(label: 'Sender ID', value: s.id, mono: true),
        if (s.status != 'REVOKED') ...[
          const SizedBox(height: Gap.sm),
          SenderRoutesSection(endpointKind: 'WHATSAPP_SENDER', endpointId: s.id),
        ],
      ],
    );
  }
}

class _SenderActions extends StatelessWidget {
  const _SenderActions({
    required this.sender,
    required this.busy,
    required this.canSubmit,
    required this.platformAdmin,
    required this.onEdit,
    required this.onSubmit,
    required this.onSuspend,
    required this.onResume,
    required this.onRevoke,
    required this.onTransfer,
    required this.onRetryRepoint,
    required this.onFinishRemoval,
  });

  final WhatsAppSenderResponse sender;
  final bool busy;
  final bool canSubmit;
  final bool platformAdmin;
  final VoidCallback onEdit;
  final VoidCallback onSubmit;
  final VoidCallback onSuspend;
  final VoidCallback onResume;
  final VoidCallback onRevoke;
  final VoidCallback onTransfer;
  final VoidCallback onRetryRepoint;
  final VoidCallback onFinishRemoval;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final s = sender;
    final inFlight = s.status == 'SUBMITTING' || s.status == 'PENDING_REVIEW';
    Widget icon(IconData i) => busy
        ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
        : Icon(i, size: 18);
    final actions = <Widget>[
      if (s.status == 'REVOKED' && s.removalIncomplete)
        FilledButton.tonalIcon(onPressed: busy ? null : onFinishRemoval, icon: icon(Icons.delete_sweep_outlined), label: const Text('Finish removal')),
      if (platformAdmin && s.transferStillForwarding)
        OutlinedButton.icon(onPressed: busy ? null : onRetryRepoint, icon: icon(Icons.sync), label: const Text('Retry move')),
      if (platformAdmin && s.canTransfer)
        OutlinedButton.icon(
          key: const Key('sender-transfer'),
          onPressed: busy ? null : onTransfer,
          icon: icon(Icons.drive_file_move_outline),
          label: const Text('Move to workspace…'),
        ),
      if (s.status != 'REVOKED')
        TextButton.icon(
          onPressed: busy ? null : onRevoke,
          icon: icon(Icons.delete_outline),
          label: Text(inFlight ? 'Cancel registration' : 'Remove'),
          style: TextButton.styleFrom(foregroundColor: scheme.error),
        ),
      if (s.status == 'ACTIVE')
        OutlinedButton.icon(onPressed: busy ? null : onSuspend, icon: icon(Icons.pause_circle_outline), label: const Text('Pause')),
      if (s.status == 'DRAFT') ...[
        OutlinedButton.icon(onPressed: busy ? null : onEdit, icon: const Icon(Icons.edit_outlined, size: 18), label: const Text('Edit')),
        Tooltip(
          message: canSubmit ? 'Send to WhatsApp for review' : 'WhatsApp provisioning isn’t set up on this instance',
          child: FilledButton.icon(
            onPressed: (busy || !canSubmit) ? null : onSubmit,
            icon: icon(Icons.send),
            label: Text(canSubmit ? 'Submit for review' : 'Submit unavailable'),
          ),
        ),
      ],
      if (s.status == 'FAILED')
        FilledButton.icon(onPressed: busy ? null : onEdit, icon: icon(Icons.edit_outlined), label: const Text('Fix & resubmit')),
      if (s.status == 'SUSPENDED')
        FilledButton.icon(onPressed: busy ? null : onResume, icon: icon(Icons.play_circle_outline), label: const Text('Resume')),
    ];
    if (actions.isEmpty) return const SizedBox.shrink();
    return Container(
      decoration: BoxDecoration(color: scheme.surface, border: Border(top: BorderSide(color: scheme.outlineVariant))),
      padding: const EdgeInsets.symmetric(horizontal: Gap.pagePadding, vertical: 12),
      child: Wrap(alignment: WrapAlignment.end, spacing: 8, runSpacing: 8, children: actions),
    );
  }
}

/// What WhatsApp reported going wrong, shown where the operator decides what
/// to do next.
class _ProblemNote extends StatelessWidget {
  const _ProblemNote({required this.title, required this.message, required this.hint, this.tone = YTone.danger});

  final String title;
  final String message;
  final String hint;
  final YTone tone;

  @override
  Widget build(BuildContext context) {
    final (bg, fg) = toneColors(context, tone);
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(top: Gap.md),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(10), border: Border.all(color: fg.withValues(alpha: 0.25))),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(tone == YTone.danger ? Icons.error_outline : Icons.warning_amber_rounded, color: fg, size: 20),
        const SizedBox(width: 10),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: TextStyle(fontWeight: FontWeight.w600, fontSize: 14, color: fg)),
            const SizedBox(height: 2),
            SelectableText(message, style: TextStyle(fontSize: 14, height: 1.45, color: fg)),
            const SizedBox(height: 4),
            Text(hint, style: TextStyle(fontSize: 13, height: 1.45, color: fg)),
          ]),
        ),
      ]),
    );
  }
}

/// How WhatsApp delivers the one-time ownership code. Shared by the wizard's
/// phone step and the submit dialog so both read the same.
class VerificationMethodSelector extends StatelessWidget {
  const VerificationMethodSelector({super.key, required this.value, required this.onChanged, this.form});

  final String value;
  final ValueChanged<String> onChanged;
  final YFormController? form;

  @override
  Widget build(BuildContext context) {
    return YEnumCards<String>(
      label: 'Verification code by',
      wireKey: 'verificationMethod',
      form: form,
      name: form == null ? null : 'verificationMethod',
      horizontal: true,
      value: value,
      onChanged: onChanged,
      helper: 'WhatsApp may send a one-time code to this number to confirm you own it — keep the phone nearby '
          'after submitting.',
      options: const [
        YEnumOption('sms', 'SMS', wire: 'sms', description: 'A text message with the code.'),
        YEnumOption('voice', 'Voice call', wire: 'voice', description: 'An automated call reads the code. Use this for landlines.'),
      ],
    );
  }
}

class _SubmitDialog extends StatefulWidget {
  const _SubmitDialog({required this.phoneE164});
  final String phoneE164;

  @override
  State<_SubmitDialog> createState() => _SubmitDialogState();
}

class _SubmitDialogState extends State<_SubmitDialog> {
  String _method = 'sms';

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text('Submit ${widget.phoneE164} for WhatsApp review?'),
      content: SizedBox(
        width: 520,
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(
            'Once submitted, the sender can’t be edited until WhatsApp returns a verdict — from minutes to a few '
            'business days.',
            style: TextStyle(fontSize: 14, height: 1.5, color: scheme.onSurface),
          ),
          const SizedBox(height: Gap.lg),
          VerificationMethodSelector(value: _method, onChanged: (v) => setState(() => _method = v)),
        ]),
      ),
      actions: [
        OutlinedButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: () => Navigator.pop(context, _method), child: const Text('Submit')),
      ],
    );
  }
}

/// Call-to-action while WhatsApp waits for the code.
class _VerificationPrompt extends StatelessWidget {
  const _VerificationPrompt({required this.phoneE164, required this.confirmed, required this.busy, required this.onVerify});

  final String phoneE164;

  /// WhatsApp reported it is waiting for the code, rather than it maybe being on its way.
  final bool confirmed;
  final bool busy;
  final VoidCallback onVerify;

  @override
  Widget build(BuildContext context) {
    final (bg, fg) = toneColors(context, confirmed ? YTone.warning : YTone.info);
    return Container(
      margin: const EdgeInsets.only(top: Gap.md),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(10), border: Border.all(color: fg.withValues(alpha: 0.25))),
      child: Row(children: [
        Icon(Icons.password_outlined, color: fg),
        const SizedBox(width: 12),
        Expanded(
          child: Text(
            confirmed
                ? 'WhatsApp sent a verification code to $phoneE164 by SMS or voice call. Enter it to finish '
                    'registering the number.'
                : 'If WhatsApp sent a code to $phoneE164 by SMS or voice call, enter it here to finish registering '
                    'the number.',
            style: TextStyle(color: fg, fontSize: 14, height: 1.45),
          ),
        ),
        const SizedBox(width: 12),
        FilledButton(onPressed: busy ? null : onVerify, child: const Text('Enter code')),
      ]),
    );
  }
}

class _VerificationCodeDialog extends StatefulWidget {
  const _VerificationCodeDialog({required this.phoneE164});
  final String phoneE164;

  @override
  State<_VerificationCodeDialog> createState() => _VerificationCodeDialogState();
}

class _VerificationCodeDialogState extends State<_VerificationCodeDialog> {
  final _code = TextEditingController();
  late final YFormController _form = YFormController(
    snapshot: () => {'code': _code.text.trim()},
    validate: () {
      final v = _code.text.trim();
      if (v.isEmpty) return {'code': 'Enter the code WhatsApp sent.'};
      return {'code': RegExp(r'^[0-9]{4,8}$').hasMatch(v) ? null : 'Codes are 4 to 8 digits.'};
    },
    labels: const {'code': 'Verification code'},
  )..addListener(_rebuild);

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    _code.dispose();
    super.dispose();
  }

  void _submit() {
    if (_form.submit()) Navigator.pop(context, _code.text.trim());
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Enter verification code'),
      content: SizedBox(
        width: 400,
        child: YTextField(
          label: 'Verification code',
          wireKey: 'code',
          controller: _code,
          form: _form,
          name: 'code',
          required: true,
          autofocus: true,
          mono: true,
          hint: 'e.g. 123456',
          keyboardType: TextInputType.number,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(8)],
          helper: 'The code WhatsApp sent to ${widget.phoneE164}. Codes expire after a few minutes.',
          onSubmitted: (_) => _submit(),
        ),
      ),
      actions: [
        OutlinedButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: _submit, child: const Text('Verify')),
      ],
    );
  }
}

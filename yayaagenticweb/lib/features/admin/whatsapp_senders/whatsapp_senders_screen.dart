import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/whatsapp_embedded_signup.dart';
import '../../../models/admin/whatsapp_sender.dart';
import '../channel_bindings/sender_routes_section.dart';
import '../shared/admin_shared.dart';
import 'whatsapp_connect_wizard.dart';
import 'whatsapp_sender_wizard.dart';

/// Refetched on every write to keep status chips fresh.
final whatsAppSendersProvider =
    FutureProvider.family<List<WhatsAppSenderResponse>, String>(
  (ref, tenantId) async {
    final api = await ref.watch(adminApiProvider.future);
    return api.listWhatsAppSenders(tenantId);
  },
);

/// Whether sender registration can hit the upstream provisioning service.
/// When false, the admin UI surfaces a banner explaining that WhatsApp
/// provisioning isn't enabled and the Submit button on each draft sender
/// is disabled — drafts can still be edited and revoked.
final whatsAppSubmitEnabledProvider =
    FutureProvider.family<bool, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.isWhatsAppSubmitEnabled(tenantId);
});

/// Meta Embedded Signup availability + browser-safe ids. Gates the
/// self-service "Connect WhatsApp" wizard — same probe pattern as
/// [whatsAppSubmitEnabledProvider].
final whatsAppEmbeddedSignupConfigProvider =
    FutureProvider.family<EmbeddedSignupConfig, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.getWhatsAppEmbeddedSignupConfig(tenantId);
});

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
  /// Tracks which sender is mid-transition so the corresponding action
  /// button can render a spinner. Without this the operator can click
  /// Submit and stare at a static row while the BSP call (which can take
  /// several seconds) is in flight — that's the original "blank page"
  /// confusion.
  String? _busySenderId;

  void _newSender() {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => const WhatsAppSenderWizard(),
    ));
  }

  void _connectWhatsApp(EmbeddedSignupConfig config) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => WhatsAppConnectWizard(config: config),
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
    setState(() => _busySenderId = sender.id);
    try {
      final api = await ref.read(adminApiProvider.future);
      await call(api);
      ref.invalidate(whatsAppSendersProvider(tenant));
      if (mounted) showSnack(context, successCopy);
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _busySenderId = null);
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
    final submitsEnabled = ref.watch(whatsAppSubmitEnabledProvider(tenant));
    final canSubmit = submitsEnabled.maybeWhen(
      data: (ready) => ready,
      orElse: () => true,    // optimistic until the probe lands
    );

    return AdminTwoPane(
      title: 'WhatsApp senders',
      subtitle:
          'Phone numbers your customers message. Each sender belongs to one workspace '
          'and can be bound to a profile so messages on that number reach the right bot.',
      list: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (!canSubmit) const _ProvisioningUnavailableBanner(),
          if (!canSubmit) const SizedBox(height: 12),
          Expanded(
            child: senders.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e),
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (list) => list.isEmpty
            ? _EmptySendersState(onAdd: _newSender)
            : ListView.separated(
                itemBuilder: (_, i) => _SenderTile(
                  sender: list[i],
                  busy: _busySenderId == list[i].id,
                  canSubmit: canSubmit,
                  onEdit: () => _editSender(list[i]),
                  onSubmit: () async {
                    final method = await showDialog<String>(
                      context: context,
                      builder: (_) => const _SubmitDialog(),
                    );
                    if (method == null) return;
                    await _runTransition(
                      tenant: tenant,
                      sender: list[i],
                      call: (api) => api.submitWhatsAppSender(tenant, list[i].id,
                          verificationMethod: method),
                      successCopy:
                          'Submitted. If WhatsApp sends a code to the phone, enter it here.',
                    );
                  },
                  onVerify: () async {
                    final code = await showDialog<String>(
                      context: context,
                      builder: (_) => _VerificationCodeDialog(phoneE164: list[i].phoneE164),
                    );
                    if (code == null) return;
                    await _runTransition(
                      tenant: tenant,
                      sender: list[i],
                      call: (api) => api.verifyWhatsAppSender(tenant, list[i].id, code),
                      successCopy: 'Code accepted — WhatsApp is finishing the registration.',
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
                    final inFlight = list[i].status == 'SUBMITTING' ||
                        list[i].status == 'PENDING_REVIEW';
                    final confirmed = await _confirm(
                        inFlight ? 'Cancel this registration?' : 'Remove this sender?',
                        inFlight
                            ? 'The registration is withdrawn. You can register the same number '
                                'again afterwards — for example after fixing the business details.'
                            : 'The sender will be retired. Historical conversation records '
                                'are preserved, and the number stops sending and receiving. '
                                'It can be registered again later.',
                        inFlight ? 'Cancel registration' : 'Remove');
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
          ),
        ],
      ),
      form: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Builder(builder: (context) {
              final signupConfig = ref
                  .watch(whatsAppEmbeddedSignupConfigProvider(tenant))
                  .maybeWhen(data: (c) => c, orElse: EmbeddedSignupConfig.disabled);
              if (!signupConfig.enabled) return const SizedBox.shrink();
              return Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: FormCard(
                  title: 'Connect WhatsApp (self-service)',
                  actions: [
                    FilledButton.icon(
                      onPressed: () => _connectWhatsApp(signupConfig),
                      icon: const Icon(Icons.link),
                      label: const Text('Connect WhatsApp'),
                    ),
                  ],
                  child: const Text(
                    'Connect your own WhatsApp Business number in minutes: enter your '
                    'business details and phone number, then authorize through the Meta '
                    'popup. Everything else happens automatically — no external accounts '
                    'or consoles needed.',
                  ),
                ),
              );
            }),
            FormCard(
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
                'number, the public display name and the assistant that answers it, then '
                'submits for WhatsApp review.',
              ),
            ),
          ],
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
    required this.busy,
    required this.canSubmit,
    required this.onEdit,
    required this.onSubmit,
    required this.onVerify,
    required this.onSuspend,
    required this.onResume,
    required this.onRevoke,
    required this.onSync,
  });

  final WhatsAppSenderResponse sender;
  final bool busy;
  final bool canSubmit;
  final VoidCallback onEdit;
  final VoidCallback onSubmit;
  final VoidCallback onVerify;
  final VoidCallback onSuspend;
  final VoidCallback onResume;
  final VoidCallback onRevoke;
  final VoidCallback onSync;

  @override
  Widget build(BuildContext context) {
    final info = SenderStatusInfo.of(sender.status);
    final inFlight = sender.status == 'SUBMITTING' || sender.status == 'PENDING_REVIEW';
    final progress = inFlight ? registrationProgressLabel(sender.onboarding.status) : null;
    final rejection = sender.status == 'FAILED' ? sender.onboarding.failureMessage : null;
    // SUBMITTING failures are our own retries, not something WhatsApp said.
    final inFlightProblem =
        sender.status == 'PENDING_REVIEW' ? sender.onboarding.failureMessage : null;
    final codeHint = !sender.needsVerificationCode && sender.canEnterVerificationCode;
    Widget spinnerOr(IconData icon) => busy
        ? const SizedBox.square(
            dimension: 16,
            child: CircularProgressIndicator(strokeWidth: 2))
        : Icon(icon, size: 18);
    return EditableRecordCard(
      onCloneEdit: onEdit,
      leading: _StatusChip(
          status: sender.status,
          labelOverride: sender.needsVerificationCode ? 'Code needed' : null),
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(sender.displayName,
              style: Theme.of(context).textTheme.titleSmall),
          Text(sender.phoneE164,
              style: const TextStyle(fontSize: 13, fontFeatures: [])),
          // Cards start collapsed; the one action that blocks go-live must
          // be visible without expanding.
          if (sender.needsVerificationCode)
            Text('Action needed — open to enter the WhatsApp verification code',
                style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: Theme.of(context).colorScheme.tertiary)),
          if (codeHint)
            Text('Got a code from WhatsApp? Open to enter it',
                style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: Theme.of(context).colorScheme.tertiary)),
          if (rejection != null)
            Text('Rejected: $rejection — open to fix and resubmit',
                style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: Theme.of(context).colorScheme.error)),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // DetailRow turns values over 80 characters into a monospace
          // block; status copy is prose and should wrap like prose.
          _ProseRow(label: 'Status', value: '${info.label}. ${info.description}'),
          if (rejection != null) ...[
            const SizedBox(height: 4),
            _ProblemNote(
              title: 'Why it was rejected',
              message: rejection,
              hint: 'Use "Fix & resubmit" to correct it and send it again.',
            ),
            const SizedBox(height: 8),
          ],
          if (inFlightProblem != null) ...[
            const SizedBox(height: 4),
            _ProblemNote(
              title: 'WhatsApp reported a problem',
              message: inFlightProblem,
              hint: 'If WhatsApp sent a code to the phone, enter it below. If no code '
                  'arrived or it expired, cancel the registration and register the '
                  'number again to get a new one.',
            ),
            const SizedBox(height: 8),
          ],
          if (progress != null) DetailRow(label: 'Progress', value: progress),
          if (sender.canEnterVerificationCode || sender.needsVerificationCode) ...[
            const SizedBox(height: 8),
            _VerificationPrompt(
              phoneE164: sender.phoneE164,
              confirmed: sender.needsVerificationCode,
              busy: busy,
              onVerify: onVerify,
            ),
            const SizedBox(height: 8),
          ],
          DetailRow(label: 'Phone', value: sender.phoneE164, mono: true),
          DetailRow(label: 'Display name', value: sender.displayName),
          if (sender.approvedDisplayName != null)
            DetailRow(
                label: 'Approved display name',
                value: sender.approvedDisplayName!),
          DetailRow(
              label: 'Business',
              value:
                  '${sender.businessProfile.legalName} (${businessVerticalLabel(sender.businessProfile.vertical)})'),
          DetailRow(
              label: 'Default locale', value: sender.defaultLocale, mono: true),
          if (sender.onboarding.lastPolledAt != null)
            DetailRow(
                label: 'Last checked', value: sender.onboarding.lastPolledAt!),
          if (sender.onboarding.failureMessage != null &&
              rejection == null &&
              inFlightProblem == null)
            DetailRow(
                label: 'Review notes',
                value: sender.onboarding.failureMessage!),
          if (sender.createdAt != null)
            DetailRow(label: 'Created', value: sender.createdAt!),
          if (sender.status != 'REVOKED') ...[
            const Divider(height: 24),
            SenderRoutesSection(
              endpointKind: 'WHATSAPP_SENDER',
              endpointId: sender.id,
            ),
          ],
        ],
      ),
      extraActions: [
        if (sender.status == 'SUBMITTING' || sender.status == 'PENDING_REVIEW')
          TextButton.icon(
            onPressed: busy ? null : onSync,
            icon: spinnerOr(Icons.refresh),
            label: const Text('Refresh status'),
          ),
        if (sender.status == 'DRAFT')
          FilledButton.tonalIcon(
            onPressed: (busy || !canSubmit) ? null : onSubmit,
            icon: spinnerOr(Icons.send),
            label: Text(canSubmit
                ? 'Submit for review'
                : 'Submit unavailable'),
          ),
        if (sender.status == 'FAILED')
          FilledButton.tonalIcon(
            onPressed: busy ? null : onEdit,
            icon: spinnerOr(Icons.edit_outlined),
            label: const Text('Fix & resubmit'),
          ),
        if (sender.status == 'ACTIVE')
          TextButton.icon(
            onPressed: busy ? null : onSuspend,
            icon: spinnerOr(Icons.pause_circle_outline),
            label: const Text('Pause'),
          ),
        if (sender.status == 'SUSPENDED')
          FilledButton.tonalIcon(
            onPressed: busy ? null : onResume,
            icon: spinnerOr(Icons.play_circle_outline),
            label: const Text('Resume'),
          ),
        if (sender.status != 'REVOKED')
          TextButton.icon(
            onPressed: busy ? null : onRevoke,
            icon: spinnerOr(Icons.delete_outline),
            label: Text(inFlight ? 'Cancel registration' : 'Remove'),
            style: TextButton.styleFrom(
                foregroundColor: Theme.of(context).colorScheme.error),
          ),
      ],
    );
  }
}

class _ProseRow extends StatelessWidget {
  const _ProseRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 120,
            child: Text(label,
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: Theme.of(context).colorScheme.outline)),
          ),
          Expanded(child: Text(value, style: const TextStyle(fontSize: 13))),
        ],
      ),
    );
  }
}

/// What WhatsApp reported going wrong, shown where the operator decides
/// what to do next.
class _ProblemNote extends StatelessWidget {
  const _ProblemNote({required this.title, required this.message, required this.hint});

  final String title;
  final String message;
  final String hint;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: scheme.errorContainer,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.error_outline, color: scheme.onErrorContainer),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    style: TextStyle(
                        fontWeight: FontWeight.w600,
                        fontSize: 13,
                        color: scheme.onErrorContainer)),
                const SizedBox(height: 2),
                SelectableText(message,
                    style: TextStyle(fontSize: 13, color: scheme.onErrorContainer)),
                const SizedBox(height: 4),
                Text(hint,
                    style: TextStyle(fontSize: 12, color: scheme.onErrorContainer)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// How WhatsApp delivers the one-time ownership code. Shared by the wizard's
/// phone step and the list's submit dialog so both read the same.
class VerificationMethodSelector extends StatelessWidget {
  const VerificationMethodSelector({super.key, required this.value, required this.onChanged});

  final String value;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Verification code by', style: Theme.of(context).textTheme.labelLarge),
        const SizedBox(height: 8),
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(value: 'sms', icon: Icon(Icons.sms_outlined), label: Text('SMS')),
            ButtonSegment(value: 'voice', icon: Icon(Icons.call_outlined), label: Text('Voice call')),
          ],
          selected: {value},
          onSelectionChanged: (s) => onChanged(s.first),
        ),
        const SizedBox(height: 6),
        Text(
          'WhatsApp may send a one-time code to this number to confirm you own it — '
          'keep the phone nearby after submitting. Choose voice call for landlines.',
          style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
        ),
      ],
    );
  }
}

class _SubmitDialog extends StatefulWidget {
  const _SubmitDialog();

  @override
  State<_SubmitDialog> createState() => _SubmitDialogState();
}

class _SubmitDialogState extends State<_SubmitDialog> {
  String _method = 'sms';

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Submit for WhatsApp review?'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
                'Once submitted, the sender cannot be edited until WhatsApp returns a verdict — '
                'from minutes to a few business days.'),
            const SizedBox(height: 16),
            VerificationMethodSelector(
              value: _method,
              onChanged: (v) => setState(() => _method = v),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: () => Navigator.pop(context, _method), child: const Text('Submit')),
      ],
    );
  }
}

/// Inline call-to-action on a sender card while WhatsApp waits for the code.
class _VerificationPrompt extends StatelessWidget {
  const _VerificationPrompt({
    required this.phoneE164,
    required this.confirmed,
    required this.busy,
    required this.onVerify,
  });

  final String phoneE164;
  /// WhatsApp reported it is waiting for the code, rather than it maybe
  /// being on its way.
  final bool confirmed;
  final bool busy;
  final VoidCallback onVerify;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: scheme.tertiaryContainer,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          Icon(Icons.password_outlined, color: scheme.onTertiaryContainer),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              confirmed
                  ? 'WhatsApp sent a verification code to $phoneE164 by SMS or voice call. '
                      'Enter it to finish registering the number.'
                  : 'If WhatsApp sent a code to $phoneE164 by SMS or voice call, enter it '
                      'here to finish registering the number.',
              style: TextStyle(color: scheme.onTertiaryContainer, fontSize: 13),
            ),
          ),
          const SizedBox(width: 12),
          FilledButton(
            onPressed: busy ? null : onVerify,
            child: const Text('Enter code'),
          ),
        ],
      ),
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
  final _codeCtrl = TextEditingController();

  @override
  void dispose() {
    _codeCtrl.dispose();
    super.dispose();
  }

  bool get _valid => RegExp(r'^[0-9]{4,8}$').hasMatch(_codeCtrl.text.trim());

  void _submit() {
    if (_valid) Navigator.pop(context, _codeCtrl.text.trim());
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Enter verification code'),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Type the code WhatsApp sent to ${widget.phoneE164}.'),
            const SizedBox(height: 12),
            TextField(
              controller: _codeCtrl,
              autofocus: true,
              keyboardType: TextInputType.number,
              maxLength: 8,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: const InputDecoration(
                labelText: 'Verification code',
                helperText: 'Codes expire after a few minutes.',
              ),
              onChanged: (_) => setState(() {}),
              onSubmitted: (_) => _submit(),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: _valid ? _submit : null, child: const Text('Verify')),
      ],
    );
  }
}

/// Surfaces "WhatsApp provisioning isn't available on this instance" when
/// the readiness probe says the platform can't submit senders to the
/// upstream service. Tenant-facing copy: never names the BSP, never
/// instructs the operator to do something they can't (the fix is platform
/// admin work).
class _ProvisioningUnavailableBanner extends StatelessWidget {
  const _ProvisioningUnavailableBanner();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: scheme.tertiaryContainer,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: scheme.tertiary.withValues(alpha: 0.25)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.info_outline, color: scheme.onTertiaryContainer),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'WhatsApp message provisioning is currently unavailable',
                  style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      color: scheme.onTertiaryContainer,
                      fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 4),
                Text(
                  'You can still draft, edit, and remove senders on this workspace. '
                  'Submitting a sender for review is disabled until your platform '
                  'administrator finishes setup. Contact your administrator to '
                  'enable WhatsApp on this instance.',
                  style: TextStyle(
                      color: scheme.onTertiaryContainer, fontSize: 13),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.status, this.labelOverride});
  final String status;
  final String? labelOverride;

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
        labelOverride ?? SenderStatusInfo.of(status).label,
        style: TextStyle(
            fontSize: 11, fontWeight: FontWeight.w700, color: _fg(scheme)),
      ),
    );
  }
}

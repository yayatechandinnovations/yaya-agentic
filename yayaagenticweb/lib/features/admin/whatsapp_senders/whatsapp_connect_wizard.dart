import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/whatsapp_embedded_signup.dart';
import '../../../models/admin/whatsapp_sender.dart';
import '../shared/admin_shared.dart';
import 'embedded_signup/embedded_signup_events.dart';
import 'embedded_signup/signup_launcher.dart';
import 'whatsapp_senders_screen.dart' show whatsAppSendersProvider;

/// Self-service "Connect WhatsApp" wizard (Meta Embedded Signup).
///
/// Step 1 collects the phone number + display name + business profile —
/// deliberately BEFORE the Meta popup, so the backend never depends on the
/// popup returning the full phone number. Step 2 opens Meta's Embedded
/// Signup; on FINISH the captured business-account ids are submitted to the
/// backend, which provisions everything and registers the sender. The final
/// panel shows the real asynchronous provisioning status from the backend —
/// the frontend never decides "connected" on its own.
class WhatsAppConnectWizard extends ConsumerStatefulWidget {
  const WhatsAppConnectWizard({
    super.key,
    required this.config,
    this.launcherFactory,
  });

  final EmbeddedSignupConfig config;

  /// Test seam — production uses the real Facebook SDK launcher.
  final EmbeddedSignupLauncher Function()? launcherFactory;

  @override
  ConsumerState<WhatsAppConnectWizard> createState() =>
      _WhatsAppConnectWizardState();
}

class _WhatsAppConnectWizardState extends ConsumerState<WhatsAppConnectWizard> {
  int _stepIndex = 0;

  // ---- step 1 — business + phone -----------------------------------------
  late final TextEditingController _legalNameCtrl = TextEditingController();
  late final TextEditingController _businessDisplayNameCtrl =
      TextEditingController();
  late final TextEditingController _contactEmailCtrl = TextEditingController();
  late final TextEditingController _addressCtrl = TextEditingController();
  late final TextEditingController _websiteUrlCtrl = TextEditingController();
  late final TextEditingController _aboutCtrl = TextEditingController();
  late final TextEditingController _phoneCtrl = TextEditingController();
  late final TextEditingController _publicDisplayNameCtrl =
      TextEditingController();
  late final TextEditingController _localeCtrl =
      TextEditingController(text: 'en-US');
  String? _vertical;
  bool _termsAccepted = false;

  // ---- step 2 — Meta signup + backend submission -------------------------
  EmbeddedSignupLauncher? _launcher;
  StreamSubscription<EmbeddedSignupEvent>? _eventsSub;
  bool _connecting = false; // popup open / SDK loading — blocks re-clicks
  bool _submitting = false; // backend call in flight — blocks re-clicks
  String? _wabaId;
  String? _metaPhoneNumberId;
  OnboardingResponse? _result;

  @override
  void dispose() {
    _eventsSub?.cancel();
    _launcher?.dispose();
    _legalNameCtrl.dispose();
    _businessDisplayNameCtrl.dispose();
    _contactEmailCtrl.dispose();
    _addressCtrl.dispose();
    _websiteUrlCtrl.dispose();
    _aboutCtrl.dispose();
    _phoneCtrl.dispose();
    _publicDisplayNameCtrl.dispose();
    _localeCtrl.dispose();
    super.dispose();
  }

  // ---- validation --------------------------------------------------------

  String? _validateDetailsStep() {
    final phone = _phoneCtrl.text.trim();
    if (phone.isEmpty) return 'WhatsApp phone number is required.';
    if (!RegExp(r'^\+[1-9][0-9]{6,17}$').hasMatch(phone)) {
      return 'Phone number must be in E.164 format including the country '
          'calling code, e.g. +18095551234.';
    }
    if (_publicDisplayNameCtrl.text.trim().isEmpty) {
      return 'Display name is required.';
    }
    if (_legalNameCtrl.text.trim().isEmpty) {
      return 'Business name is required.';
    }
    if (_contactEmailCtrl.text.trim().isEmpty ||
        !_contactEmailCtrl.text.contains('@')) {
      return 'A valid contact email is required.';
    }
    if (_vertical == null) {
      return 'Select the business vertical that best fits your business.';
    }
    if (!_termsAccepted) {
      return 'You must confirm you are authorized to connect this number.';
    }
    return null;
  }

  void _next() {
    final error = _validateDetailsStep();
    if (error != null) {
      showSnack(context, error, error: true);
      return;
    }
    setState(() => _stepIndex = 1);
  }

  // ---- Meta signup -------------------------------------------------------

  Future<void> _connectWithMeta() async {
    if (_connecting || _submitting) return; // duplicate-click prevention
    final error = _validateDetailsStep();
    if (error != null) {
      showSnack(context, error, error: true);
      return;
    }
    setState(() => _connecting = true);
    try {
      _launcher ??= (widget.launcherFactory ?? createEmbeddedSignupLauncher)();
      _eventsSub ??= _launcher!.events.listen(_onSignupEvent);
      await _launcher!.load(appId: widget.config.metaAppId ?? '');
      _launcher!.launch(
        configurationId: widget.config.metaConfigurationId ?? '',
        partnerSolutionId: widget.config.partnerSolutionId,
      );
    } catch (e) {
      if (mounted) {
        setState(() => _connecting = false);
        showSnack(context, formatError(e), error: true);
      }
    }
  }

  void _onSignupEvent(EmbeddedSignupEvent event) {
    if (!mounted) return;
    switch (event) {
      case SignupFinish finish:
        // The popup finishing is NOT "connected" — the backend decides that.
        setState(() {
          _wabaId = finish.wabaId;
          _metaPhoneNumberId = finish.phoneNumberId;
          _connecting = false;
        });
        _submitOnboarding();
      case SignupCancel _:
        // A late CANCEL after FINISH (login dialog closing) is benign.
        if (_wabaId != null || _submitting || _result != null) return;
        setState(() => _connecting = false);
        showSnack(context, 'Meta signup was cancelled before completing.');
      case SignupError err:
        setState(() => _connecting = false);
        showSnack(
            context,
            err.message == null || err.message!.isEmpty
                ? 'Meta reported an error during signup. Please try again.'
                : 'Meta signup error: ${err.message}',
            error: true);
      case SignupIgnored _:
        break;
    }
  }

  // ---- backend submission ------------------------------------------------

  Future<void> _submitOnboarding() async {
    if (_submitting) return;
    final tenant = ref.read(currentTenantOrNull);
    final wabaId = _wabaId;
    if (tenant == null || wabaId == null) return;
    setState(() => _submitting = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final res = await api.startWhatsAppEmbeddedSignup(
        tenant,
        OnboardingRequest(
          phoneE164: _phoneCtrl.text.trim(),
          wabaId: wabaId,
          metaPhoneNumberId: _metaPhoneNumberId,
          displayName: _publicDisplayNameCtrl.text.trim(),
          defaultLocale: _localeCtrl.text.trim(),
          businessProfile: BusinessProfileFields(
            legalName: _legalNameCtrl.text.trim(),
            displayName: _businessDisplayNameCtrl.text.trim().isEmpty
                ? _publicDisplayNameCtrl.text.trim()
                : _businessDisplayNameCtrl.text.trim(),
            contactEmail: _contactEmailCtrl.text.trim(),
            address: _addressCtrl.text.trim(),
            websiteUrl: _websiteUrlCtrl.text.trim(),
            vertical: _vertical!,
            about: _aboutCtrl.text.trim(),
          ),
        ),
      );
      ref.invalidate(whatsAppSendersProvider(tenant));
      if (!mounted) return;
      setState(() => _result = res);
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  Future<void> _refreshStatus() async {
    final tenant = ref.read(currentTenantOrNull);
    final senderId = _result?.sender?.id;
    if (tenant == null || senderId == null) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      final refreshed = await api.syncWhatsAppSender(tenant, senderId);
      ref.invalidate(whatsAppSendersProvider(tenant));
      if (!mounted) return;
      setState(() => _result = OnboardingResponse(
          attempt: _result!.attempt, sender: refreshed));
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  // ---- UI ----------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Connect WhatsApp')),
        body: const Center(child: Text('Select a workspace first.')),
      );
    }

    return Scaffold(
      appBar: AppBar(title: const Text('Connect WhatsApp')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 720),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
            child: Stepper(
              currentStep: _stepIndex,
              controlsBuilder: (context, details) => const SizedBox.shrink(),
              steps: [
                Step(
                  title: const Text('Business & phone details'),
                  isActive: _stepIndex >= 0,
                  state: _stepIndex > 0 ? StepState.complete : StepState.indexed,
                  content: _detailsStep(),
                ),
                Step(
                  title: const Text('Connect with Meta'),
                  isActive: _stepIndex >= 1,
                  state: _result != null ? StepState.complete : StepState.indexed,
                  content: _connectStep(),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _detailsStep() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          controller: _phoneCtrl,
          keyboardType: const TextInputType.numberWithOptions(signed: true),
          inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9+]'))],
          decoration: const InputDecoration(
            labelText: 'WhatsApp phone number (E.164) *',
            helperText:
                'International format with country calling code, e.g. +18095551234. '
                'Enter it here — do not rely on the Meta popup to choose it.',
            prefixIcon: Icon(Icons.phone_outlined),
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _publicDisplayNameCtrl,
          maxLength: 128,
          decoration: const InputDecoration(
            labelText: 'WhatsApp display name *',
            helperText: 'Shown to customers next to your messages.',
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _legalNameCtrl,
          decoration: const InputDecoration(
            labelText: 'Business name *',
            helperText: 'Your registered business name.',
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _businessDisplayNameCtrl,
          decoration: const InputDecoration(
            labelText: 'Business display name',
            helperText:
                'How your business is known publicly. Defaults to the WhatsApp display name.',
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _contactEmailCtrl,
          keyboardType: TextInputType.emailAddress,
          decoration: const InputDecoration(labelText: 'Contact email *'),
        ),
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue: _vertical,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Business vertical *'),
          items: kMetaBusinessVerticals
              .map((v) => DropdownMenuItem(value: v, child: Text(businessVerticalLabel(v))))
              .toList(),
          onChanged: (v) => setState(() => _vertical = v),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _addressCtrl,
          decoration: const InputDecoration(labelText: 'Business address'),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _websiteUrlCtrl,
          keyboardType: TextInputType.url,
          decoration: const InputDecoration(labelText: 'Website URL'),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _aboutCtrl,
          maxLength: 139,
          decoration: const InputDecoration(labelText: 'About (profile description)'),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _localeCtrl,
          decoration: const InputDecoration(
            labelText: 'Default locale *',
            helperText: 'BCP-47 tag, e.g. "en-US", "es-DO".',
          ),
        ),
        const SizedBox(height: 8),
        CheckboxListTile(
          value: _termsAccepted,
          onChanged: (v) => setState(() => _termsAccepted = v ?? false),
          controlAffinity: ListTileControlAffinity.leading,
          contentPadding: EdgeInsets.zero,
          title: const Text(
            'I confirm I am authorized to connect this phone number for this '
            'business and accept the WhatsApp Business messaging policies.',
            style: TextStyle(fontSize: 13),
          ),
        ),
        const SizedBox(height: 8),
        Align(
          alignment: Alignment.centerLeft,
          child: FilledButton(onPressed: _next, child: const Text('Next')),
        ),
      ],
    );
  }

  Widget _connectStep() {
    final result = _result;
    if (result != null) return _statusPanel(result);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          'Meta will open a popup to sign in with Facebook, confirm your '
          'business, and authorize WhatsApp for ${_phoneCtrl.text.trim()}. '
          'You stay in this app the whole time — only the Meta popup is external.',
          style: Theme.of(context).textTheme.bodyMedium,
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            FilledButton.icon(
              onPressed:
                  (_connecting || _submitting) ? null : _connectWithMeta,
              icon: _connecting || _submitting
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.link),
              label: Text(_submitting
                  ? 'Connecting…'
                  : _connecting
                      ? 'Waiting for Meta…'
                      : 'Connect with Meta'),
            ),
            const SizedBox(width: 8),
            TextButton(
              onPressed: (_connecting || _submitting)
                  ? null
                  : () => setState(() => _stepIndex = 0),
              child: const Text('Back'),
            ),
          ],
        ),
        if (_wabaId != null && !_submitting) ...[
          const SizedBox(height: 12),
          Text('Meta authorization captured. Finalizing the connection…',
              style: Theme.of(context).textTheme.bodySmall),
        ],
      ],
    );
  }

  Widget _statusPanel(OnboardingResponse result) {
    final sender = result.sender;
    final attempt = result.attempt;
    final statusInfo =
        sender == null ? null : SenderStatusInfo.of(sender.status);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: Icon(
            attempt.isFailed
                ? Icons.error_outline
                : sender?.status == 'ACTIVE'
                    ? Icons.check_circle_outline
                    : Icons.hourglass_top,
            color: attempt.isFailed
                ? Theme.of(context).colorScheme.error
                : Theme.of(context).colorScheme.primary,
          ),
          title: Text(statusInfo?.label ?? attempt.state),
          subtitle: Text(
            attempt.isFailed
                ? (attempt.failureMessage ??
                    'The connection failed. You can retry from this screen.')
                : (statusInfo?.description ??
                    'Your number is being registered. Approval is usually quick '
                        'but can take up to a few days.'),
          ),
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            if (sender != null)
              OutlinedButton.icon(
                onPressed: _refreshStatus,
                icon: const Icon(Icons.refresh),
                label: const Text('Refresh status'),
              ),
            if (attempt.isFailed)
              FilledButton.icon(
                onPressed:
                    (_connecting || _submitting) ? null : _connectWithMeta,
                icon: const Icon(Icons.replay),
                label: const Text('Retry connection'),
              ),
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Done'),
            ),
          ],
        ),
      ],
    );
  }
}

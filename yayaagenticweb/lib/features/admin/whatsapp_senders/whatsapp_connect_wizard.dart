import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/whatsapp_embedded_signup.dart';
import '../../../models/admin/whatsapp_sender.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../channel_bindings/channel_ui.dart';
import 'embedded_signup/embedded_signup_events.dart';
import 'embedded_signup/signup_launcher.dart';
import 'sender_logo_picker.dart';
import 'whatsapp_senders_screen.dart' show senderBadgeLabel, senderTone, whatsAppSendersProvider;

const _labels = {
  'phoneE164': 'WhatsApp phone number',
  'displayName': 'WhatsApp display name',
  'legalName': 'Business name',
  'businessDisplayName': 'Business display name',
  'contactEmail': 'Contact email',
  'vertical': 'Business category',
  'address': 'Business address',
  'websiteUrl': 'Website',
  'about': 'About',
  'defaultLocale': 'Default locale',
  'termsAccepted': 'Authorization',
};

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
  final _legalNameCtrl = TextEditingController();
  final _businessDisplayNameCtrl = TextEditingController();
  final _contactEmailCtrl = TextEditingController();
  final _addressCtrl = TextEditingController();
  final _websiteUrlCtrl = TextEditingController();
  final _aboutCtrl = TextEditingController();
  final _phoneCtrl = TextEditingController();
  final _publicDisplayNameCtrl = TextEditingController();
  final _localeCtrl = TextEditingController(text: 'en-US');
  String? _vertical;
  String _logoUrl = '';
  bool _termsAccepted = false;
  late final YFormController _form;

  /// What Meta or the backend said went wrong; stays until the next attempt.
  String? _connectError;

  // ---- step 2 — Meta signup + backend submission -------------------------
  EmbeddedSignupLauncher? _launcher;
  StreamSubscription<EmbeddedSignupEvent>? _eventsSub;
  bool _connecting = false; // popup open / SDK loading — blocks re-clicks
  bool _submitting = false; // backend call in flight — blocks re-clicks
  String? _wabaId;
  String? _metaPhoneNumberId;
  OnboardingResponse? _result;

  @override
  void initState() {
    super.initState();
    final tenant = ref.read(currentTenantOrNull);
    _form = YFormController(
      snapshot: () => {
        'phoneE164': _phoneCtrl.text.trim(),
        'displayName': _publicDisplayNameCtrl.text.trim(),
        'legalName': _legalNameCtrl.text.trim(),
        'businessDisplayName': _businessDisplayNameCtrl.text.trim(),
        'contactEmail': _contactEmailCtrl.text.trim(),
        'vertical': _vertical,
        'address': _addressCtrl.text.trim(),
        'websiteUrl': _websiteUrlCtrl.text.trim(),
        'about': _aboutCtrl.text.trim(),
        'logoUrl': _logoUrl,
        'defaultLocale': _localeCtrl.text.trim(),
      },
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.whatsappConnect.$tenant.new',
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      final v = d.values;
      String s(String k, TextEditingController c) => (v[k] as String?) ?? c.text;
      _phoneCtrl.text = s('phoneE164', _phoneCtrl);
      _publicDisplayNameCtrl.text = s('displayName', _publicDisplayNameCtrl);
      _legalNameCtrl.text = s('legalName', _legalNameCtrl);
      _businessDisplayNameCtrl.text = s('businessDisplayName', _businessDisplayNameCtrl);
      _contactEmailCtrl.text = s('contactEmail', _contactEmailCtrl);
      _addressCtrl.text = s('address', _addressCtrl);
      _websiteUrlCtrl.text = s('websiteUrl', _websiteUrlCtrl);
      _aboutCtrl.text = s('about', _aboutCtrl);
      _localeCtrl.text = s('defaultLocale', _localeCtrl);
      _vertical = normalizeBusinessVertical(v['vertical'] as String?) ?? _vertical;
      _logoUrl = (v['logoUrl'] as String?) ?? _logoUrl;
      _form.refresh();
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
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

  Map<String, String?> _validate() => {
        'phoneE164': ChannelValidators.e164(_phoneCtrl.text),
        'displayName': Validators.required(_publicDisplayNameCtrl.text, 'the name customers see'),
        'legalName': Validators.required(_legalNameCtrl.text, 'your registered business name'),
        'contactEmail': ChannelValidators.email(_contactEmailCtrl.text),
        'vertical': _vertical == null ? 'Choose the category that best fits your business.' : null,
        'websiteUrl': websiteUrlError(_websiteUrlCtrl.text),
        'defaultLocale': ChannelValidators.locale(_localeCtrl.text),
        'termsAccepted': _termsAccepted ? null : 'Confirm you are authorized to connect this number.',
      };

  bool _detailsValid() {
    final website = normalizeWebsiteUrl(_websiteUrlCtrl.text);
    if (website != _websiteUrlCtrl.text) _websiteUrlCtrl.text = website;
    return _form.submit();
  }

  void _next() {
    if (!_detailsValid()) return;
    setState(() => _stepIndex = 1);
  }

  // ---- Meta signup -------------------------------------------------------

  Future<void> _connectWithMeta() async {
    if (_connecting || _submitting) return; // duplicate-click prevention
    if (!_detailsValid()) {
      setState(() => _stepIndex = 0);
      return;
    }
    setState(() {
      _connecting = true;
      _connectError = null;
    });
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
        setState(() {
          _connecting = false;
          _connectError = ApiErrorInfo.from(e).message;
        });
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
        setState(() {
          _connecting = false;
          _connectError = 'Meta signup was cancelled before completing. Connect again when you’re ready.';
        });
      case SignupError err:
        setState(() {
          _connecting = false;
          _connectError = err.message == null || err.message!.isEmpty
              ? 'Meta reported an error during signup. Please try again.'
              : 'Meta signup error: ${err.message}';
        });
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
            logoUrl: _logoUrl,
          ),
        ),
      );
      ref.invalidate(whatsAppSendersProvider(tenant));
      // The sender exists now; leaving the page must not warn about the form.
      _form.markClean();
      if (!mounted) return;
      setState(() => _result = res);
    } catch (e) {
      if (mounted) setState(() => _connectError = ApiErrorInfo.from(e).message);
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
      setState(() {
        _connectError = null;
        _result = OnboardingResponse(attempt: _result!.attempt, sender: refreshed);
      });
    } catch (e) {
      if (mounted) setState(() => _connectError = ApiErrorInfo.from(e).message);
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

    return YUnsavedGuard(
      form: _form,
      child: Scaffold(
        appBar: AppBar(title: const Text('Connect WhatsApp')),
        body: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                if (_stepIndex == 0 && (_form.summary.isNotEmpty || _form.formError != null))
                  Padding(
                    padding: const EdgeInsets.only(bottom: Gap.md),
                    child: YErrorSummary(form: _form, action: 'connecting'),
                  ),
                Expanded(
                  child: Stepper(
                    currentStep: _stepIndex,
                    controlsBuilder: (context, details) => const SizedBox.shrink(),
                    steps: [
                      Step(
                        title: const Text('Business & phone details'),
                        isActive: true,
                        state: _stepIndex > 0 ? StepState.complete : StepState.indexed,
                        content: _detailsStep(),
                      ),
                      Step(
                        title: const Text('Connect with Meta'),
                        isActive: _stepIndex >= 1,
                        state: _result != null ? StepState.complete : StepState.indexed,
                        content: _connectStep(tenant),
                      ),
                    ],
                  ),
                ),
              ]),
            ),
          ),
        ),
      ),
    );
  }

  Widget _detailsStep() {
    final fields = <Widget>[
      YTextField(
        label: 'WhatsApp phone number',
        wireKey: 'phoneE164',
        controller: _phoneCtrl,
        form: _form,
        name: 'phoneE164',
        required: true,
        mono: true,
        keyboardType: const TextInputType.numberWithOptions(signed: true),
        inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9+]'))],
        prefix: const Icon(Icons.phone_outlined, size: 18),
        hint: 'e.g. +18095551234',
        helper: 'International format with the country code. Enter it here — don’t rely on the Meta popup to choose it.',
      ),
      YTextField(
        label: 'WhatsApp display name',
        wireKey: 'displayName',
        controller: _publicDisplayNameCtrl,
        form: _form,
        name: 'displayName',
        required: true,
        inputFormatters: [LengthLimitingTextInputFormatter(128)],
        hint: 'e.g. Acme Foods',
        helper: 'Shown to customers next to your messages.',
      ),
      YTextField(
        label: 'Business name',
        wireKey: 'businessProfile.legalName',
        controller: _legalNameCtrl,
        form: _form,
        name: 'legalName',
        required: true,
        hint: 'e.g. Acme Foods S.R.L.',
        helper: 'Your registered business name.',
      ),
      YTextField(
        label: 'Business display name',
        wireKey: 'businessProfile.displayName',
        controller: _businessDisplayNameCtrl,
        form: _form,
        name: 'businessDisplayName',
        optional: true,
        helper: 'How your business is known publicly. Defaults to the WhatsApp display name.',
      ),
      YTextField(
        label: 'Contact email',
        wireKey: 'businessProfile.contactEmail',
        controller: _contactEmailCtrl,
        form: _form,
        name: 'contactEmail',
        required: true,
        keyboardType: TextInputType.emailAddress,
        hint: 'e.g. ops@acme.example',
      ),
      YSelectField<String>(
        label: 'Business category',
        wireKey: 'businessProfile.vertical',
        form: _form,
        name: 'vertical',
        required: true,
        value: _vertical,
        hint: 'Choose a category',
        options: [for (final v in kMetaBusinessVerticals) YOption(v, businessVerticalLabel(v))],
        onChanged: (v) => setState(() => _vertical = v),
      ),
      YTextField(
        label: 'Business address',
        wireKey: 'businessProfile.address',
        controller: _addressCtrl,
        form: _form,
        name: 'address',
        optional: true,
      ),
      YTextField(
        label: 'Website',
        wireKey: 'businessProfile.websiteUrl',
        controller: _websiteUrlCtrl,
        form: _form,
        name: 'websiteUrl',
        optional: true,
        keyboardType: TextInputType.url,
        hint: 'e.g. https://example.com',
      ),
      YTextField(
        label: 'About',
        wireKey: 'businessProfile.about',
        controller: _aboutCtrl,
        form: _form,
        name: 'about',
        optional: true,
        inputFormatters: [LengthLimitingTextInputFormatter(139)],
        helper: 'Profile description, up to 139 characters.',
      ),
      SenderLogoPicker(
        url: _logoUrl,
        onChanged: (url) {
          setState(() => _logoUrl = url);
          _form.refresh();
        },
      ),
      YTextField(
        label: 'Default locale',
        wireKey: 'defaultLocale',
        controller: _localeCtrl,
        form: _form,
        name: 'defaultLocale',
        required: true,
        mono: true,
        hint: 'e.g. es-DO',
        helper: 'Language tag used to pick message templates, e.g. en-US or es-DO.',
      ),
      YFieldShell(
        label: 'Authorization',
        form: _form,
        name: 'termsAccepted',
        required: true,
        child: CheckboxListTile(
          value: _termsAccepted,
          onChanged: (v) {
            setState(() => _termsAccepted = v ?? false);
            _form.changed('termsAccepted');
          },
          controlAffinity: ListTileControlAffinity.leading,
          contentPadding: EdgeInsets.zero,
          title: const Text(
            'I confirm I am authorized to connect this phone number for this business and accept the WhatsApp '
            'Business messaging policies.',
            style: TextStyle(fontSize: 14, height: 1.45),
          ),
        ),
      ),
      Align(
        alignment: Alignment.centerLeft,
        child: FilledButton(onPressed: _next, child: const Text('Next')),
      ),
    ];
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        for (var i = 0; i < fields.length; i++) ...[if (i > 0) const SizedBox(height: Gap.fieldGap), fields[i]],
      ]),
    );
  }

  Widget _connectStep(String tenant) {
    final result = _result;
    final scheme = Theme.of(context).colorScheme;
    final error = _connectError == null
        ? null
        : Padding(
            padding: const EdgeInsets.only(top: Gap.md),
            child: YBanner(tone: YBannerTone.error, message: _connectError!),
          );
    if (result != null) {
      return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [_statusPanel(tenant, result), ?error]);
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          'Meta will open a popup to sign in with Facebook, confirm your business, and authorize WhatsApp for '
          '${_phoneCtrl.text.trim()}. You stay in this app the whole time — only the Meta popup is external.',
          style: TextStyle(fontSize: 14, height: 1.5, color: scheme.onSurface),
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            FilledButton.icon(
              onPressed: (_connecting || _submitting) ? null : _connectWithMeta,
              icon: _connecting || _submitting
                  ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.link),
              label: Text(_submitting
                  ? 'Connecting…'
                  : _connecting
                      ? 'Waiting for Meta…'
                      : 'Connect with Meta'),
            ),
            const SizedBox(width: 8),
            TextButton(
              onPressed: (_connecting || _submitting) ? null : () => setState(() => _stepIndex = 0),
              child: const Text('Back'),
            ),
          ],
        ),
        if (_wabaId != null && !_submitting) ...[
          const SizedBox(height: 12),
          Text('Meta authorization captured. Finalizing the connection…',
              style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
        ],
        ?error,
      ],
    );
  }

  Widget _statusPanel(String tenant, OnboardingResponse result) {
    final scheme = Theme.of(context).colorScheme;
    // The live list keeps polling while the sender is in review, so the
    // verdict shows up here without pressing Refresh.
    final live = result.sender == null
        ? null
        : (ref.watch(whatsAppSendersProvider(tenant)).valueOrNull ?? const <WhatsAppSenderResponse>[])
            .where((s) => s.id == result.sender!.id)
            .firstOrNull;
    final sender = live ?? result.sender;
    final attempt = result.attempt;
    final statusInfo = sender == null ? null : SenderStatusInfo.of(sender.status);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Align(
          alignment: Alignment.centerLeft,
          child: sender != null
              ? YBadge(senderBadgeLabel(sender), tone: senderTone(sender), icon: iconForStatus(sender.status))
              : YBadge(humanizeWire(attempt.state), tone: toneForStatus(attempt.state), icon: iconForStatus(attempt.state)),
        ),
        const SizedBox(height: 8),
        Text(
          attempt.isFailed
              ? (attempt.failureMessage ?? 'The connection failed. You can retry from this screen.')
              : (statusInfo?.description ??
                  'Your number is being registered. Approval is usually quick but can take up to a few days.'),
          style: TextStyle(fontSize: 14, height: 1.5, color: attempt.isFailed ? scheme.error : scheme.onSurfaceVariant),
        ),
        const SizedBox(height: 12),
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
                onPressed: (_connecting || _submitting) ? null : _connectWithMeta,
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

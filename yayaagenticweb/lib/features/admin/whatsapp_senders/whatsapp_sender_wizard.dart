import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/channel_binding.dart';
import '../../../models/admin/profile.dart';
import '../../../models/admin/start_flow.dart';
import '../../../models/admin/whatsapp_sender.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/form/y_resource_picker.dart';
import '../../../ui/tokens.dart';
import '../channel_bindings/channel_ui.dart';
import '../channel_bindings/sender_routes_section.dart' show channelBindingsProvider;
import '../flows/flows_admin_screen.dart' show startFlowsProvider;
import '../profiles/profiles_screen.dart' show profilesProvider;
import 'sender_logo_picker.dart';
import 'whatsapp_senders_screen.dart' show VerificationMethodSelector, whatsAppSendersProvider, whatsAppSubmitEnabledProvider;

const _labels = {
  'legalName': 'Legal business name',
  'businessDisplayName': 'Business display name',
  'contactEmail': 'Contact email',
  'address': 'Business address',
  'websiteUrl': 'Website',
  'vertical': 'Business category',
  'about': 'About',
  'phoneE164': 'Phone number',
  'displayName': 'Public display name',
  'defaultLocale': 'Default locale',
};

/// Which step owns each field — a step's fields are only checked once the
/// operator tries to leave it, so the next step never opens full of errors.
const _stepOf = {
  'legalName': 0,
  'businessDisplayName': 0,
  'contactEmail': 0,
  'address': 0,
  'websiteUrl': 0,
  'vertical': 0,
  'about': 0,
  'logoUrl': 0,
  'phoneE164': 1,
  'verificationMethod': 1,
  'displayName': 2,
  'defaultLocale': 2,
  'assistant': 2,
  'startFlowId': 2,
};

const _maxAbout = 139;
const _maxDisplayName = 128;

/// 4-step sender registration. Tenants describe their business, pick a phone
/// number, choose a display name and the assistant that answers it, and
/// review. The last step saves the sender and — unless the operator picks
/// "Save as draft" — submits it for WhatsApp review in the same click,
/// routing the number to the chosen assistant so it answers as soon as the
/// sender goes live.
///
/// Optional [existing] turns the wizard into an editor for a DRAFT/FAILED
/// sender. Routes on an existing sender are managed in its detail sheet, so
/// the assistant picker only appears for new ones.
class WhatsAppSenderWizard extends ConsumerStatefulWidget {
  const WhatsAppSenderWizard({super.key, this.existing});

  final WhatsAppSenderResponse? existing;

  @override
  ConsumerState<WhatsAppSenderWizard> createState() => _WhatsAppSenderWizardState();
}

class _WhatsAppSenderWizardState extends ConsumerState<WhatsAppSenderWizard> {
  int _stepIndex = 0;

  /// Highest step whose fields are validated.
  int _gate = -1;

  final _legalName = TextEditingController();
  final _businessDisplayName = TextEditingController();
  final _contactEmail = TextEditingController();
  final _address = TextEditingController();
  final _website = TextEditingController();
  final _about = TextEditingController();
  String? _vertical;
  String _logoUrl = '';

  final _phone = TextEditingController();
  String _verificationMethod = 'sms';

  final _displayName = TextEditingController();
  final _locale = TextEditingController();
  String? _startFlowId;
  String? _profileSlug; // "<id>@<version>"

  late final YFormController _form;
  DateTime? _restoredDraftAt;

  bool get _isNew => widget.existing == null;

  @override
  void initState() {
    super.initState();
    _load(widget.existing);
    final tenant = ref.read(currentTenantOrNull);
    _form = YFormController(
      snapshot: _snapshot,
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.whatsappSender.$tenant.${widget.existing?.id ?? 'new'}',
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      _apply(d.values);
      _form.refresh();
      setState(() => _restoredDraftAt = d.savedAt);
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  void _load(WhatsAppSenderResponse? existing) {
    final bp = existing?.businessProfile ?? BusinessProfileFields.empty();
    _legalName.text = bp.legalName;
    _businessDisplayName.text = bp.displayName.isEmpty ? existing?.displayName ?? '' : bp.displayName;
    _contactEmail.text = bp.contactEmail;
    _address.text = bp.address;
    _website.text = bp.websiteUrl;
    _about.text = bp.about;
    _vertical = normalizeBusinessVertical(bp.vertical);
    _logoUrl = bp.logoUrl;
    _phone.text = existing?.phoneE164 ?? '';
    _displayName.text = existing?.displayName ?? '';
    _locale.text = existing?.defaultLocale ?? 'en-US';
    _startFlowId = existing?.startFlowId;
    _profileSlug = null;
    _verificationMethod = 'sms';
  }

  FormSnapshot _snapshot() => {
        'legalName': _legalName.text.trim(),
        'businessDisplayName': _businessDisplayName.text.trim(),
        'contactEmail': _contactEmail.text.trim(),
        'address': _address.text.trim(),
        'websiteUrl': _website.text.trim(),
        'vertical': _vertical,
        'about': _about.text.trim(),
        'logoUrl': _logoUrl,
        'phoneE164': _phone.text.trim(),
        'verificationMethod': _verificationMethod,
        'displayName': _displayName.text.trim(),
        'defaultLocale': _locale.text.trim(),
        'startFlowId': _startFlowId,
        'assistant': _profileSlug,
      };

  void _apply(FormSnapshot v) {
    String s(String k, TextEditingController c) => (v[k] as String?) ?? c.text;
    _legalName.text = s('legalName', _legalName);
    _businessDisplayName.text = s('businessDisplayName', _businessDisplayName);
    _contactEmail.text = s('contactEmail', _contactEmail);
    _address.text = s('address', _address);
    _website.text = s('websiteUrl', _website);
    _about.text = s('about', _about);
    _vertical = normalizeBusinessVertical(v['vertical'] as String?) ?? _vertical;
    _logoUrl = (v['logoUrl'] as String?) ?? _logoUrl;
    _phone.text = s('phoneE164', _phone);
    _verificationMethod = (v['verificationMethod'] as String?) ?? _verificationMethod;
    _displayName.text = s('displayName', _displayName);
    _locale.text = s('defaultLocale', _locale);
    _startFlowId = v.containsKey('startFlowId') ? v['startFlowId'] as String? : _startFlowId;
    if (_isNew) _profileSlug = v['assistant'] as String?;
  }

  Future<void> _discardDraft() async {
    await _form.clearDraft();
    _load(widget.existing);
    _form.markClean();
    setState(() => _restoredDraftAt = null);
  }

  Map<String, String?> _validate() {
    final display = _displayName.text.trim();
    final all = <String, String?>{
      'legalName': Validators.required(_legalName.text, 'the legal business name'),
      'businessDisplayName': Validators.required(_businessDisplayName.text, 'the name your business is known by'),
      'contactEmail': ChannelValidators.email(_contactEmail.text),
      'vertical': _vertical == null ? 'Choose the category that best fits your business.' : null,
      'websiteUrl': websiteUrlError(_website.text),
      'about': _about.text.trim().length > _maxAbout ? 'Keep it to $_maxAbout characters.' : null,
      'phoneE164': ChannelValidators.e164(_phone.text),
      'displayName': display.isEmpty
          ? 'Enter the name customers see next to your messages.'
          : display.length > _maxDisplayName
              ? 'Keep it to $_maxDisplayName characters.'
              : null,
      'defaultLocale': ChannelValidators.locale(_locale.text),
    };
    return {for (final e in all.entries) e.key: (_stepOf[e.key] ?? 0) <= _gate ? e.value : null};
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    for (final c in [_legalName, _businessDisplayName, _contactEmail, _address, _website, _about, _phone, _displayName, _locale]) {
      c.dispose();
    }
    super.dispose();
  }

  /// WhatsApp rejects the whole registration when the website has no scheme.
  void _normalizeWebsite() {
    final website = normalizeWebsiteUrl(_website.text);
    if (website != _website.text) _website.text = website;
  }

  /// Validates every step up to [step]; on failure moves to the first step
  /// with a problem so the errors are on screen.
  bool _checkThrough(int step) {
    if (step > _gate) _gate = step;
    _normalizeWebsite();
    if (_form.submit()) return true;
    final bad = _validate().entries.where((e) => e.value != null).map((e) => _stepOf[e.key] ?? 0);
    final first = bad.isEmpty ? _stepIndex : bad.reduce((a, b) => a < b ? a : b);
    setState(() => _stepIndex = first);
    return false;
  }

  void _next() {
    if (!_checkThrough(_stepIndex)) return;
    setState(() => _stepIndex = (_stepIndex + 1).clamp(0, 3));
  }

  void _back() => setState(() => _stepIndex = (_stepIndex - 1).clamp(0, 3));

  Future<void> _save({required bool submitForReview}) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    if (!_checkThrough(2)) return;
    _form.saving = true;

    final body = WhatsAppSenderRequest(
      phoneE164: _phone.text.trim(),
      displayName: _displayName.text.trim(),
      defaultLocale: _locale.text.trim(),
      startFlowId: _startFlowId,
      businessProfile: BusinessProfileFields(
        legalName: _legalName.text.trim(),
        displayName: _businessDisplayName.text.trim(),
        contactEmail: _contactEmail.text.trim(),
        address: _address.text.trim(),
        websiteUrl: _website.text.trim(),
        vertical: _vertical!,
        about: _about.text.trim(),
        logoUrl: _logoUrl,
      ),
    );

    final AdminApi api;
    final WhatsAppSenderResponse saved;
    try {
      api = await ref.read(adminApiProvider.future);
      saved = _isNew
          ? await api.createWhatsAppSender(tenant, body)
          : await api.updateWhatsAppSender(tenant, widget.existing!.id, body);
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
      return;
    }

    // Past this point the sender exists, so a follow-up failure is reported
    // with what still needs doing instead of rolling the save back.
    final followUps = <String>[];
    if (_isNew && _profileSlug != null) {
      try {
        await api.createChannelBinding(tenant, _matchEverythingRoute(saved.id));
        ref.invalidate(channelBindingsProvider(tenant));
      } catch (e) {
        followUps.add('the assistant route could not be added (${ApiErrorInfo.from(e).message}) — add it from the sender');
      }
    }
    var submitted = false;
    if (submitForReview) {
      try {
        await api.submitWhatsAppSender(tenant, saved.id, verificationMethod: _verificationMethod);
        submitted = true;
      } catch (e) {
        followUps.add('submitting for review failed (${ApiErrorInfo.from(e).message}) — submit it from the list');
      }
    }

    ref.invalidate(whatsAppSendersProvider(tenant));
    _form.saving = false;
    _form.markClean();
    if (!mounted) return;
    if (followUps.isNotEmpty) {
      showToast(context, '${saved.phoneE164} was saved, but ${followUps.join('; ')}.', error: true);
    } else if (submitted) {
      showToast(context,
          'Submitted for WhatsApp review. If WhatsApp sends a code to the phone, enter it on the sender.');
    } else {
      showToast(context,
          _isNew ? 'Saved ${saved.phoneE164} as a draft. Submit it for WhatsApp review when ready.' : 'Saved ${saved.phoneE164}.');
    }
    Navigator.of(context).pop();
  }

  ChannelBindingRequest _matchEverythingRoute(String senderId) {
    final parts = _profileSlug!.split('@');
    return ChannelBindingRequest(
      endpoint: ChannelEndpointWire(kind: 'WHATSAPP_SENDER', id: senderId),
      profileId: parts[0],
      profileVersion: int.tryParse(parts.length > 1 ? parts[1] : '1') ?? 1,
      routing: RoutingRuleWire.matchAny(),
      sessionPolicy: SessionPolicyWire.defaults(),
      priority: 100,
      status: 'ACTIVE',
    );
  }

  // ---- step bodies ------------------------------------------------------

  Widget _spaced(List<Widget> children) => Padding(
        padding: const EdgeInsets.only(top: 8),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          for (var i = 0; i < children.length; i++) ...[if (i > 0) const SizedBox(height: Gap.fieldGap), children[i]],
        ]),
      );

  Widget _businessProfileStep() => _spaced([
        YTextField(
          label: 'Legal business name',
          wireKey: 'businessProfile.legalName',
          controller: _legalName,
          form: _form,
          name: 'legalName',
          required: true,
          hint: 'e.g. Acme Foods S.R.L.',
          helper: 'Exactly as it appears on your business registration.',
        ),
        YTextField(
          label: 'Business display name',
          wireKey: 'businessProfile.displayName',
          controller: _businessDisplayName,
          form: _form,
          name: 'businessDisplayName',
          required: true,
          hint: 'e.g. Acme Foods',
          helper: 'How your business is known publicly — may differ from the legal name.',
        ),
        YTextField(
          label: 'Contact email',
          wireKey: 'businessProfile.contactEmail',
          controller: _contactEmail,
          form: _form,
          name: 'contactEmail',
          required: true,
          keyboardType: TextInputType.emailAddress,
          hint: 'e.g. ops@acme.example',
          helper: 'WhatsApp uses this to reach you about review status or policy updates.',
        ),
        YSelectField<String>(
          label: 'Business category',
          wireKey: 'businessProfile.vertical',
          form: _form,
          name: 'vertical',
          required: true,
          value: _vertical,
          hint: 'Choose a category',
          helper: 'The category WhatsApp uses to classify your business.',
          options: [for (final v in kMetaBusinessVerticals) YOption(v, businessVerticalLabel(v))],
          onChanged: (v) => setState(() => _vertical = v),
        ),
        YTextField(
          label: 'Business address',
          wireKey: 'businessProfile.address',
          controller: _address,
          form: _form,
          name: 'address',
          optional: true,
          maxLines: 2,
          minLines: 1,
          helper: 'Physical address of your business.',
        ),
        YTextField(
          label: 'Website',
          wireKey: 'businessProfile.websiteUrl',
          controller: _website,
          form: _form,
          name: 'websiteUrl',
          optional: true,
          keyboardType: TextInputType.url,
          hint: 'e.g. https://example.com',
          helper: 'Public website where customers can verify your business. https:// is added if you leave it out.',
        ),
        YTextField(
          label: 'About',
          wireKey: 'businessProfile.about',
          controller: _about,
          form: _form,
          name: 'about',
          optional: true,
          minLines: 2,
          maxLines: 4,
          inputFormatters: [LengthLimitingTextInputFormatter(_maxAbout)],
          helper: 'Short description on your WhatsApp Business profile · ${_about.text.length}/$_maxAbout characters.',
          onChanged: (_) => setState(() {}),
        ),
        SenderLogoPicker(
          url: _logoUrl,
          onChanged: (url) {
            setState(() => _logoUrl = url);
            _form.refresh();
          },
        ),
      ]);

  Widget _phoneStep() => _spaced([
        YTextField(
          label: 'Phone number',
          wireKey: 'phoneE164',
          controller: _phone,
          form: _form,
          name: 'phoneE164',
          required: true,
          mono: true,
          keyboardType: const TextInputType.numberWithOptions(signed: true),
          inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9+]'))],
          prefix: const Icon(Icons.phone_outlined, size: 18),
          hint: 'e.g. +15551234567',
          helper: 'International format: + then the country code and number.',
          onChanged: (_) => setState(() {}),
        ),
        const YBanner(
          tone: YBannerTone.info,
          message: 'Use a number you control that isn’t already on WhatsApp (personal or business). If it is, delete '
              'that WhatsApp account before registering it here.',
        ),
        const YBanner(
          tone: YBannerTone.warning,
          message: 'Each number can be registered to only one workspace across the whole platform.',
        ),
        VerificationMethodSelector(
          form: _form,
          value: _verificationMethod,
          onChanged: (v) => setState(() => _verificationMethod = v),
        ),
      ]);

  Widget _displayNameStep(String tenant) {
    final scheme = Theme.of(context).colorScheme;
    final display = _displayName.text.trim();
    final flows = ref.watch(startFlowsProvider(tenant));
    final flowIds = <String, StartFlowResponse>{};
    for (final f in flows.valueOrNull ?? const <StartFlowResponse>[]) {
      final cur = flowIds[f.id];
      if (cur == null || f.version > cur.version) flowIds[f.id] = f;
    }
    return _spaced([
      YTextField(
        label: 'Public display name',
        wireKey: 'displayName',
        controller: _displayName,
        form: _form,
        name: 'displayName',
        required: true,
        inputFormatters: [LengthLimitingTextInputFormatter(_maxDisplayName)],
        hint: 'e.g. Acme Foods',
        helper: 'Shown to customers next to your messages. WhatsApp reviews it — it must represent your business. '
            '${display.length}/$_maxDisplayName characters.',
        onChanged: (_) => setState(() {}),
      ),
      YTextField(
        label: 'Default locale',
        wireKey: 'defaultLocale',
        controller: _locale,
        form: _form,
        name: 'defaultLocale',
        required: true,
        mono: true,
        hint: 'e.g. es-DO',
        helper: 'Language tag used to pick message templates, e.g. en-US or es-DO.',
      ),
      if (_isNew) _assistantPicker(),
      YResourcePicker(
        label: 'Start flow',
        wireKey: 'startFlowId',
        optional: true,
        value: _startFlowId,
        noneLabel: 'None',
        loading: flows.isLoading,
        resources: [
          for (final f in flowIds.values) YResource(id: f.id, name: f.name, meta: 'v${f.version} · ${humanizeWire(f.status)}'),
        ],
        helper: 'Runs when a customer starts a conversation on this number — e.g. look them up and greet them by name.',
        onChanged: (v) {
          setState(() => _startFlowId = v);
          _form.refresh();
        },
      ),
      Card(
        color: scheme.surfaceContainerHigh,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Preview', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: scheme.onSurfaceVariant)),
            const SizedBox(height: 8),
            Row(children: [
              CircleAvatar(
                backgroundColor: scheme.primary,
                child: Text(display.isNotEmpty ? display[0].toUpperCase() : '?', style: TextStyle(color: scheme.onPrimary)),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(display.isNotEmpty ? display : 'Your display name',
                      style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
                  Text(_phone.text.trim().isEmpty ? 'Phone' : _phone.text.trim(),
                      style: monoStyle(context, size: 12.5, color: scheme.onSurfaceVariant)),
                ]),
              ),
            ]),
          ]),
        ),
      ),
      const YBanner(
        tone: YBannerTone.info,
        title: 'Display name rules (Meta).',
        message: 'Use a real business name or one closely related to it. No generic terms (“Marketing”, “Bot”, '
            '“Test”), celebrity names or profanity. Unusual characters or capitalization may be rejected.',
      ),
    ]);
  }

  Widget _assistantPicker() {
    final tenant = ref.read(currentTenantOrNull);
    final profiles = ref.watch(profilesProvider);
    final list = (profiles.valueOrNull ?? const <ProfileResponse>[]).where((p) => p.tenant == tenant).toList()
      ..sort((a, b) => a.id == b.id ? b.version.compareTo(a.version) : a.id.compareTo(b.id));
    return YResourcePicker(
      label: 'Assistant that answers this number',
      wireKey: 'profileId',
      optional: true,
      value: _profileSlug,
      noneLabel: 'Decide later',
      loading: profiles.isLoading,
      resources: [
        for (final p in list) YResource(id: '${p.id}@${p.version}', name: p.displayName, meta: 'v${p.version}'),
      ],
      helper: list.isEmpty && !profiles.isLoading
          ? 'No assistants in this workspace yet — add a route from the sender later.'
          : 'Every message to this number goes to this assistant. Add finer routes from the sender later.',
      onChanged: (v) {
        setState(() => _profileSlug = v);
        _form.refresh();
      },
    );
  }

  String _assistantLabel() {
    if (_profileSlug == null) return 'Decide later';
    final match = (ref.read(profilesProvider).valueOrNull ?? const []).where((p) => '${p.id}@${p.version}' == _profileSlug);
    return match.isEmpty ? _profileSlug! : match.first.displayName;
  }

  Widget _reviewStep() => Padding(
        padding: const EdgeInsets.only(top: 8),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          _ReviewRow(label: 'Phone number', value: _phone.text.trim(), mono: true),
          _ReviewRow(label: 'Verification code by', value: _verificationMethod == 'voice' ? 'Voice call' : 'SMS'),
          _ReviewRow(label: 'Display name', value: _displayName.text.trim()),
          _ReviewRow(label: 'Default locale', value: _locale.text.trim(), mono: true),
          if (_isNew) _ReviewRow(label: 'Assistant', value: _assistantLabel()),
          _ReviewRow(label: 'Start flow', value: _startFlowId ?? '', placeholder: 'None'),
          const Divider(height: 24),
          _ReviewRow(label: 'Legal business name', value: _legalName.text.trim()),
          _ReviewRow(label: 'Business display name', value: _businessDisplayName.text.trim()),
          _ReviewRow(label: 'Contact email', value: _contactEmail.text.trim()),
          _ReviewRow(label: 'Category', value: _vertical == null ? '' : businessVerticalLabel(_vertical!)),
          if (_address.text.trim().isNotEmpty) _ReviewRow(label: 'Address', value: _address.text.trim()),
          if (_website.text.trim().isNotEmpty) _ReviewRow(label: 'Website', value: _website.text.trim()),
          if (_about.text.trim().isNotEmpty) _ReviewRow(label: 'About', value: _about.text.trim()),
          _ReviewRow(label: 'Profile photo', value: _logoUrl.isEmpty ? '' : 'Uploaded', placeholder: 'None'),
          const SizedBox(height: 12),
          const YBanner(
            tone: YBannerTone.info,
            message: '“Save & submit” sends the sender for WhatsApp review right away. If WhatsApp texts or calls the '
                'phone with a code, enter it on the sender. “Save as draft” keeps it here so you can submit later.',
          ),
        ]),
      );

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    final title = _isNew ? 'Register a WhatsApp sender' : 'Edit ${widget.existing!.phoneE164}';
    if (tenant == null) {
      return Scaffold(
        appBar: AppBar(title: Text(title)),
        body: const Center(child: Text('Select a workspace first.')),
      );
    }
    final canSubmit = ref.watch(whatsAppSubmitEnabledProvider(tenant)).maybeWhen(data: (r) => r, orElse: () => true);
    final onReview = _stepIndex == 3;
    final draftLabel = _isNew ? 'Save as draft' : 'Save changes';
    final String primaryLabel;
    final VoidCallback onPrimary;
    if (!onReview) {
      primaryLabel = 'Next';
      onPrimary = _next;
    } else if (canSubmit) {
      primaryLabel = 'Save & submit for review';
      onPrimary = () => _save(submitForReview: true);
    } else {
      primaryLabel = draftLabel;
      onPrimary = () => _save(submitForReview: false);
    }

    StepState state(int i) => _stepIndex > i ? StepState.complete : StepState.indexed;

    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: onPrimary,
        child: Scaffold(
          appBar: AppBar(title: Text(title)),
          body: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Expanded(
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 760),
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                      if (_restoredDraftAt != null)
                        Padding(
                          padding: const EdgeInsets.only(bottom: Gap.md),
                          child: YBanner(
                            tone: YBannerTone.info,
                            message: 'Restored your unsaved draft.',
                            actions: [TextButton(onPressed: _discardDraft, child: const Text('Discard draft'))],
                          ),
                        ),
                      if (_form.summary.isNotEmpty || _form.formError != null)
                        Padding(
                          padding: const EdgeInsets.only(bottom: Gap.md),
                          child: YErrorSummary(form: _form, action: onReview ? 'saving' : 'continuing'),
                        ),
                      Expanded(
                        child: Stepper(
                          currentStep: _stepIndex,
                          onStepTapped: (i) {
                            if (i > _stepIndex && !_checkThrough(i - 1)) return;
                            setState(() => _stepIndex = i);
                          },
                          controlsBuilder: (context, details) => const SizedBox(height: 8),
                          steps: [
                            Step(title: const Text('Business profile'), isActive: true, state: state(0), content: _businessProfileStep()),
                            Step(title: const Text('Phone number'), isActive: _stepIndex >= 1, state: state(1), content: _phoneStep()),
                            Step(title: const Text('Display name'), isActive: _stepIndex >= 2, state: state(2), content: _displayNameStep(tenant)),
                            Step(title: const Text('Review & save'), isActive: onReview, state: StepState.indexed, content: _reviewStep()),
                          ],
                        ),
                      ),
                    ]),
                  ),
                ),
              ),
            ),
            YActionBar(
              form: _form,
              maxWidth: 760,
              primaryLabel: primaryLabel,
              onPrimary: onPrimary,
              onCancel: () => Navigator.of(context).maybePop(),
              secondary: [
                if (_stepIndex > 0) TextButton(onPressed: _form.saving ? null : _back, child: const Text('Back')),
                if (onReview && canSubmit)
                  OutlinedButton(
                    onPressed: _form.saving ? null : () => _save(submitForReview: false),
                    child: Text(draftLabel),
                  ),
              ],
            ),
          ]),
        ),
      ),
    );
  }
}

class _ReviewRow extends StatelessWidget {
  const _ReviewRow({required this.label, required this.value, this.placeholder, this.mono = false});

  final String label;
  final String value;
  final String? placeholder;
  final bool mono;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final empty = value.isEmpty;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(
          flex: 2,
          child: Text(label, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: scheme.onSurfaceVariant)),
        ),
        const SizedBox(width: 12),
        Expanded(
          flex: 3,
          child: Text(
            empty ? (placeholder ?? '—') : value,
            style: mono && !empty
                ? monoStyle(context, size: 13)
                : TextStyle(fontSize: 14, color: empty ? scheme.onSurfaceVariant : scheme.onSurface),
          ),
        ),
      ]),
    );
  }
}

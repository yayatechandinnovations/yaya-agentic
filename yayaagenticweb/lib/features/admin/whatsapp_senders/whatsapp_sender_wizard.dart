import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/channel_binding.dart';
import '../../../models/admin/whatsapp_sender.dart';
import '../channel_bindings/sender_routes_section.dart' show channelBindingsProvider;
import '../profiles/profiles_screen.dart' show profilesProvider;
import '../shared/admin_shared.dart';
import '../flows/start_flow_picker.dart';
import 'sender_logo_picker.dart';
import 'whatsapp_senders_screen.dart'
    show VerificationMethodSelector, whatsAppSendersProvider, whatsAppSubmitEnabledProvider;

/// 4-step sender registration wizard. Tenants describe their business, pick
/// a phone number, choose a display name and the assistant that answers it,
/// and review. The last step saves the sender and — unless the operator
/// picks "Save as draft" — submits it for WhatsApp review in the same click,
/// routing the number to the chosen assistant so it answers as soon as the
/// sender goes live.
///
/// Optional [existing] turns the wizard into an editor for a DRAFT/FAILED
/// sender. The flow is the same — the operator can step through and revise
/// each field, then save (and resubmit). Routes on an existing sender are
/// managed on its card, so the assistant picker only appears for new ones.
class WhatsAppSenderWizard extends ConsumerStatefulWidget {
  const WhatsAppSenderWizard({super.key, this.existing});

  final WhatsAppSenderResponse? existing;

  @override
  ConsumerState<WhatsAppSenderWizard> createState() =>
      _WhatsAppSenderWizardState();
}

class _WhatsAppSenderWizardState extends ConsumerState<WhatsAppSenderWizard> {
  int _stepIndex = 0;
  bool _saving = false;

  // ---- step 1 — business profile -----------------------------------------
  late final TextEditingController _legalNameCtrl;
  late final TextEditingController _businessDisplayNameCtrl;
  late final TextEditingController _contactEmailCtrl;
  late final TextEditingController _addressCtrl;
  late final TextEditingController _websiteUrlCtrl;
  late final TextEditingController _aboutCtrl;
  String? _vertical;
  late String _logoUrl;

  // ---- step 2 — phone ----------------------------------------------------
  late final TextEditingController _phoneCtrl;
  String _verificationMethod = 'sms';

  // ---- step 3 — display name --------------------------------------------
  late final TextEditingController _publicDisplayNameCtrl;
  late final TextEditingController _localeCtrl;
  String? _startFlowId;
  String? _profileSlug; // "<id>@<version>"

  bool get _isNew => widget.existing == null;

  @override
  void initState() {
    super.initState();
    final existing = widget.existing;
    final bp = existing?.businessProfile ?? BusinessProfileFields.empty();
    _legalNameCtrl = TextEditingController(text: bp.legalName);
    _businessDisplayNameCtrl = TextEditingController(
        text: bp.displayName.isEmpty ? existing?.displayName ?? '' : bp.displayName);
    _contactEmailCtrl = TextEditingController(text: bp.contactEmail);
    _addressCtrl = TextEditingController(text: bp.address);
    _websiteUrlCtrl = TextEditingController(text: bp.websiteUrl);
    _aboutCtrl = TextEditingController(text: bp.about);
    _vertical = normalizeBusinessVertical(bp.vertical);
    _logoUrl = bp.logoUrl;

    _phoneCtrl = TextEditingController(text: existing?.phoneE164 ?? '');
    _publicDisplayNameCtrl =
        TextEditingController(text: existing?.displayName ?? '');
    _localeCtrl = TextEditingController(text: existing?.defaultLocale ?? 'en-US');
    _startFlowId = existing?.startFlowId;
  }

  @override
  void dispose() {
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

  // ---- per-step validation ----------------------------------------------

  String? _validateBusinessProfile() {
    final website = normalizeWebsiteUrl(_websiteUrlCtrl.text);
    if (website != _websiteUrlCtrl.text) _websiteUrlCtrl.text = website;
    if (_legalNameCtrl.text.trim().isEmpty) {
      return 'Legal business name is required.';
    }
    if (_businessDisplayNameCtrl.text.trim().isEmpty) {
      return 'Business display name is required.';
    }
    if (_contactEmailCtrl.text.trim().isEmpty ||
        !_contactEmailCtrl.text.contains('@')) {
      return 'A valid contact email is required.';
    }
    if (_vertical == null) {
      return 'Select the business vertical that best fits your business.';
    }
    return websiteUrlError(website);
  }

  String? _validatePhone() {
    final phone = _phoneCtrl.text.trim();
    if (phone.isEmpty) return 'Phone number is required.';
    if (!RegExp(r'^\+[1-9][0-9]{6,17}$').hasMatch(phone)) {
      return 'Phone number must be in E.164 format, e.g. +15551234567.';
    }
    return null;
  }

  String? _validateDisplayName() {
    final display = _publicDisplayNameCtrl.text.trim();
    if (display.isEmpty) return 'Display name is required.';
    if (display.length > 128) {
      return 'Display name must be 128 characters or fewer.';
    }
    final locale = _localeCtrl.text.trim();
    if (locale.isEmpty) return 'Default locale is required.';
    if (!RegExp(r'^[a-z]{2}(-[A-Z]{2})?$').hasMatch(locale)) {
      return 'Locale must be a BCP-47 tag, e.g. "en-US", "es-DO".';
    }
    return null;
  }

  String? _validateCurrentStep() {
    switch (_stepIndex) {
      case 0:
        return _validateBusinessProfile();
      case 1:
        return _validatePhone();
      case 2:
        return _validateDisplayName();
      default:
        return null;
    }
  }

  void _next() {
    final error = _validateCurrentStep();
    if (error != null) {
      showSnack(context, error, error: true);
      return;
    }
    setState(() => _stepIndex = (_stepIndex + 1).clamp(0, 3));
  }

  void _back() {
    setState(() => _stepIndex = (_stepIndex - 1).clamp(0, 3));
  }

  Future<void> _save({required bool submitForReview}) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) {
      showSnack(context, 'Select a workspace first.', error: true);
      return;
    }
    // Final validation pass — defends against an operator who skipped via
    // a future "edit a step from review" affordance.
    for (var i = 0; i <= 2; i++) {
      _stepIndex = i;
      final error = _validateCurrentStep();
      if (error != null) {
        setState(() => _stepIndex = i);
        showSnack(context, error, error: true);
        return;
      }
    }
    setState(() {
      _stepIndex = 3;
      _saving = true;
    });

    final body = WhatsAppSenderRequest(
      phoneE164: _phoneCtrl.text.trim(),
      displayName: _publicDisplayNameCtrl.text.trim(),
      defaultLocale: _localeCtrl.text.trim(),
      startFlowId: _startFlowId,
      businessProfile: BusinessProfileFields(
        legalName: _legalNameCtrl.text.trim(),
        displayName: _businessDisplayNameCtrl.text.trim(),
        contactEmail: _contactEmailCtrl.text.trim(),
        address: _addressCtrl.text.trim(),
        websiteUrl: _websiteUrlCtrl.text.trim(),
        vertical: _vertical!,
        about: _aboutCtrl.text.trim(),
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
      if (mounted) {
        showSnack(context, formatError(e), error: true);
        setState(() => _saving = false);
      }
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
        followUps.add('the assistant route could not be added (${formatError(e)}) — add it from the sender card');
      }
    }
    var submitted = false;
    if (submitForReview) {
      try {
        await api.submitWhatsAppSender(tenant, saved.id,
            verificationMethod: _verificationMethod);
        submitted = true;
      } catch (e) {
        followUps.add('submitting for review failed (${formatError(e)}) — submit it from the list');
      }
    }

    ref.invalidate(whatsAppSendersProvider(tenant));
    if (!mounted) return;
    Navigator.of(context).pop();
    if (followUps.isNotEmpty) {
      showSnack(context, 'Sender saved, but ${followUps.join('; ')}.', error: true);
    } else if (submitted) {
      showSnack(context,
          'Submitted for WhatsApp review. If WhatsApp sends a code to the phone, enter it on the sender card.');
    } else {
      showSnack(context,
          _isNew ? 'Sender saved as draft. Submit it for WhatsApp review when ready.' : 'Sender updated.');
    }
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

  // The Stepper clips each step's content at its top edge, and an outlined
  // field paints its floating label above its own box — without this gap
  // the first field's label is sliced in half.
  Widget _stepBody(Widget child) =>
      Padding(padding: const EdgeInsets.only(top: 12), child: child);

  Widget _businessProfileStep() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          controller: _legalNameCtrl,
          decoration: const InputDecoration(
            labelText: 'Legal business name *',
            helperText: 'Exactly as it appears on your business registration.',
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _businessDisplayNameCtrl,
          decoration: const InputDecoration(
            labelText: 'Business display name *',
            helperText:
                'How your business is known publicly — may differ from the legal name.',
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _contactEmailCtrl,
          keyboardType: TextInputType.emailAddress,
          decoration: const InputDecoration(
            labelText: 'Contact email *',
            helperText:
                'WhatsApp uses this to reach you about review status or policy updates.',
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _addressCtrl,
          decoration: const InputDecoration(
            labelText: 'Business address',
            helperText: 'Physical address of your business.',
          ),
          maxLines: 2,
          minLines: 1,
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _websiteUrlCtrl,
          keyboardType: TextInputType.url,
          decoration: const InputDecoration(
            labelText: 'Website URL',
            hintText: 'https://example.com',
            helperText: 'Public website where customers can verify your business.',
          ),
        ),
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue: _vertical,
          isExpanded: true,
          decoration: const InputDecoration(
            labelText: 'Business vertical *',
            helperText: 'The category WhatsApp uses to classify your business.',
          ),
          items: kMetaBusinessVerticals
              .map((v) => DropdownMenuItem(value: v, child: Text(businessVerticalLabel(v))))
              .toList(),
          onChanged: (v) => setState(() => _vertical = v),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _aboutCtrl,
          decoration: const InputDecoration(
            labelText: 'About',
            helperText:
                'Short description shown on your WhatsApp Business profile.',
          ),
          maxLength: 139,
          minLines: 2,
          maxLines: 4,
        ),
        const SizedBox(height: 4),
        SenderLogoPicker(
          url: _logoUrl,
          onChanged: (url) => setState(() => _logoUrl = url),
        ),
      ],
    );
  }

  Widget _phoneStep() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          controller: _phoneCtrl,
          keyboardType: const TextInputType.numberWithOptions(signed: true),
          inputFormatters: [
            FilteringTextInputFormatter.allow(RegExp(r'[0-9+]')),
          ],
          decoration: const InputDecoration(
            labelText: 'Phone number (E.164) *',
            helperText:
                'International format, including +country code. e.g. +15551234567.',
            prefixIcon: Icon(Icons.phone_outlined),
          ),
        ),
        const SizedBox(height: 16),
        const _InfoNote(
          icon: Icons.info_outline,
          text:
              'The phone number must be one you control and that is not already in use on '
              'WhatsApp (personal or business). If it is, you must delete the existing '
              'WhatsApp account on that number before registering it here.',
        ),
        const SizedBox(height: 8),
        const _InfoNote(
          icon: Icons.warning_amber_outlined,
          text:
              'Phone numbers are global — each number can be registered to only one '
              'workspace across the entire platform.',
        ),
        const SizedBox(height: 16),
        VerificationMethodSelector(
          value: _verificationMethod,
          onChanged: (v) => setState(() => _verificationMethod = v),
        ),
      ],
    );
  }

  Widget _displayNameStep() {
    final display = _publicDisplayNameCtrl.text.trim();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          controller: _publicDisplayNameCtrl,
          onChanged: (_) => setState(() {}),
          maxLength: 128,
          decoration: const InputDecoration(
            labelText: 'Public display name *',
            helperText:
                'Shown to customers next to your messages. WhatsApp reviews this for '
                'policy compliance — it must accurately represent your business.',
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _localeCtrl,
          decoration: const InputDecoration(
            labelText: 'Default locale *',
            helperText:
                'BCP-47 tag, e.g. "en-US", "es-DO". Used for template selection.',
          ),
        ),
        const SizedBox(height: 12),
        if (_isNew) ...[
          _assistantPicker(),
          const SizedBox(height: 12),
        ],
        StartFlowPicker(
          tenant: ref.read(currentTenantOrNull) ?? 'default',
          value: _startFlowId,
          onChanged: (v) => setState(() => _startFlowId = v),
        ),
        const SizedBox(height: 24),
        Card(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Preview',
                    style: Theme.of(context).textTheme.labelSmall?.copyWith(
                        color: Theme.of(context).colorScheme.outline)),
                const SizedBox(height: 8),
                Row(
                  children: [
                    CircleAvatar(
                      backgroundColor: Theme.of(context).colorScheme.primary,
                      child: Text(
                        display.isNotEmpty ? display[0].toUpperCase() : '?',
                        style: TextStyle(
                            color: Theme.of(context).colorScheme.onPrimary),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            display.isNotEmpty ? display : 'Your display name',
                            style: Theme.of(context).textTheme.titleSmall,
                          ),
                          Text(
                            _phoneCtrl.text.trim().isEmpty
                                ? 'Phone'
                                : _phoneCtrl.text.trim(),
                            style: TextStyle(
                                fontSize: 12,
                                color: Theme.of(context).colorScheme.outline),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 8),
        const _InfoNote(
          icon: Icons.policy_outlined,
          text:
              'Display name rules (Meta): the name must be a real business name or a '
              'name closely related to it. No generic terms ("Marketing", "Bot", "Test"), '
              'no celebrity names, no profanity. Names with special characters or unusual '
              'capitalization may be rejected.',
        ),
      ],
    );
  }

  Widget _assistantPicker() {
    final tenant = ref.read(currentTenantOrNull);
    return ref.watch(profilesProvider).when(
          loading: () => const LinearProgressIndicator(),
          error: (e, _) => Text(formatError(e)),
          data: (all) {
            final profiles = all.where((p) => p.tenant == tenant).toList();
            return DropdownButtonFormField<String>(
              initialValue: _profileSlug,
              isExpanded: true,
              decoration: InputDecoration(
                labelText: 'Assistant that answers this number',
                helperText: profiles.isEmpty
                    ? 'No assistants in this workspace yet — you can add a route from the sender card later.'
                    : 'Every message to this number goes to this assistant. Optional — '
                        'add finer routes from the sender card later.',
                helperMaxLines: 3,
              ),
              items: [
                const DropdownMenuItem<String>(value: null, child: Text('Decide later')),
                for (final p in profiles)
                  DropdownMenuItem(
                    value: '${p.id}@${p.version}',
                    child: Text('${p.displayName} (${p.id}@${p.version})',
                        overflow: TextOverflow.ellipsis),
                  ),
              ],
              onChanged: (v) => setState(() => _profileSlug = v),
            );
          },
        );
  }

  String _assistantLabel() {
    if (_profileSlug == null) return 'Decide later';
    final match = (ref.read(profilesProvider).valueOrNull ?? const [])
        .where((p) => '${p.id}@${p.version}' == _profileSlug);
    return match.isEmpty ? _profileSlug! : match.first.displayName;
  }

  Widget _reviewStep() {
    if (_saving) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(24),
          child: CircularProgressIndicator(),
        ),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _ReviewRow(label: 'Phone number', value: _phoneCtrl.text.trim()),
        _ReviewRow(
            label: 'Verification code by',
            value: _verificationMethod == 'voice' ? 'Voice call' : 'SMS'),
        _ReviewRow(label: 'Display name', value: _publicDisplayNameCtrl.text.trim()),
        _ReviewRow(label: 'Default locale', value: _localeCtrl.text.trim()),
        if (_isNew) _ReviewRow(label: 'Assistant', value: _assistantLabel()),
        const Divider(height: 24),
        _ReviewRow(label: 'Legal business name', value: _legalNameCtrl.text.trim()),
        _ReviewRow(
            label: 'Business display name', value: _businessDisplayNameCtrl.text.trim()),
        _ReviewRow(label: 'Contact email', value: _contactEmailCtrl.text.trim()),
        _ReviewRow(
            label: 'Vertical',
            value: _vertical == null ? '' : businessVerticalLabel(_vertical!),
            placeholder: '—'),
        if (_addressCtrl.text.trim().isNotEmpty)
          _ReviewRow(label: 'Address', value: _addressCtrl.text.trim()),
        if (_websiteUrlCtrl.text.trim().isNotEmpty)
          _ReviewRow(label: 'Website', value: _websiteUrlCtrl.text.trim()),
        if (_aboutCtrl.text.trim().isNotEmpty)
          _ReviewRow(label: 'About', value: _aboutCtrl.text.trim()),
        _ReviewRow(
            label: 'Profile photo',
            value: _logoUrl.isEmpty ? '' : 'Uploaded',
            placeholder: 'None'),
        const SizedBox(height: 12),
        const _InfoNote(
          icon: Icons.send_outlined,
          text:
              '"Save & submit" sends the sender for WhatsApp review right away. If WhatsApp '
              'texts or calls the phone with a code, enter it on the sender card. "Save as '
              'draft" keeps it here so you can submit later.',
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Register a WhatsApp sender')),
        body: const Center(child: Text('Select a workspace first.')),
      );
    }
    final canSubmit = ref.watch(whatsAppSubmitEnabledProvider(tenant))
        .maybeWhen(data: (ready) => ready, orElse: () => true);

    return Scaffold(
      appBar: AppBar(
        title: Text(widget.existing == null
            ? 'Register a WhatsApp sender'
            : 'Edit WhatsApp sender'),
      ),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 720),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
            child: Stepper(
              currentStep: _stepIndex,
              onStepTapped: (i) {
                // Forward-tap requires the current step to validate first.
                if (i > _stepIndex) {
                  for (var step = _stepIndex; step < i; step++) {
                    final saved = _stepIndex;
                    _stepIndex = step;
                    final err = _validateCurrentStep();
                    _stepIndex = saved;
                    if (err != null) {
                      showSnack(context, err, error: true);
                      return;
                    }
                  }
                }
                setState(() => _stepIndex = i);
              },
              controlsBuilder: (context, details) {
                return Padding(
                  padding: const EdgeInsets.only(top: 16),
                  child: Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      if (_stepIndex < 3)
                        FilledButton(
                          onPressed: _saving ? null : _next,
                          child: const Text('Next'),
                        )
                      else ...[
                        if (canSubmit)
                          FilledButton.icon(
                            onPressed: _saving
                                ? null
                                : () => _save(submitForReview: true),
                            icon: const Icon(Icons.send_outlined),
                            label: const Text('Save & submit for review'),
                          ),
                        OutlinedButton.icon(
                          onPressed: _saving
                              ? null
                              : () => _save(submitForReview: false),
                          icon: const Icon(Icons.save_outlined),
                          label: Text(_isNew ? 'Save as draft' : 'Save changes'),
                        ),
                      ],
                      if (_stepIndex > 0)
                        TextButton(
                          onPressed: _saving ? null : _back,
                          child: const Text('Back'),
                        ),
                    ],
                  ),
                );
              },
              steps: [
                Step(
                  title: const Text('Business profile'),
                  isActive: _stepIndex >= 0,
                  state: _stepIndex > 0 ? StepState.complete : StepState.indexed,
                  content: _stepBody(_businessProfileStep()),
                ),
                Step(
                  title: const Text('Phone number'),
                  isActive: _stepIndex >= 1,
                  state: _stepIndex > 1 ? StepState.complete : StepState.indexed,
                  content: _stepBody(_phoneStep()),
                ),
                Step(
                  title: const Text('Display name'),
                  isActive: _stepIndex >= 2,
                  state: _stepIndex > 2 ? StepState.complete : StepState.indexed,
                  content: _stepBody(_displayNameStep()),
                ),
                Step(
                  title: const Text('Review & save'),
                  isActive: _stepIndex >= 3,
                  state: StepState.indexed,
                  content: _stepBody(_reviewStep()),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

}

class _ReviewRow extends StatelessWidget {
  const _ReviewRow({required this.label, required this.value, this.placeholder});

  final String label;
  final String value;
  final String? placeholder;

  @override
  Widget build(BuildContext context) {
    final outline = Theme.of(context).colorScheme.outline;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            flex: 2,
            child: Text(label,
                style: Theme.of(context).textTheme.labelSmall?.copyWith(color: outline)),
          ),
          const SizedBox(width: 12),
          Expanded(
            flex: 3,
            child: Text(
              value.isEmpty ? (placeholder ?? '—') : value,
              style: const TextStyle(fontSize: 14),
            ),
          ),
        ],
      ),
    );
  }
}

class _InfoNote extends StatelessWidget {
  const _InfoNote({required this.icon, required this.text});
  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 18, color: scheme.outline),
          const SizedBox(width: 8),
          Expanded(
            child: Text(text,
                style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant)),
          ),
        ],
      ),
    );
  }
}

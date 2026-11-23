import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/network_settings.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../shared/admin_shared.dart' show TenantScopedEmptyState;

final networkSettingsProvider = FutureProvider.family<NetworkSettings, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.networkSettings);
  final api = await ref.watch(adminApiProvider.future);
  return api.getNetworkSettings(tenantId);
});

const _labels = {
  'enabled': 'Network chat',
  'playEnabled': 'Play',
  'claimEnabled': 'Claim',
  'hubBaseUrl': 'Hub URL',
  'hubCredentialRef': 'Hub credential',
};

/// Settings → Network chat (Corillo). One page turns `player_network_v1` on
/// and toggles play/claim at runtime — the master switch seeds the profile and
/// auto-provisions the chat front door; play/claim take effect on the next turn
/// with no restart.
class NetworkSettingsScreen extends ConsumerWidget {
  const NetworkSettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) return const TenantScopedEmptyState(resourceLabel: 'Network settings');
    final settings = ref.watch(networkSettingsProvider(tenant));
    if (!settings.hasValue) {
      return YPage(
        title: 'Network chat (Corillo)',
        child: settings.hasError
            ? YErrorState(
                error: settings.error!,
                what: 'network settings',
                onRetry: () => ref.invalidate(networkSettingsProvider(tenant)),
              )
            : const SingleChildScrollView(child: YSkeletonRows(count: 4)),
      );
    }
    return _NetworkSettingsForm(key: ValueKey(tenant), tenant: tenant);
  }
}

class _NetworkSettingsForm extends ConsumerStatefulWidget {
  const _NetworkSettingsForm({super.key, required this.tenant});
  final String tenant;

  @override
  ConsumerState<_NetworkSettingsForm> createState() => _NetworkSettingsFormState();
}

class _NetworkSettingsFormState extends ConsumerState<_NetworkSettingsForm> {
  final _hub = TextEditingController();
  final _cred = TextEditingController();
  bool _enabled = false;
  bool _play = false;
  bool _claim = false;
  late final YFormController _form;

  /// The server copy the form shows, so a refetch only refills an untouched form.
  NetworkSettings? _base;

  String get _tenant => widget.tenant;

  @override
  void initState() {
    super.initState();
    _fill(ref.read(networkSettingsProvider(_tenant)).requireValue);
    _form = YFormController(
      snapshot: () => {
        'enabled': _enabled,
        'playEnabled': _play,
        'claimEnabled': _claim,
        'hubBaseUrl': _hub.text.trim(),
        'hubCredentialRef': _cred.text.trim(),
      },
      validate: () => {
        'hubBaseUrl': Validators.httpUrl(_hub.text, optional: true),
        'hubCredentialRef': RegExp(r'\s').hasMatch(_cred.text.trim()) ? 'Use the variable’s name, without spaces.' : null,
      },
      labels: _labels,
      draftKey: 'yaya.draft.networkSettings.$_tenant.settings',
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      final v = d.values;
      _enabled = (v['enabled'] as bool?) ?? _enabled;
      _play = (v['playEnabled'] as bool?) ?? _play;
      _claim = (v['claimEnabled'] as bool?) ?? _claim;
      _hub.text = (v['hubBaseUrl'] as String?) ?? _hub.text;
      _cred.text = (v['hubCredentialRef'] as String?) ?? _cred.text;
      _form.refresh();
    });
  }

  void _fill(NetworkSettings s) {
    _base = s;
    _enabled = s.enabled;
    _play = s.playEnabled;
    _claim = s.claimEnabled;
    _hub.text = s.hubBaseUrl ?? '';
    _cred.text = s.hubCredentialRef ?? '';
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    _hub.dispose();
    _cred.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    if (_base?.enabled == true && !_enabled) {
      final ok = await confirmAction(
        context,
        title: 'Turn off Network chat?',
        message: 'The chat front door${_base?.entryPointHandle == null ? '' : ' @${_base!.entryPointHandle}'} is '
            'paused right away — players who open it can’t start a conversation until you turn it back on.',
        confirmLabel: 'Turn off',
        destructive: true,
      );
      if (!ok) return;
    }
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final updated = await api.updateNetworkSettings(
        _tenant,
        NetworkSettings(
          enabled: _enabled,
          playEnabled: _play,
          claimEnabled: _claim,
          hubBaseUrl: _hub.text.trim(),
          hubCredentialRef: _cred.text.trim(),
        ),
      );
      setState(() => _fill(updated));
      _form.saving = false;
      _form.markClean();
      ref.invalidate(networkSettingsProvider(_tenant));
      if (mounted) showToast(context, 'Network settings saved');
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  void _discard() {
    _form.clearDraft();
    setState(() => _fill(ref.read(networkSettingsProvider(_tenant)).valueOrNull ?? _base!));
    _form.markClean();
  }

  @override
  Widget build(BuildContext context) {
    ref.listen<AsyncValue<NetworkSettings>>(networkSettingsProvider(_tenant), (_, next) {
      final s = next.valueOrNull;
      if (s == null || _form.isDirty || _form.saving) return;
      setState(() => _fill(s));
      _form.markClean();
    });
    final status = _base!;
    final scheme = Theme.of(context).colorScheme;
    const offReason = 'Turn on Network chat first.';

    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Expanded(
            child: YPage(
              title: 'Network chat (Corillo)',
              subtitle: 'The player_network_v1 assistant. Turning it on seeds the profile and opens the chat front door; '
                  'Play and Claim take effect on the next message, no restart.',
              maxWidth: 880,
              scrollable: true,
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                if (_form.summary.isNotEmpty || _form.formError != null) ...[
                  YErrorSummary(form: _form),
                  const SizedBox(height: Gap.lg),
                ],
                YFormSection(
                  title: 'Features',
                  children: [
                    YSwitchField(
                      title: 'Enabled',
                      wireKey: 'enabled',
                      description: 'Master switch. On: seeds the profile and opens the chat front door. Off: pauses '
                          'the front door.',
                      value: _enabled,
                      onChanged: (v) {
                        setState(() => _enabled = v);
                        _form.refresh();
                      },
                    ),
                    YSwitchField(
                      title: 'Play',
                      wireKey: 'playEnabled',
                      description: 'Players can get quotes, confirm plays and link their account.',
                      value: _play,
                      disabledReason: _enabled ? null : offReason,
                      onChanged: (v) {
                        setState(() => _play = v);
                        _form.refresh();
                      },
                    ),
                    YSwitchField(
                      title: 'Claim',
                      wireKey: 'claimEnabled',
                      description: 'Players can claim prizes to their wallet.',
                      value: _claim,
                      disabledReason: _enabled ? null : offReason,
                      onChanged: (v) {
                        setState(() => _claim = v);
                        _form.refresh();
                      },
                    ),
                  ],
                ),
                const SizedBox(height: Gap.lg),
                YFormSection(
                  title: 'Hub connection',
                  description: 'Where the assistant sends plays and claims.',
                  children: [
                    YTextField(
                      label: 'Hub URL',
                      wireKey: 'hubBaseUrl',
                      controller: _hub,
                      form: _form,
                      name: 'hubBaseUrl',
                      optional: true,
                      keyboardType: TextInputType.url,
                      hint: 'e.g. https://hub.smartlottery.example',
                      helper: 'Leave blank to use the platform default.',
                    ),
                    YTextField(
                      label: 'Hub credential',
                      wireKey: 'hubCredentialRef',
                      controller: _cred,
                      form: _form,
                      name: 'hubCredentialRef',
                      optional: true,
                      mono: true,
                      inputFormatters: [FilteringTextInputFormatter.deny(RegExp(r'\s'))],
                      hint: 'e.g. CORILLO_HUB_SECRET',
                      helper: 'The name of the server environment variable that holds the X-Agent-Auth secret — not '
                          'the secret itself. Blank uses the platform default.',
                    ),
                  ],
                ),
                if (status.entryPointHandle != null) ...[
                  const SizedBox(height: Gap.lg),
                  YFormSection(
                    title: 'Chat front door',
                    description: 'Provisioned automatically when Network chat is on.',
                    trailing: YBadge(
                      status.entryPointActive ? 'Active' : 'Paused',
                      tone: status.entryPointActive ? YTone.success : YTone.info,
                      icon: status.entryPointActive ? Icons.check_circle_outline : Icons.pause_circle_outline,
                    ),
                    children: [
                      Row(children: [
                        Expanded(
                          child: SelectableText(
                            status.chatUrl ?? '@${status.entryPointHandle}',
                            style: monoStyle(context, size: 13, color: scheme.onSurface),
                          ),
                        ),
                        IconButton(
                          tooltip: 'Copy link',
                          icon: const Icon(Icons.copy, size: 18),
                          onPressed: () {
                            Clipboard.setData(ClipboardData(text: status.chatUrl ?? status.entryPointHandle!));
                            showToast(context, 'Link copied');
                          },
                        ),
                      ]),
                    ],
                  ),
                ],
              ]),
            ),
          ),
          YActionBar(
            form: _form,
            maxWidth: 880,
            primaryLabel: 'Save settings',
            onPrimary: _save,
            onCancel: _form.isDirty ? _discard : null,
            cancelLabel: 'Discard changes',
          ),
        ]),
      ),
    );
  }
}

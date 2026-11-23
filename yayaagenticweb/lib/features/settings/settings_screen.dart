import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../api/api_config.dart';
import '../../ui/data/live_async.dart';
import '../../ui/data/y_page.dart';
import '../../ui/feedback/toast.dart';
import '../../ui/form/unsaved_guard.dart';
import '../../ui/form/y_fields.dart';
import '../../ui/form/y_form_controller.dart';
import '../../ui/form/y_form_layout.dart';
import '../../ui/theme.dart';
import '../../ui/tokens.dart';

const _labels = {'baseUrl': 'Backend URL'};

/// Per-browser console settings: how it looks and which backend it talks to.
class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cfg = ref.watch(apiConfigProvider);
    return YPage(
      title: 'Settings',
      subtitle: 'Settings for this browser only. They aren’t shared with teammates.',
      maxWidth: 880,
      child: LiveAsyncView<ApiConfig>(
        value: cfg,
        what: 'settings',
        onRetry: () => ref.invalidate(apiConfigProvider),
        data: (context, config) => _SettingsForm(config: config),
      ),
    );
  }
}

class _SettingsForm extends ConsumerStatefulWidget {
  const _SettingsForm({required this.config});
  final ApiConfig config;

  @override
  ConsumerState<_SettingsForm> createState() => _SettingsFormState();
}

class _SettingsFormState extends ConsumerState<_SettingsForm> {
  final _baseUrl = TextEditingController();
  late final YFormController _form;

  @override
  void initState() {
    super.initState();
    _baseUrl.text = widget.config.baseUrl;
    _form = YFormController(
      snapshot: () => {'baseUrl': _baseUrl.text.trim()},
      validate: () => {'baseUrl': Validators.httpUrl(_baseUrl.text)},
      labels: _labels,
    );
    _form.addListener(_rebuild);
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    _baseUrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    _form.saving = true;
    try {
      await setBackendBaseUrl(ref, _baseUrl.text.trim());
      _form.saving = false;
      _form.markClean();
      if (mounted) showToast(context, 'Settings saved. The console now talks to ${_baseUrl.text.trim()}.');
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  void _resetToDefault() {
    _baseUrl.text = ApiConfig.defaultBaseUrl;
    _form.changed('baseUrl');
  }

  @override
  Widget build(BuildContext context) {
    final mode = ref.watch(themeModeProvider);
    final isDefault = _baseUrl.text.trim() == ApiConfig.defaultBaseUrl;
    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Expanded(
            child: ListView(
              padding: const EdgeInsets.only(bottom: Gap.xl),
              children: [
                if (_form.summary.isNotEmpty || _form.formError != null) ...[
                  YErrorSummary(form: _form, action: 'saving settings'),
                  const SizedBox(height: Gap.lg),
                ],
                YFormSection(
                  title: 'Appearance',
                  description: 'Applies immediately on this browser.',
                  children: [
                    YEnumCards<ThemeMode>(
                      label: 'Theme',
                      horizontal: true,
                      value: mode,
                      onChanged: (m) => ref.read(themeModeProvider.notifier).set(m),
                      options: const [
                        YEnumOption(ThemeMode.system, 'System', description: 'Follow your operating system’s light or dark setting.'),
                        YEnumOption(ThemeMode.light, 'Light', description: 'Always light, whatever the system uses.'),
                        YEnumOption(ThemeMode.dark, 'Dark', description: 'Always dark, whatever the system uses.'),
                      ],
                    ),
                  ],
                ),
                const SizedBox(height: Gap.lg),
                YFormSection(
                  title: 'Backend',
                  description: 'Where the yaya-agentic Spring Boot backend is listening. Changing it signs you in against that server.',
                  children: [
                    YTextField(
                      label: 'Backend URL',
                      controller: _baseUrl,
                      form: _form,
                      name: 'baseUrl',
                      required: true,
                      mono: true,
                      keyboardType: TextInputType.url,
                      hint: 'e.g. https://agentic.example.com',
                      helper: 'Full URL including https://. The default for this build is ${ApiConfig.defaultBaseUrl}.',
                      onSubmitted: (_) => _save(),
                    ),
                    Align(
                      alignment: Alignment.centerLeft,
                      child: OutlinedButton.icon(
                        onPressed: isDefault ? null : _resetToDefault,
                        icon: const Icon(Icons.restart_alt, size: 18),
                        label: const Text('Reset to default'),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: Gap.lg),
                YFormSection(
                  title: 'API keys',
                  description: 'Keys that let scripts and services call the admin API on behalf of a workspace.',
                  children: [
                    Align(
                      alignment: Alignment.centerLeft,
                      child: OutlinedButton.icon(
                        onPressed: () => context.go('/settings/api-keys'),
                        icon: const Icon(Icons.key_outlined, size: 18),
                        label: const Text('Manage API keys'),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          YActionBar(
            form: _form,
            primaryLabel: 'Save settings',
            onPrimary: _save,
            onCancel: _form.isDirty
                ? () {
                    _baseUrl.text = widget.config.baseUrl;
                    _form.markClean();
                  }
                : null,
            cancelLabel: 'Discard changes',
          ),
        ]),
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/auth_binding.dart';
import '../../../models/admin/capability.dart';
import '../../../models/admin/profile.dart';
import '../capabilities/capabilities_screen.dart' show toolsProvider;
import '../flows/start_flow_picker.dart';
import '../product_catalogs/product_catalogs_screen.dart' show productCatalogsProvider;
import '../shared/admin_shared.dart';
import 'clone_profile_wizard.dart';

/// Empty list when no tenant is selected — the scoped screen renders a
/// "register a tenant first" banner via [TenantScopedEmptyState] instead
/// of dispatching against `default` and confusing the operator.
final profilesProvider = FutureProvider<List<ProfileResponse>>((ref) async {
  final tenant = ref.watch(currentTenantOrNull);
  if (tenant == null) return const [];
  final api = await ref.watch(adminApiProvider.future);
  return api.listProfiles(tenant: tenant);
});

final capabilitiesProvider = FutureProvider<List<CapabilityResponse>>((ref) async {
  final tenant = ref.watch(currentTenantOrNull);
  if (tenant == null) return const [];
  final api = await ref.watch(adminApiProvider.future);
  return api.listCapabilities(tenant: tenant);
});

final authBindingsProvider = FutureProvider<List<AuthBindingResponse>>((ref) async {
  final tenant = ref.watch(currentTenantOrNull);
  if (tenant == null) return const [];
  final api = await ref.watch(adminApiProvider.future);
  return api.listAuthBindings(tenant: tenant);
});

class ProfilesScreen extends ConsumerStatefulWidget {
  const ProfilesScreen({super.key});
  @override
  ConsumerState<ProfilesScreen> createState() => _ProfilesScreenState();
}

class _ProfilesScreenState extends ConsumerState<ProfilesScreen> {
  final _idCtrl = TextEditingController();
  final _nameCtrl = TextEditingController();
  final _introCtrl = TextEditingController();
  final _promptCtrl = TextEditingController();
  final _capsSelected = <String>{};
  String? _authBinding;
  String _language = 'en';
  String? _defaultStartFlowId;
  bool _saving = false;
  int? _cloningFromVersion;

  // Commerce block (nullable on the profile): turns this profile into a store.
  bool _commerceEnabled = false;
  final _commerceCatalogs = <String>{};
  final _commerceCurrencyCtrl = TextEditingController(text: 'USD');
  String? _commerceCheckoutTool;

  /// Languages we surface in the dropdown. The backend accepts any BCP 47
  /// tag — operators with niche needs can extend the dropdown later. Until
  /// then, two covers 99% of demo use.
  static const _languageOptions = {
    'en': 'English',
    'es': 'Spanish',
  };

  void _cloneEditFrom(ProfileResponse p) {
    setState(() {
      _idCtrl.text = p.id;
      _nameCtrl.text = p.displayName;
      _introCtrl.text = p.introOneLiner;
      _promptCtrl.text = p.systemPromptFragment;
      _capsSelected
        ..clear()
        ..addAll(p.capabilities);
      _authBinding = p.authBindingId;
      _language =
          _languageOptions.containsKey(p.language) ? p.language : 'en';
      _defaultStartFlowId = p.defaultStartFlowId;
      _loadCommerce(p.commerce);
      _cloningFromVersion = p.version;
    });
    showSnack(context,
        'Cloned ${p.id}@${p.version} → will save as v${p.version + 1}');
  }

  void _cancelClone() {
    setState(() {
      _cloningFromVersion = null;
      _idCtrl.clear();
      _nameCtrl.clear();
      _introCtrl.clear();
      _promptCtrl.clear();
      _capsSelected.clear();
      _authBinding = null;
      _language = 'en';
      _defaultStartFlowId = null;
      _loadCommerce(null);
    });
  }

  /// Populates the commerce controls from a profile's `commerce` block, whose
  /// shape mirrors the backend CommerceConfig (catalogs as {value,version},
  /// checkoutTool as {value}).
  void _loadCommerce(Map<String, dynamic>? commerce) {
    _commerceCatalogs.clear();
    if (commerce == null) {
      _commerceEnabled = false;
      _commerceCurrencyCtrl.text = 'USD';
      _commerceCheckoutTool = null;
      return;
    }
    _commerceEnabled = commerce['cartEnabled'] == true;
    _commerceCurrencyCtrl.text = commerce['currency']?.toString() ?? 'USD';
    final tool = commerce['checkoutTool'];
    _commerceCheckoutTool = tool is Map ? tool['value']?.toString() : tool?.toString();
    for (final c in (commerce['catalogs'] as List? ?? const [])) {
      if (c is Map && c['value'] != null) _commerceCatalogs.add(c['value'].toString());
    }
  }

  @override
  void dispose() {
    _idCtrl.dispose();
    _nameCtrl.dispose();
    _introCtrl.dispose();
    _promptCtrl.dispose();
    _commerceCurrencyCtrl.dispose();
    super.dispose();
  }

  /// Builds the commerce block in CommerceConfig JSON shape, or null when the
  /// store toggle is off. Catalog versions come from the loaded catalog list.
  Map<String, dynamic>? _buildCommerce() {
    if (!_commerceEnabled) return null;
    final catalogs = ref.read(productCatalogsProvider).valueOrNull ?? const [];
    final versionById = {for (final c in catalogs) c.id: c.version};
    return {
      'catalogs': _commerceCatalogs
          .map((id) => {'value': id, 'version': versionById[id] ?? 1})
          .toList(),
      'currency': _commerceCurrencyCtrl.text.trim().isEmpty ? 'USD' : _commerceCurrencyCtrl.text.trim(),
      'checkoutTool': _commerceCheckoutTool == null ? null : {'value': _commerceCheckoutTool},
      'cartEnabled': true,
    };
  }

  Future<void> _save() async {
    if (_idCtrl.text.isBlank || _nameCtrl.text.isBlank || _introCtrl.text.isBlank || _promptCtrl.text.isBlank) {
      showSnack(context, 'fill id, display name, intro, and system prompt', error: true);
      return;
    }
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) {
      showSnack(context, 'select a tenant first', error: true);
      return;
    }
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.createProfile(ProfileRequest(
        tenant: tenant,
        id: _idCtrl.text.trim(),
        displayName: _nameCtrl.text.trim(),
        introOneLiner: _introCtrl.text.trim(),
        systemPromptFragment: _promptCtrl.text.trim(),
        capabilities: _capsSelected.toList(),
        authBindingId: _authBinding,
        language: _language,
        defaultStartFlowId: _defaultStartFlowId,
        commerce: _buildCommerce(),
      ));
      ref.invalidate(profilesProvider);
      if (!mounted) return;
      final wasCloning = _cloningFromVersion;
      _idCtrl.clear();
      _nameCtrl.clear();
      _introCtrl.clear();
      _promptCtrl.clear();
      setState(() {
        _capsSelected.clear();
        _authBinding = null;
        _language = 'en';
        _defaultStartFlowId = null;
        _loadCommerce(null);
        _cloningFromVersion = null;
      });
      showSnack(context,
          wasCloning == null
              ? 'profile saved'
              : 'saved as v${wasCloning + 1}');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Widget _commerceSection(BuildContext context) {
    final catalogs = ref.watch(productCatalogsProvider);
    final tools = ref.watch(toolsProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 8),
        Text('Catalogs', style: Theme.of(context).textTheme.labelLarge),
        catalogs.when(
          loading: () => const LinearProgressIndicator(),
          error: (e, _) => Text(formatError(e),
              style: TextStyle(color: Theme.of(context).colorScheme.error)),
          data: (list) => list.isEmpty
              ? const Text('No catalogs yet — create one under Catalogs.',
                  style: TextStyle(fontSize: 12))
              : Wrap(
                  spacing: 6,
                  runSpacing: 4,
                  children: list
                      .map((c) => FilterChip(
                            label: Text('${c.id}@${c.version}'),
                            selected: _commerceCatalogs.contains(c.id),
                            onSelected: (sel) => setState(() {
                              if (sel) {
                                _commerceCatalogs.add(c.id);
                              } else {
                                _commerceCatalogs.remove(c.id);
                              }
                            }),
                          ))
                      .toList(),
                ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _commerceCurrencyCtrl,
          decoration: const InputDecoration(labelText: 'currency (ISO-4217)'),
        ),
        const SizedBox(height: 12),
        tools.when(
          loading: () => const SizedBox.shrink(),
          error: (e, _) => Text(formatError(e),
              style: TextStyle(color: Theme.of(context).colorScheme.error)),
          data: (list) {
            final ids = list.map((t) => t.id).toSet();
            final value = ids.contains(_commerceCheckoutTool) ? _commerceCheckoutTool : null;
            return DropdownButtonFormField<String?>(
              initialValue: value,
              decoration: const InputDecoration(labelText: 'checkout tool'),
              items: [
                const DropdownMenuItem(value: null, child: Text('— none —')),
                ...ids.map((id) => DropdownMenuItem(value: id, child: Text(id))),
              ],
              onChanged: (v) => setState(() => _commerceCheckoutTool = v),
            );
          },
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    if (ref.watch(currentTenantOrNull) == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Profiles');
    }
    final profiles = ref.watch(profilesProvider);
    final caps = ref.watch(capabilitiesProvider);
    final bindings = ref.watch(authBindingsProvider);

    return AdminTwoPane(
      title: 'Profiles',
      subtitle: 'Each POST creates a new version. Earlier versions stay sticky for in-flight sessions.',
      list: profiles.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e), style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (list) => list.isEmpty
            ? const Center(child: Text('No profiles yet.'))
            : ListView.separated(
                itemBuilder: (_, i) => _ProfileTile(
                  profile: list[i],
                  onCloneEdit: () => _cloneEditFrom(list[i]),
                ),
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemCount: list.length,
              ),
      ),
      form: SingleChildScrollView(
        child: FormCard(
          title: _cloningFromVersion == null
              ? 'New profile'
              : 'Editing clone of ${_idCtrl.text}@$_cloningFromVersion → '
                  'saves as v${_cloningFromVersion! + 1}',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(controller: _idCtrl, decoration: const InputDecoration(labelText: 'id')),
              const SizedBox(height: 8),
              TextField(controller: _nameCtrl, decoration: const InputDecoration(labelText: 'display name')),
              const SizedBox(height: 8),
              TextField(controller: _introCtrl, decoration: const InputDecoration(labelText: 'intro one-liner')),
              const SizedBox(height: 8),
              TextField(
                controller: _promptCtrl,
                maxLines: 4,
                decoration: const InputDecoration(labelText: 'system prompt fragment'),
              ),
              const SizedBox(height: 16),
              Text('Capabilities', style: Theme.of(context).textTheme.labelLarge),
              caps.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text(formatError(e),
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
                data: (list) => Wrap(
                  spacing: 6,
                  runSpacing: 4,
                  children: list
                      .map((c) => FilterChip(
                            label: Text('${c.id}@${c.version}'),
                            selected: _capsSelected.contains(c.id),
                            onSelected: (sel) => setState(() {
                              if (sel) {
                                _capsSelected.add(c.id);
                              } else {
                                _capsSelected.remove(c.id);
                              }
                            }),
                          ))
                      .toList(),
                ),
              ),
              const SizedBox(height: 12),
              bindings.when(
                loading: () => const SizedBox.shrink(),
                error: (e, _) => Text(formatError(e),
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
                data: (list) => DropdownButtonFormField<String?>(
                  initialValue: _authBinding,
                  decoration: const InputDecoration(labelText: 'auth binding'),
                  items: [
                    const DropdownMenuItem(value: null, child: Text('— none —')),
                    ...list.map((b) => DropdownMenuItem(value: b.id, child: Text(b.id))),
                  ],
                  onChanged: (v) => setState(() => _authBinding = v),
                ),
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                initialValue: _language,
                decoration: const InputDecoration(
                  labelText: 'response language',
                  helperText: 'the LLM responds in this language regardless of user input',
                ),
                items: _languageOptions.entries
                    .map((e) => DropdownMenuItem(
                          value: e.key,
                          child: Text('${e.value}  (${e.key})'),
                        ))
                    .toList(),
                onChanged: (v) => setState(() => _language = v ?? 'en'),
              ),
              const SizedBox(height: 12),
              StartFlowPicker(
                tenant: ref.watch(currentTenantOrNull) ?? 'default',
                value: _defaultStartFlowId,
                label: 'Default start flow (optional)',
                onChanged: (v) => setState(() => _defaultStartFlowId = v),
              ),
              const SizedBox(height: 16),
              const Divider(),
              SwitchListTile(
                value: _commerceEnabled,
                onChanged: (v) => setState(() => _commerceEnabled = v),
                title: const Text('Commerce (storefront)'),
                subtitle: const Text(
                    'Turn this profile into a store: bind catalogs, currency, and '
                    'a checkout tool. Shows the cart affordance to shoppers.',
                    style: TextStyle(fontSize: 12)),
                contentPadding: EdgeInsets.zero,
              ),
              if (_commerceEnabled) _commerceSection(context),
            ],
          ),
          actions: [
            if (_cloningFromVersion != null)
              TextButton(
                onPressed: _saving ? null : _cancelClone,
                child: const Text('Cancel'),
              ),
            FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
                  ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : Text(_cloningFromVersion == null
                      ? 'Save'
                      : 'Save as v${_cloningFromVersion! + 1}'),
            ),
          ],
        ),
      ),
    );
  }
}

class _ProfileTile extends StatelessWidget {
  const _ProfileTile({required this.profile, required this.onCloneEdit});
  final ProfileResponse profile;
  final VoidCallback onCloneEdit;

  @override
  Widget build(BuildContext context) {
    return EditableRecordCard(
      onCloneEdit: onCloneEdit,
      extraActions: [
        OutlinedButton.icon(
          onPressed: () => CloneProfileWizard.show(
            context,
            sourceTenant: profile.tenant,
            profile: profile,
          ),
          icon: const Icon(Icons.move_to_inbox_outlined, size: 18),
          label: const Text('Clone to another tenant…'),
        ),
      ],
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('${profile.id}@${profile.version}',
              style: Theme.of(context).textTheme.titleSmall),
          Text(profile.displayName, style: Theme.of(context).textTheme.bodyMedium),
        ],
      ),
      trailing: profile.status == null
          ? null
          : Chip(
              label: Text(profile.status!, style: const TextStyle(fontSize: 11)),
              visualDensity: VisualDensity.compact,
            ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'displayName', value: profile.displayName),
          DetailRow(label: 'introOneLiner', value: profile.introOneLiner),
          DetailRow(
            label: 'systemPromptFragment',
            value: profile.systemPromptFragment,
            mono: true,
          ),
          DetailRow(
            label: 'capabilities',
            value: profile.capabilities.isEmpty
                ? '(none)'
                : profile.capabilities.join(', '),
          ),
          DetailRow(
            label: 'authBindingId',
            value: profile.authBindingId ?? '(none)',
          ),
          DetailRow(label: 'language', value: profile.language),
        ],
      ),
    );
  }
}

extension on String {
  bool get isBlank => trim().isEmpty;
}

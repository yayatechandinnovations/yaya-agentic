import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../models/admin/auth_binding.dart';
import '../../../models/admin/capability.dart';
import '../../../models/admin/profile.dart';
import '../shared/admin_shared.dart';

final profilesProvider = FutureProvider<List<ProfileResponse>>((ref) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listProfiles();
});

final capabilitiesProvider = FutureProvider<List<CapabilityResponse>>((ref) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listCapabilities();
});

final authBindingsProvider = FutureProvider<List<AuthBindingResponse>>((ref) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listAuthBindings();
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
  bool _saving = false;

  @override
  void dispose() {
    _idCtrl.dispose();
    _nameCtrl.dispose();
    _introCtrl.dispose();
    _promptCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_idCtrl.text.isBlank || _nameCtrl.text.isBlank || _introCtrl.text.isBlank || _promptCtrl.text.isBlank) {
      showSnack(context, 'fill id, display name, intro, and system prompt', error: true);
      return;
    }
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.createProfile(ProfileRequest(
        tenant: 'default',
        id: _idCtrl.text.trim(),
        displayName: _nameCtrl.text.trim(),
        introOneLiner: _introCtrl.text.trim(),
        systemPromptFragment: _promptCtrl.text.trim(),
        capabilities: _capsSelected.toList(),
        authBindingId: _authBinding,
      ));
      ref.invalidate(profilesProvider);
      if (!mounted) return;
      _idCtrl.clear();
      _nameCtrl.clear();
      _introCtrl.clear();
      _promptCtrl.clear();
      setState(_capsSelected.clear);
      showSnack(context, 'profile saved');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
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
                itemBuilder: (_, i) => _ProfileTile(profile: list[i]),
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemCount: list.length,
              ),
      ),
      form: SingleChildScrollView(
        child: FormCard(
          title: 'New / new-version profile',
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
            ],
          ),
          actions: [
            FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2)) : const Text('Save'),
            ),
          ],
        ),
      ),
    );
  }
}

class _ProfileTile extends StatelessWidget {
  const _ProfileTile({required this.profile});
  final ProfileResponse profile;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        title: Text('${profile.id}@${profile.version}'),
        subtitle: Text('${profile.displayName} · ${profile.capabilities.length} capabilities'
            '${profile.authBindingId == null ? "" : " · auth: ${profile.authBindingId}"}'),
        trailing: Text(profile.status ?? '', style: Theme.of(context).textTheme.bodySmall),
      ),
    );
  }
}

extension on String {
  bool get isBlank => trim().isEmpty;
}

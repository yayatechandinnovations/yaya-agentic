import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/identity_profile_mapping.dart';
import '../profiles/profiles_screen.dart' show profilesProvider;
import '../shared/admin_shared.dart';

/// Refetched on every write to keep the priority-ordered list current.
final identityProfileMappingsProvider =
    FutureProvider.family<List<IdentityProfileMappingResponse>, String>(
  (ref, tenantId) async {
    final api = await ref.watch(adminApiProvider.future);
    return api.listIdentityProfileMappings(tenantId);
  },
);

/// Per-tenant claim → profile mapping. Drives `IdentityProfileResolver`:
/// at session start the resolver walks the tenant's rows in priority-ASC
/// order and returns the first profile whose `claimName=claimValue` matches
/// the verified principal's claims. The chain falls through to channel /
/// fallback resolvers when nothing matches.
class IdentityRoutingScreen extends ConsumerStatefulWidget {
  const IdentityRoutingScreen({super.key});

  @override
  ConsumerState<IdentityRoutingScreen> createState() => _IdentityRoutingScreenState();
}

class _IdentityRoutingScreenState extends ConsumerState<IdentityRoutingScreen> {
  final _claimNameCtrl = TextEditingController();
  final _claimValueCtrl = TextEditingController();
  final _priorityCtrl = TextEditingController(text: '100');
  String? _profileId;     // dropdown selection: "<id>@<version>"
  int? _editingId;
  bool _saving = false;

  void _resetForm() {
    setState(() {
      _editingId = null;
      _claimNameCtrl.clear();
      _claimValueCtrl.clear();
      _priorityCtrl.text = '100';
      _profileId = null;
    });
  }

  void _loadIntoForm(IdentityProfileMappingResponse row) {
    setState(() {
      _editingId = row.id;
      _claimNameCtrl.text = row.claimName;
      _claimValueCtrl.text = row.claimValue;
      _priorityCtrl.text = row.priority.toString();
      _profileId = '${row.profileId}@${row.profileVersion}';
    });
  }

  @override
  void dispose() {
    _claimNameCtrl.dispose();
    _claimValueCtrl.dispose();
    _priorityCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) {
      showSnack(context, 'select a tenant first', error: true);
      return;
    }
    if (_claimNameCtrl.text.trim().isEmpty ||
        _claimValueCtrl.text.trim().isEmpty ||
        _profileId == null) {
      showSnack(context, 'claimName, claimValue, and profile are required', error: true);
      return;
    }
    final parts = _profileId!.split('@');
    final profileId = parts[0];
    final profileVersion = int.tryParse(parts.length > 1 ? parts[1] : '1') ?? 1;
    final priority = int.tryParse(_priorityCtrl.text.trim()) ?? 100;

    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final body = IdentityProfileMappingRequest(
        claimName: _claimNameCtrl.text.trim(),
        claimValue: _claimValueCtrl.text.trim(),
        profileId: profileId,
        profileVersion: profileVersion,
        priority: priority,
      );
      if (_editingId == null) {
        await api.createIdentityProfileMapping(tenant, body);
      } else {
        await api.updateIdentityProfileMapping(tenant, _editingId!, body);
      }
      ref.invalidate(identityProfileMappingsProvider(tenant));
      if (!mounted) return;
      final wasEdit = _editingId != null;
      _resetForm();
      showSnack(context, wasEdit ? 'mapping updated' : 'mapping added');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _delete(IdentityProfileMappingResponse row) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final confirmed = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('Remove this mapping?'),
            content: Text(
                '${row.claimName}=${row.claimValue} → ${row.profileId}@${row.profileVersion} will be removed.'),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Cancel')),
              FilledButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: const Text('Remove')),
            ],
          ),
        ) ??
        false;
    if (!confirmed) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.deleteIdentityProfileMapping(tenant, row.id);
      ref.invalidate(identityProfileMappingsProvider(tenant));
      if (mounted) showSnack(context, 'mapping removed');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Identity routing');
    }
    final mappings = ref.watch(identityProfileMappingsProvider(tenant));
    final profiles = ref.watch(profilesProvider);

    return AdminTwoPane(
      title: 'Identity routing',
      subtitle:
          'JWT claim → profile mapping for tenant "$tenant". Walked in priority-ASC order; '
          'first match wins. No row matches → chain falls through to channel / fallback resolvers.',
      list: mappings.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e),
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (list) => list.isEmpty
            ? const Center(
                child: Text(
                    'No mappings yet. Without them, every session needs an explicit profileId.'))
            : ListView.separated(
                itemBuilder: (_, i) => _MappingTile(
                  row: list[i],
                  onEdit: () => _loadIntoForm(list[i]),
                  onDelete: () => _delete(list[i]),
                ),
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemCount: list.length,
              ),
      ),
      form: SingleChildScrollView(
        child: FormCard(
          title: _editingId == null ? 'Add mapping' : 'Editing mapping #$_editingId',
          child: profiles.when(
            loading: () => const LinearProgressIndicator(),
            error: (e, _) => Text(formatError(e),
                style: TextStyle(color: Theme.of(context).colorScheme.error)),
            data: (profileList) {
              final filtered = profileList.where((p) => p.tenant == tenant).toList();
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  TextField(
                    controller: _claimNameCtrl,
                    decoration: const InputDecoration(
                      labelText: 'claimName',
                      helperText:
                          'JWT claim to read (e.g. "role", "groups", "scope").',
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _claimValueCtrl,
                    decoration: const InputDecoration(
                      labelText: 'claimValue',
                      helperText:
                          'Match value. Scalar string → equality. List → containment. Space-separated → token match.',
                    ),
                  ),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<String>(
                    initialValue: _profileId,
                    isExpanded: true,
                    decoration: const InputDecoration(labelText: 'profile'),
                    items: filtered
                        .map((p) => DropdownMenuItem(
                              value: '${p.id}@${p.version}',
                              child: Text('${p.id}@${p.version}'),
                            ))
                        .toList(),
                    onChanged: (v) => setState(() => _profileId = v),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _priorityCtrl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'priority',
                      helperText:
                          'Lower = checked first. 10/20/30… leaves room to insert later.',
                    ),
                  ),
                ],
              );
            },
          ),
          actions: [
            if (_editingId != null)
              TextButton(
                onPressed: _saving ? null : _resetForm,
                child: const Text('Cancel'),
              ),
            FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
                  ? const SizedBox.square(
                      dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : Text(_editingId == null ? 'Add' : 'Save'),
            ),
          ],
        ),
      ),
    );
  }
}

class _MappingTile extends StatelessWidget {
  const _MappingTile({
    required this.row,
    required this.onEdit,
    required this.onDelete,
  });

  final IdentityProfileMappingResponse row;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    return EditableRecordCard(
      onCloneEdit: onEdit,
      leading: _PriorityChip(priority: row.priority),
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('${row.claimName} = ${row.claimValue}',
              style: Theme.of(context).textTheme.titleSmall),
          Text('→ ${row.profileId}@${row.profileVersion}',
              style: const TextStyle(fontSize: 13)),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'claimName', value: row.claimName, mono: true),
          DetailRow(label: 'claimValue', value: row.claimValue, mono: true),
          DetailRow(
              label: 'profile',
              value: '${row.profileId}@${row.profileVersion}',
              mono: true),
          DetailRow(label: 'priority', value: row.priority.toString()),
          if (row.createdAt != null)
            DetailRow(label: 'createdAt', value: row.createdAt!),
        ],
      ),
      extraActions: [
        TextButton.icon(
          onPressed: onDelete,
          icon: const Icon(Icons.delete_outline, size: 18),
          label: const Text('Remove'),
          style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error),
        ),
      ],
    );
  }
}

class _PriorityChip extends StatelessWidget {
  const _PriorityChip({required this.priority});
  final int priority;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: 36,
      height: 24,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: scheme.secondaryContainer,
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(
        '$priority',
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w700,
          color: scheme.onSecondaryContainer,
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/channel_binding.dart';
import '../profiles/profiles_screen.dart' show profilesProvider;
import '../shared/admin_shared.dart';
import '../whatsapp_senders/whatsapp_senders_screen.dart' show whatsAppSendersProvider;

final channelBindingsProvider =
    FutureProvider.family<List<ChannelBindingResponse>, String>(
  (ref, tenantId) async {
    final api = await ref.watch(adminApiProvider.future);
    return api.listChannelBindings(tenantId);
  },
);

/// Routes inbound messages on a registered WhatsApp sender to a profile.
/// One sender can have multiple bindings — first matching rule (by lowest
/// priority) wins.
class ChannelBindingsScreen extends ConsumerStatefulWidget {
  const ChannelBindingsScreen({super.key});

  @override
  ConsumerState<ChannelBindingsScreen> createState() =>
      _ChannelBindingsScreenState();
}

class _ChannelBindingsScreenState extends ConsumerState<ChannelBindingsScreen> {
  final _priorityCtrl = TextEditingController(text: '100');
  final _keywordsCtrl = TextEditingController();
  final _localesCtrl = TextEditingController();
  final _claimCtrl = TextEditingController();
  final _claimValueCtrl = TextEditingController();

  String? _senderId;
  String? _profileSlug;             // "<id>@<version>"
  String _routingKind = 'MatchAny';
  String _status = 'ACTIVE';
  int? _editingId;
  bool _saving = false;

  @override
  void dispose() {
    _priorityCtrl.dispose();
    _keywordsCtrl.dispose();
    _localesCtrl.dispose();
    _claimCtrl.dispose();
    _claimValueCtrl.dispose();
    super.dispose();
  }

  void _resetForm() {
    setState(() {
      _editingId = null;
      _senderId = null;
      _profileSlug = null;
      _routingKind = 'MatchAny';
      _status = 'ACTIVE';
      _priorityCtrl.text = '100';
      _keywordsCtrl.clear();
      _localesCtrl.clear();
      _claimCtrl.clear();
      _claimValueCtrl.clear();
    });
  }

  void _loadIntoForm(ChannelBindingResponse row) {
    setState(() {
      _editingId = row.id;
      _senderId = row.endpoint.id;
      _profileSlug = '${row.profileId}@${row.profileVersion ?? 1}';
      _routingKind = row.routing.kind;
      _status = row.status;
      _priorityCtrl.text = row.priority.toString();
      switch (row.routing.kind) {
        case 'KeywordPrefix':
          _keywordsCtrl.text =
              ((row.routing.json['keywords'] as List?) ?? const []).join(', ');
          break;
        case 'Locale':
          _localesCtrl.text =
              ((row.routing.json['locales'] as List?) ?? const []).join(', ');
          break;
        case 'EndUserClaim':
          _claimCtrl.text = (row.routing.json['claim'] ?? '') as String;
          _claimValueCtrl.text = (row.routing.json['equals'] ?? '') as String;
          break;
      }
    });
  }

  RoutingRuleWire _buildRoutingRule() {
    switch (_routingKind) {
      case 'KeywordPrefix':
        final words = _keywordsCtrl.text
            .split(',')
            .map((s) => s.trim())
            .where((s) => s.isNotEmpty)
            .toList();
        return RoutingRuleWire.keywordPrefix(words);
      case 'Locale':
        final locs = _localesCtrl.text
            .split(',')
            .map((s) => s.trim())
            .where((s) => s.isNotEmpty)
            .toList();
        return RoutingRuleWire.locale(locs);
      case 'EndUserClaim':
        return RoutingRuleWire.endUserClaim(
            _claimCtrl.text.trim(), _claimValueCtrl.text.trim());
      default:
        return RoutingRuleWire.matchAny();
    }
  }

  String? _validate() {
    if (_senderId == null) return 'Choose a sender.';
    if (_profileSlug == null) return 'Choose a profile.';
    final priority = int.tryParse(_priorityCtrl.text.trim());
    if (priority == null || priority < 0) {
      return 'Priority must be a non-negative whole number.';
    }
    switch (_routingKind) {
      case 'KeywordPrefix':
        if (_keywordsCtrl.text.trim().isEmpty) {
          return 'At least one keyword is required.';
        }
        break;
      case 'Locale':
        if (_localesCtrl.text.trim().isEmpty) {
          return 'At least one locale is required.';
        }
        break;
      case 'EndUserClaim':
        if (_claimCtrl.text.trim().isEmpty || _claimValueCtrl.text.trim().isEmpty) {
          return 'Both claim and value are required.';
        }
        break;
    }
    return null;
  }

  Future<void> _save() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final err = _validate();
    if (err != null) {
      showSnack(context, err, error: true);
      return;
    }
    final parts = _profileSlug!.split('@');
    final profileId = parts[0];
    final profileVersion = int.tryParse(parts.length > 1 ? parts[1] : '1') ?? 1;
    final priority = int.tryParse(_priorityCtrl.text.trim()) ?? 100;
    final policy = SessionPolicyWire.defaults();

    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      if (_editingId == null) {
        await api.createChannelBinding(
            tenant,
            ChannelBindingRequest(
              endpoint: ChannelEndpointWire(
                  kind: 'WHATSAPP_SENDER', id: _senderId!),
              profileId: profileId,
              profileVersion: profileVersion,
              routing: _buildRoutingRule(),
              sessionPolicy: policy,
              priority: priority,
              status: _status,
            ));
      } else {
        await api.updateChannelBinding(
            tenant,
            _editingId!,
            ChannelBindingUpdate(
              profileId: profileId,
              profileVersion: profileVersion,
              routing: _buildRoutingRule(),
              sessionPolicy: policy,
              priority: priority,
              status: _status,
            ));
      }
      ref.invalidate(channelBindingsProvider(tenant));
      if (!mounted) return;
      _resetForm();
      showSnack(context, 'Binding saved');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _delete(ChannelBindingResponse row) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final confirmed = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('Remove this binding?'),
            content: Text(
                'Inbound messages on ${row.endpoint.id} will no longer be handed to '
                '"${row.profileId}". The audit trail is preserved.'),
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
      await api.deleteChannelBinding(tenant, row.id);
      ref.invalidate(channelBindingsProvider(tenant));
      if (mounted) showSnack(context, 'Binding removed');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Channel bindings');
    }
    final bindings = ref.watch(channelBindingsProvider(tenant));
    final senders = ref.watch(whatsAppSendersProvider(tenant));
    final profiles = ref.watch(profilesProvider);

    return AdminTwoPane(
      title: 'Channel bindings',
      subtitle:
          'Route inbound WhatsApp messages on each sender to a profile. '
          'Multiple bindings on one sender are evaluated in priority order — lowest first.',
      list: bindings.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e),
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (list) => list.isEmpty
            ? const Center(
                child: Text(
                    'No bindings yet. Add one to route inbound messages to a profile.'))
            : ListView.separated(
                itemBuilder: (_, i) => _BindingTile(
                  row: list[i],
                  onEdit: () => _loadIntoForm(list[i]),
                  onDelete: () => _delete(list[i]),
                ),
                separatorBuilder: (_, i) => const SizedBox(height: 8),
                itemCount: list.length,
              ),
      ),
      form: SingleChildScrollView(
        child: FormCard(
          title: _editingId == null ? 'Add binding' : 'Editing binding #$_editingId',
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
          child: senders.when(
            loading: () => const LinearProgressIndicator(),
            error: (e, _) => Text(formatError(e),
                style: TextStyle(color: Theme.of(context).colorScheme.error)),
            data: (senderList) {
              final activeSenders = senderList
                  .where((s) => s.status != 'REVOKED')
                  .toList();
              return profiles.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text(formatError(e),
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
                data: (profileList) {
                  final tenantProfiles =
                      profileList.where((p) => p.tenant == tenant).toList();
                  return Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      DropdownButtonFormField<String>(
                        initialValue: _senderId,
                        isExpanded: true,
                        decoration: const InputDecoration(labelText: 'Sender'),
                        items: activeSenders
                            .map((s) => DropdownMenuItem(
                                  value: s.id,
                                  child: Text(
                                      '${s.displayName} (${s.phoneE164})'),
                                ))
                            .toList(),
                        onChanged: _editingId != null
                            ? null
                            : (v) => setState(() => _senderId = v),
                      ),
                      const SizedBox(height: 12),
                      DropdownButtonFormField<String>(
                        initialValue: _profileSlug,
                        isExpanded: true,
                        decoration: const InputDecoration(labelText: 'Profile'),
                        items: tenantProfiles
                            .map((p) => DropdownMenuItem(
                                  value: '${p.id}@${p.version}',
                                  child: Text('${p.id}@${p.version}'),
                                ))
                            .toList(),
                        onChanged: (v) => setState(() => _profileSlug = v),
                      ),
                      const SizedBox(height: 12),
                      DropdownButtonFormField<String>(
                        initialValue: _routingKind,
                        isExpanded: true,
                        decoration: const InputDecoration(labelText: 'Routing rule'),
                        items: const [
                          DropdownMenuItem(
                              value: 'MatchAny',
                              child: Text('Match every message')),
                          DropdownMenuItem(
                              value: 'KeywordPrefix',
                              child: Text('Body starts with keyword')),
                          DropdownMenuItem(
                              value: 'Locale',
                              child: Text('End-user locale matches')),
                          DropdownMenuItem(
                              value: 'EndUserClaim',
                              child: Text('Principal claim equals value')),
                        ],
                        onChanged: (v) =>
                            setState(() => _routingKind = v ?? 'MatchAny'),
                      ),
                      const SizedBox(height: 12),
                      if (_routingKind == 'KeywordPrefix')
                        TextField(
                          controller: _keywordsCtrl,
                          decoration: const InputDecoration(
                              labelText: 'Keywords (comma-separated)',
                              helperText:
                                  'Case-insensitive prefix match. e.g. #sales, #support'),
                        ),
                      if (_routingKind == 'Locale')
                        TextField(
                          controller: _localesCtrl,
                          decoration: const InputDecoration(
                              labelText: 'Locales (comma-separated)',
                              helperText:
                                  'BCP-47, e.g. en-US, es. Language-only entries also match country variants.'),
                        ),
                      if (_routingKind == 'EndUserClaim') ...[
                        TextField(
                          controller: _claimCtrl,
                          decoration: const InputDecoration(labelText: 'Claim name'),
                        ),
                        const SizedBox(height: 12),
                        TextField(
                          controller: _claimValueCtrl,
                          decoration: const InputDecoration(labelText: 'Equals'),
                        ),
                      ],
                      const SizedBox(height: 12),
                      TextField(
                        controller: _priorityCtrl,
                        keyboardType: TextInputType.number,
                        decoration: const InputDecoration(
                            labelText: 'Priority',
                            helperText:
                                'Lower number = evaluated first. 10/20/30 leaves room to insert later.'),
                      ),
                      const SizedBox(height: 12),
                      DropdownButtonFormField<String>(
                        initialValue: _status,
                        isExpanded: true,
                        decoration: const InputDecoration(labelText: 'Status'),
                        items: const [
                          DropdownMenuItem(value: 'ACTIVE', child: Text('Active')),
                          DropdownMenuItem(value: 'PAUSED', child: Text('Paused')),
                        ],
                        onChanged: (v) =>
                            setState(() => _status = v ?? 'ACTIVE'),
                      ),
                    ],
                  );
                },
              );
            },
          ),
        ),
      ),
    );
  }
}

class _BindingTile extends StatelessWidget {
  const _BindingTile(
      {required this.row, required this.onEdit, required this.onDelete});
  final ChannelBindingResponse row;
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
          Text('${row.endpoint.id} → ${row.profileId}',
              style: Theme.of(context).textTheme.titleSmall),
          Text(
              '${row.routing.humanLabel()} · idle ${row.sessionPolicy.humanIdle()}',
              style: const TextStyle(fontSize: 12)),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'Sender id', value: row.endpoint.id, mono: true),
          DetailRow(label: 'Profile', value: '${row.profileId}@${row.profileVersion ?? 1}'),
          DetailRow(label: 'Routing', value: row.routing.humanLabel()),
          DetailRow(label: 'Priority', value: row.priority.toString()),
          DetailRow(label: 'Status', value: row.status),
          DetailRow(label: 'Idle timeout', value: row.sessionPolicy.humanIdle()),
          DetailRow(
              label: 'New-session keywords',
              value: row.sessionPolicy.newSessionKeywords.join(', ')),
          DetailRow(
              label: 'Expose end-user phone to profile',
              value: row.sessionPolicy.exposeEndUserPhoneToProfile ? 'yes' : 'no'),
          if (row.createdAt != null)
            DetailRow(label: 'Created', value: row.createdAt!),
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

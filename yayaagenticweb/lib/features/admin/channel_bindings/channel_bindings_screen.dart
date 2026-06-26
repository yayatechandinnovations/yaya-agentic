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
  final _addScopeCtrl = TextEditingController();
  final _addClaimKeyCtrl = TextEditingController();
  final _addClaimValueCtrl = TextEditingController();

  String? _senderId;
  String? _profileSlug;             // "<id>@<version>"
  String _routingKind = 'MatchAny';
  String _status = 'ACTIVE';
  int? _editingId;
  bool _saving = false;

  String _principalKind = 'Anonymous';
  final List<String> _principalExtraScopes = [];
  final Map<String, String> _principalExtraClaims = {};

  @override
  void dispose() {
    _priorityCtrl.dispose();
    _keywordsCtrl.dispose();
    _localesCtrl.dispose();
    _claimCtrl.dispose();
    _claimValueCtrl.dispose();
    _addScopeCtrl.dispose();
    _addClaimKeyCtrl.dispose();
    _addClaimValueCtrl.dispose();
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
      _addScopeCtrl.clear();
      _addClaimKeyCtrl.clear();
      _addClaimValueCtrl.clear();
      _principalKind = 'Anonymous';
      _principalExtraScopes.clear();
      _principalExtraClaims.clear();
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
      final policy = row.sessionPolicy.defaultPrincipalPolicy;
      _principalKind = policy.kind;
      _principalExtraScopes
        ..clear()
        ..addAll(policy.additionalScopes);
      _principalExtraClaims
        ..clear()
        ..addEntries(policy.additionalClaims.entries
            .map((e) => MapEntry(e.key, e.value.toString())));
    });
  }

  DefaultPrincipalPolicyWire _buildPrincipalPolicy() {
    switch (_principalKind) {
      case 'PhoneAsPrincipal':
        return DefaultPrincipalPolicyWire.phoneAsPrincipal(
          additionalScopes: List.unmodifiable(_principalExtraScopes),
          additionalClaims:
              Map<String, dynamic>.unmodifiable(_principalExtraClaims),
        );
      case 'LookupPrincipal':
        // Not reachable in W-A1 — option is disabled in the form.
        return DefaultPrincipalPolicyWire.anonymous();
      default:
        return DefaultPrincipalPolicyWire.anonymous();
    }
  }

  String? _addScopeChip() {
    final raw = _addScopeCtrl.text.trim();
    if (raw.isEmpty) return 'Type a scope name first.';
    if (_principalExtraScopes.contains(raw)) return 'Scope already added.';
    setState(() {
      _principalExtraScopes.add(raw);
      _addScopeCtrl.clear();
    });
    return null;
  }

  String? _addClaimChip() {
    final key = _addClaimKeyCtrl.text.trim();
    final value = _addClaimValueCtrl.text.trim();
    if (key.isEmpty || value.isEmpty) return 'Claim key and value required.';
    if (DefaultPrincipalPolicyWire.reservedClaimKeys.contains(key)) {
      return 'Reserved channel claim — pick a different key '
          '(${DefaultPrincipalPolicyWire.reservedClaimKeys.join(", ")}).';
    }
    setState(() {
      _principalExtraClaims[key] = value;
      _addClaimKeyCtrl.clear();
      _addClaimValueCtrl.clear();
    });
    return null;
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
    final policy = SessionPolicyWire.defaults()
        .copyWith(defaultPrincipalPolicy: _buildPrincipalPolicy());

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
                      const SizedBox(height: 16),
                      _IdentityPolicyCard(
                        kind: _principalKind,
                        extraScopes: _principalExtraScopes,
                        extraClaims: _principalExtraClaims,
                        onKindChanged: (v) =>
                            setState(() => _principalKind = v ?? 'Anonymous'),
                        addScopeCtrl: _addScopeCtrl,
                        addClaimKeyCtrl: _addClaimKeyCtrl,
                        addClaimValueCtrl: _addClaimValueCtrl,
                        onAddScope: () {
                          final err = _addScopeChip();
                          if (err != null) showSnack(context, err, error: true);
                        },
                        onRemoveScope: (s) =>
                            setState(() => _principalExtraScopes.remove(s)),
                        onAddClaim: () {
                          final err = _addClaimChip();
                          if (err != null) showSnack(context, err, error: true);
                        },
                        onRemoveClaim: (k) =>
                            setState(() => _principalExtraClaims.remove(k)),
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
          DetailRow(
              label: 'Identity policy',
              value: row.sessionPolicy.defaultPrincipalPolicy.humanLabel()),
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

class _IdentityPolicyCard extends StatelessWidget {
  const _IdentityPolicyCard({
    required this.kind,
    required this.extraScopes,
    required this.extraClaims,
    required this.onKindChanged,
    required this.addScopeCtrl,
    required this.addClaimKeyCtrl,
    required this.addClaimValueCtrl,
    required this.onAddScope,
    required this.onRemoveScope,
    required this.onAddClaim,
    required this.onRemoveClaim,
  });

  final String kind;
  final List<String> extraScopes;
  final Map<String, String> extraClaims;
  final ValueChanged<String?> onKindChanged;
  final TextEditingController addScopeCtrl;
  final TextEditingController addClaimKeyCtrl;
  final TextEditingController addClaimValueCtrl;
  final VoidCallback onAddScope;
  final ValueChanged<String> onRemoveScope;
  final VoidCallback onAddClaim;
  final ValueChanged<String> onRemoveClaim;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        border: Border.all(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Identity policy',
              style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 4),
          Text(
            "How the inbound authenticator builds the end-user's Principal. "
            "A profile that requires user identity needs more than Anonymous; "
            "step-up for sensitive actions is configured per Authorizer.",
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: 8),
          RadioListTile<String>(
            dense: true,
            contentPadding: EdgeInsets.zero,
            value: 'Anonymous',
            groupValue: kind,
            onChanged: onKindChanged,
            title: const Text('Anonymous (no identity beyond verified phone)'),
            subtitle: const Text(
                'Channel scopes only. Suitable for FAQ / public flows.'),
          ),
          RadioListTile<String>(
            dense: true,
            contentPadding: EdgeInsets.zero,
            value: 'PhoneAsPrincipal',
            groupValue: kind,
            onChanged: onKindChanged,
            title: const Text('Phone as principal'),
            subtitle: const Text(
                'Phone is the identity. Add scopes and claims that ride along.'),
          ),
          if (kind == 'PhoneAsPrincipal') ...[
            const SizedBox(height: 4),
            Text('Additional scopes',
                style: Theme.of(context).textTheme.bodySmall),
            const SizedBox(height: 4),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final scope in extraScopes)
                  InputChip(
                    label: Text(scope),
                    onDeleted: () => onRemoveScope(scope),
                  ),
              ],
            ),
            const SizedBox(height: 4),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: TextField(
                    controller: addScopeCtrl,
                    decoration: const InputDecoration(
                      isDense: true,
                      hintText: 'e.g. order:read',
                    ),
                    onSubmitted: (_) => onAddScope(),
                  ),
                ),
                const SizedBox(width: 8),
                TextButton.icon(
                  onPressed: onAddScope,
                  icon: const Icon(Icons.add, size: 16),
                  label: const Text('Add scope'),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text('Additional claims',
                style: Theme.of(context).textTheme.bodySmall),
            const SizedBox(height: 4),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final entry in extraClaims.entries)
                  InputChip(
                    label: Text('${entry.key}: ${entry.value}'),
                    onDeleted: () => onRemoveClaim(entry.key),
                  ),
              ],
            ),
            const SizedBox(height: 4),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: TextField(
                    controller: addClaimKeyCtrl,
                    decoration: const InputDecoration(
                      isDense: true,
                      hintText: 'claim key',
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    controller: addClaimValueCtrl,
                    decoration: const InputDecoration(
                      isDense: true,
                      hintText: 'value',
                    ),
                    onSubmitted: (_) => onAddClaim(),
                  ),
                ),
                const SizedBox(width: 8),
                TextButton.icon(
                  onPressed: onAddClaim,
                  icon: const Icon(Icons.add, size: 16),
                  label: const Text('Add claim'),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              'Reserved keys (silently overridden by the channel): '
              '${DefaultPrincipalPolicyWire.reservedClaimKeys.join(", ")}.',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    fontStyle: FontStyle.italic,
                  ),
            ),
          ],
          Tooltip(
            message: 'Coming in W-A2 — needs a tenant PhoneToPrincipalResolver bean.',
            child: RadioListTile<String>(
              dense: true,
              contentPadding: EdgeInsets.zero,
              value: 'LookupPrincipal',
              groupValue: kind,
              onChanged: null, // disabled in W-A1
              title: const Text('Lookup principal (coming soon)'),
              subtitle: const Text(
                  'Resolve the phone against a tenant resolver. Requires W-A2.'),
            ),
          ),
        ],
      ),
    );
  }
}

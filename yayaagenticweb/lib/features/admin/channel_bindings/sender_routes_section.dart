import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/auth_resolver.dart';
import '../../../models/admin/channel_binding.dart';
import '../auth_resolvers/auth_resolvers_screen.dart' show authResolversProvider;
import '../profiles/profiles_screen.dart' show profilesProvider;
import '../shared/admin_shared.dart';
import '../shared/profile_allowlist_field.dart';

final channelBindingsProvider =
    FutureProvider.family<List<ChannelBindingResponse>, String>(
  (ref, tenantId) async {
    final api = await ref.watch(adminApiProvider.future);
    return api.listChannelBindings(tenantId);
  },
);

/// The routing config for a single channel endpoint (a WhatsApp/SMS sender),
/// embedded inside that sender's detail card. Replaces the old standalone
/// "Bindings" page: an operator manages *which profile an inbound talks to*
/// right where they manage the number.
///
/// One endpoint can carry several routes (evaluated by priority, lowest first),
/// so this shows a list with an "Add route" affordance rather than a single
/// profile field. The common case is one MatchAny route → a profile.
class SenderRoutesSection extends ConsumerWidget {
  const SenderRoutesSection({
    super.key,
    required this.endpointKind, // 'WHATSAPP_SENDER' | 'SMS_SENDER'
    required this.endpointId,
    this.supportsResolver = true,
  });

  final String endpointKind;
  final String endpointId;

  /// WhatsApp runs the auth resolver; SMS is FAQ-mode only, so hide the
  /// identity + override controls there.
  final bool supportsResolver;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) return const SizedBox.shrink();
    final bindings = ref.watch(channelBindingsProvider(tenant));

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Text('Routes', style: Theme.of(context).textTheme.titleSmall),
            const Spacer(),
            TextButton.icon(
              onPressed: () => _openEditor(context, ref, tenant, null),
              icon: const Icon(Icons.add, size: 18),
              label: const Text('Add route'),
            ),
          ],
        ),
        bindings.when(
          loading: () => const LinearProgressIndicator(),
          error: (e, _) => Text(formatError(e),
              style: TextStyle(color: Theme.of(context).colorScheme.error)),
          data: (all) {
            final routes = all
                .where((b) =>
                    b.endpoint.kind == endpointKind && b.endpoint.id == endpointId)
                .toList()
              ..sort((a, b) => a.priority.compareTo(b.priority));
            if (routes.isEmpty) {
              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: Text(
                  'No routes yet — inbound messages on this number aren’t handed '
                  'to any profile. Add one to start replying.',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              );
            }
            return Column(
              children: [
                for (final r in routes)
                  _RouteRow(
                    row: r,
                    onEdit: () => _openEditor(context, ref, tenant, r),
                    onDelete: () => _delete(context, ref, tenant, r),
                  ),
              ],
            );
          },
        ),
      ],
    );
  }

  Future<void> _openEditor(BuildContext context, WidgetRef ref, String tenant,
      ChannelBindingResponse? existing) async {
    await showDialog<void>(
      context: context,
      builder: (_) => _RouteEditorDialog(
        tenant: tenant,
        endpointKind: endpointKind,
        endpointId: endpointId,
        supportsResolver: supportsResolver,
        existing: existing,
      ),
    );
  }

  Future<void> _delete(BuildContext context, WidgetRef ref, String tenant,
      ChannelBindingResponse row) async {
    final confirmed = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('Remove this route?'),
            content: Text(
                'Inbound messages matching "${row.routing.humanLabel()}" will no '
                'longer be handed to "${row.profileId}". The audit trail is preserved.'),
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
      if (context.mounted) showSnack(context, 'Route removed');
    } catch (e) {
      if (context.mounted) showSnack(context, formatError(e), error: true);
    }
  }
}

/// One route row in the sender card: priority + "rule → profile" + resolver hint.
class _RouteRow extends StatelessWidget {
  const _RouteRow(
      {required this.row, required this.onEdit, required this.onDelete});
  final ChannelBindingResponse row;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final muted = Theme.of(context).colorScheme.onSurfaceVariant;
    final resolverHint = row.authResolverId == null
        ? 'FAQ mode'
        : 'resolver #${row.authResolverId}';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          _PriorityChip(priority: row.priority),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('${row.routing.humanLabel()} → ${row.profileId}',
                    style: Theme.of(context).textTheme.bodyMedium),
                Text('${row.status.toLowerCase()} · $resolverHint',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(color: muted)),
              ],
            ),
          ),
          IconButton(
            tooltip: 'Edit route',
            icon: const Icon(Icons.edit_outlined, size: 18),
            onPressed: onEdit,
          ),
          IconButton(
            tooltip: 'Remove route',
            icon: Icon(Icons.delete_outline,
                size: 18, color: Theme.of(context).colorScheme.error),
            onPressed: onDelete,
          ),
        ],
      ),
    );
  }
}

/// Add/edit a single route for a fixed endpoint. Same fields as the old
/// standalone binding form minus the sender picker (the endpoint is the sender
/// this dialog was opened from).
class _RouteEditorDialog extends ConsumerStatefulWidget {
  const _RouteEditorDialog({
    required this.tenant,
    required this.endpointKind,
    required this.endpointId,
    required this.supportsResolver,
    this.existing,
  });

  final String tenant;
  final String endpointKind;
  final String endpointId;
  final bool supportsResolver;
  final ChannelBindingResponse? existing;

  @override
  ConsumerState<_RouteEditorDialog> createState() => _RouteEditorDialogState();
}

class _RouteEditorDialogState extends ConsumerState<_RouteEditorDialog> {
  final _priorityCtrl = TextEditingController(text: '100');
  final _keywordsCtrl = TextEditingController();
  final _localesCtrl = TextEditingController();
  final _claimCtrl = TextEditingController();
  final _claimValueCtrl = TextEditingController();

  String? _profileSlug; // "<id>@<version>"
  String _routingKind = 'MatchAny';
  String _status = 'ACTIVE';
  int? _authResolverId;
  String _onUnresolved = 'REJECT';
  String _onOverrideDenied = 'IGNORE';
  List<String> _allowedOverrideProfiles = [];
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    final row = widget.existing;
    if (row != null) {
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
      _authResolverId = row.authResolverId;
      _onUnresolved = row.onUnresolved;
      _onOverrideDenied = row.onOverrideDenied;
      _allowedOverrideProfiles = List.of(row.allowedOverrideProfiles);
    }
  }

  @override
  void dispose() {
    for (final c in [
      _priorityCtrl,
      _keywordsCtrl,
      _localesCtrl,
      _claimCtrl,
      _claimValueCtrl
    ]) {
      c.dispose();
    }
    super.dispose();
  }

  RoutingRuleWire _buildRoutingRule() {
    List<String> csv(TextEditingController c) => c.text
        .split(',')
        .map((s) => s.trim())
        .where((s) => s.isNotEmpty)
        .toList();
    switch (_routingKind) {
      case 'KeywordPrefix':
        return RoutingRuleWire.keywordPrefix(csv(_keywordsCtrl));
      case 'Locale':
        return RoutingRuleWire.locale(csv(_localesCtrl));
      case 'EndUserClaim':
        return RoutingRuleWire.endUserClaim(
            _claimCtrl.text.trim(), _claimValueCtrl.text.trim());
      default:
        return RoutingRuleWire.matchAny();
    }
  }

  String? _validate() {
    if (_profileSlug == null) return 'Choose a profile.';
    final priority = int.tryParse(_priorityCtrl.text.trim());
    if (priority == null || priority < 0) {
      return 'Priority must be a non-negative whole number.';
    }
    switch (_routingKind) {
      case 'KeywordPrefix':
        if (_keywordsCtrl.text.trim().isEmpty) return 'At least one keyword is required.';
        break;
      case 'Locale':
        if (_localesCtrl.text.trim().isEmpty) return 'At least one locale is required.';
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
    final err = _validate();
    if (err != null) {
      showSnack(context, err, error: true);
      return;
    }
    final parts = _profileSlug!.split('@');
    final profileId = parts[0];
    final profileVersion = int.tryParse(parts.length > 1 ? parts[1] : '1') ?? 1;
    final priority = int.tryParse(_priorityCtrl.text.trim()) ?? 100;
    // SMS never runs a resolver, so never persist resolver/override on it.
    final resolverId = widget.supportsResolver ? _authResolverId : null;
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      if (widget.existing == null) {
        await api.createChannelBinding(
            widget.tenant,
            ChannelBindingRequest(
              endpoint: ChannelEndpointWire(
                  kind: widget.endpointKind, id: widget.endpointId),
              profileId: profileId,
              profileVersion: profileVersion,
              routing: _buildRoutingRule(),
              sessionPolicy: SessionPolicyWire.defaults(),
              priority: priority,
              status: _status,
              authResolverId: resolverId,
              onUnresolved: _onUnresolved,
              allowedOverrideProfiles: _allowedOverrideProfiles,
              onOverrideDenied: _onOverrideDenied,
            ));
      } else {
        await api.updateChannelBinding(
            widget.tenant,
            widget.existing!.id,
            ChannelBindingUpdate(
              profileId: profileId,
              profileVersion: profileVersion,
              routing: _buildRoutingRule(),
              sessionPolicy: SessionPolicyWire.defaults(),
              priority: priority,
              status: _status,
              authResolverId: resolverId,
              onUnresolved: _onUnresolved,
              allowedOverrideProfiles: _allowedOverrideProfiles,
              onOverrideDenied: _onOverrideDenied,
            ));
      }
      ref.invalidate(channelBindingsProvider(widget.tenant));
      if (!mounted) return;
      Navigator.pop(context);
      showSnack(context, 'Route saved');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final profiles = ref.watch(profilesProvider);
    final resolvers = ref.watch(authResolversProvider(widget.tenant));
    return AlertDialog(
      title: Text(widget.existing == null ? 'Add route' : 'Edit route'),
      content: SizedBox(
        width: 440,
        child: SingleChildScrollView(
          child: profiles.when(
            loading: () => const LinearProgressIndicator(),
            error: (e, _) => Text(formatError(e)),
            data: (profileList) {
              final tenantProfiles =
                  profileList.where((p) => p.tenant == widget.tenant).toList();
              return Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  DropdownButtonFormField<String>(
                    initialValue: _profileSlug,
                    isExpanded: true,
                    decoration: const InputDecoration(labelText: 'Profile'),
                    items: tenantProfiles
                        .map((p) => DropdownMenuItem(
                              value: '${p.id}@${p.version}',
                              child: Text('${p.displayName} (${p.id}@${p.version})',
                                  overflow: TextOverflow.ellipsis),
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
                          value: 'MatchAny', child: Text('Match every message')),
                      DropdownMenuItem(
                          value: 'KeywordPrefix',
                          child: Text('Body starts with keyword')),
                      DropdownMenuItem(
                          value: 'Locale', child: Text('End-user locale matches')),
                      DropdownMenuItem(
                          value: 'EndUserClaim',
                          child: Text('Principal claim equals value')),
                    ],
                    onChanged: (v) => setState(() => _routingKind = v ?? 'MatchAny'),
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
                              'BCP-47, e.g. en-US, es. Language-only entries also match variants.'),
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
                            'Lower = evaluated first. 10/20/30 leaves room to insert later.'),
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
                    onChanged: (v) => setState(() => _status = v ?? 'ACTIVE'),
                  ),
                  if (widget.supportsResolver) ...[
                    const SizedBox(height: 16),
                    _AuthResolverSection(
                      resolvers: resolvers,
                      selectedId: _authResolverId,
                      onUnresolved: _onUnresolved,
                      onResolverChanged: (id) => setState(() => _authResolverId = id),
                      onUnresolvedChanged: (v) =>
                          setState(() => _onUnresolved = v ?? 'REJECT'),
                    ),
                    if (_authResolverId != null) ...[
                      const SizedBox(height: 16),
                      Text('Profile override guardrails',
                          style: Theme.of(context).textTheme.titleSmall),
                      const SizedBox(height: 8),
                      ProfileAllowlistField(
                        profiles: tenantProfiles,
                        selected: _allowedOverrideProfiles,
                        onChanged: (v) =>
                            setState(() => _allowedOverrideProfiles = v),
                      ),
                      const SizedBox(height: 12),
                      DropdownButtonFormField<String>(
                        initialValue: _onOverrideDenied,
                        isExpanded: true,
                        decoration: const InputDecoration(
                            labelText: 'When an override is denied',
                            helperText:
                                'IGNORE runs the bound profile; REJECT drops the inbound.'),
                        items: const [
                          DropdownMenuItem(
                              value: 'IGNORE',
                              child: Text('Ignore — use the bound profile')),
                          DropdownMenuItem(
                              value: 'REJECT',
                              child: Text('Reject — drop the inbound')),
                        ],
                        onChanged: (v) =>
                            setState(() => _onOverrideDenied = v ?? 'IGNORE'),
                      ),
                    ],
                  ],
                ],
              );
            },
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: _saving ? null : () => Navigator.pop(context),
            child: const Text('Cancel')),
        FilledButton(
          onPressed: _saving ? null : _save,
          child: _saving
              ? const SizedBox.square(
                  dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Save'),
        ),
      ],
    );
  }
}

class _AuthResolverSection extends StatelessWidget {
  const _AuthResolverSection({
    required this.resolvers,
    required this.selectedId,
    required this.onUnresolved,
    required this.onResolverChanged,
    required this.onUnresolvedChanged,
  });

  final AsyncValue<AuthResolverListResponse> resolvers;
  final int? selectedId;
  final String onUnresolved;
  final ValueChanged<int?> onResolverChanged;
  final ValueChanged<String?> onUnresolvedChanged;

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
          Text('Identity (auth resolver)',
              style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 4),
          Text(
              'When set, every inbound on this number runs the resolver to mint a '
              'tenant JWT before the engine starts. Leave empty for an FAQ-shape '
              'bot — the engine sees only the verified phone with no tenant identity.',
              style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 8),
          resolvers.when(
            loading: () => const LinearProgressIndicator(),
            error: (e, _) => Text(formatError(e),
                style: TextStyle(color: Theme.of(context).colorScheme.error)),
            data: (data) {
              final items = <DropdownMenuItem<int?>>[
                const DropdownMenuItem<int?>(
                    value: null, child: Text('(none — FAQ mode)')),
                for (final r in data.resolvers.where((r) => r.status == 'ACTIVE'))
                  DropdownMenuItem<int?>(
                    value: r.id,
                    child: Text('${r.name} · ${r.kind}'),
                  ),
              ];
              final knownIds = data.resolvers.map((r) => r.id).toSet();
              final value = selectedId != null && knownIds.contains(selectedId)
                  ? selectedId
                  : null;
              return DropdownButtonFormField<int?>(
                initialValue: value,
                isExpanded: true,
                decoration:
                    const InputDecoration(labelText: 'Auth resolver', isDense: true),
                items: items,
                onChanged: onResolverChanged,
              );
            },
          ),
          if (selectedId != null) ...[
            const SizedBox(height: 8),
            DropdownButtonFormField<String>(
              initialValue: onUnresolved,
              isExpanded: true,
              decoration: const InputDecoration(
                labelText: 'When the resolver returns "user not found"',
                isDense: true,
              ),
              items: const [
                DropdownMenuItem(
                    value: 'REJECT', child: Text('REJECT — drop the message')),
                DropdownMenuItem(
                    value: 'ANONYMOUS',
                    child: Text('ANONYMOUS — fall back to baseline channel auth')),
              ],
              onChanged: onUnresolvedChanged,
            ),
          ],
        ],
      ),
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

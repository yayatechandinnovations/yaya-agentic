import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/start_flow.dart';
import '../shared/admin_shared.dart';
import 'flow_builder.dart';
import 'flow_drafts.dart';
import 'flow_templates.dart';

/// Session Start Flow admin: author the ordered, data-bound tool steps that run
/// deterministically when a conversation starts — recognize / verify / register
/// / enrich, and shape the opening message. Built graphically (no JSON). Bind a
/// flow to a WhatsApp sender or chat entry point (their "Start flow" field), or
/// to a profile default. See `docs/design/session-start-flows.md`.

final startFlowsProvider =
    FutureProvider.family<List<StartFlowResponse>, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listStartFlows(tenantId);
});

final _toolIdsProvider = FutureProvider.family<List<String>, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  final tools = await api.listTools(tenant: tenantId);
  return tools.map((t) => t.id).toList()..sort();
});

class StartFlowsAdminScreen extends ConsumerWidget {
  const StartFlowsAdminScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Start flows');
    }
    final async = ref.watch(startFlowsProvider(tenant));
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Start flows', style: Theme.of(context).textTheme.headlineSmall),
                    const SizedBox(height: 4),
                    Text(
                      'Automation that runs when a conversation starts. Bind a flow to a sender / '
                      'chat entry point, or set it as a profile default.',
                      style: Theme.of(context).textTheme.bodyMedium
                          ?.copyWith(color: Theme.of(context).colorScheme.outline),
                    ),
                  ],
                ),
              ),
              FilledButton.icon(
                onPressed: () => _openEditor(context, ref, tenant, null),
                icon: const Icon(Icons.add),
                label: const Text('New flow'),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Expanded(
            child: async.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => Center(child: Text(formatError(e))),
              data: (flows) => flows.isEmpty
                  ? const Center(child: Text('No start flows yet. Create one with “New flow”.'))
                  : ListView.separated(
                      itemCount: flows.length,
                      separatorBuilder: (_, __) => const SizedBox(height: 8),
                      itemBuilder: (_, i) => _FlowCard(tenant: tenant, flow: flows[i]),
                    ),
            ),
          ),
        ],
      ),
    );
  }
}

Future<void> _openEditor(
    BuildContext context, WidgetRef ref, String tenant, StartFlowResponse? existing) async {
  final saved = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (_) => _FlowEditorDialog(tenant: tenant, existing: existing),
  );
  if (saved == true) ref.invalidate(startFlowsProvider(tenant));
}

class _FlowCard extends ConsumerWidget {
  const _FlowCard({required this.tenant, required this.flow});
  final String tenant;
  final StartFlowResponse flow;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final statusColor = flow.isActive
        ? Colors.green
        : (flow.isArchived ? theme.colorScheme.outline : theme.colorScheme.tertiary);
    return Card(
      child: ListTile(
        leading: Icon(Icons.play_circle_outline, color: theme.colorScheme.primary),
        title: Text(flow.name),
        subtitle: Text('${flow.id}  ·  v${flow.version}  ·  ${flow.stepCount} step(s)  ·  ${flow.greetingMode}'),
        trailing: Wrap(spacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
          Chip(
            label: Text(flow.status, style: const TextStyle(fontSize: 11)),
            backgroundColor: statusColor.withValues(alpha: 0.15),
            side: BorderSide(color: statusColor.withValues(alpha: 0.4)),
            visualDensity: VisualDensity.compact,
          ),
          if (flow.isDraft)
            TextButton(onPressed: () => _publish(context, ref), child: const Text('Publish')),
          TextButton(onPressed: () => _openEditor(context, ref, tenant, flow), child: const Text('Edit')),
        ]),
      ),
    );
  }

  Future<void> _publish(BuildContext context, WidgetRef ref) async {
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.publishStartFlow(tenant, flow.id);
      ref.invalidate(startFlowsProvider(tenant));
      if (context.mounted) showSnack(context, 'Published ${flow.id}');
    } catch (e) {
      if (context.mounted) showSnack(context, formatError(e), error: true);
    }
  }
}

// ---- editor dialog -----------------------------------------------------

class _FlowEditorDialog extends ConsumerStatefulWidget {
  const _FlowEditorDialog({required this.tenant, this.existing});
  final String tenant;
  final StartFlowResponse? existing;

  @override
  ConsumerState<_FlowEditorDialog> createState() => _FlowEditorDialogState();
}

class _FlowEditorDialogState extends ConsumerState<_FlowEditorDialog> {
  late FlowDraft _draft;
  late bool _editing;
  bool _publish = false;
  bool _showTemplates = false;
  bool _busy = false;
  int _rev = 0; // bumped when the draft is wholesale-replaced, to re-init fields

  @override
  void initState() {
    super.initState();
    _editing = widget.existing != null;
    _draft = widget.existing != null ? FlowDraft.fromResponse(widget.existing!) : FlowDraft();
  }

  void _loadTemplate(FlowDraft t) {
    setState(() {
      _draft = t;
      _showTemplates = false;
      _rev++;
    });
  }

  Future<void> _save() async {
    if (_draft.id.trim().isEmpty || _draft.name.trim().isEmpty) {
      showSnack(context, 'Flow id and name are required', error: true);
      return;
    }
    setState(() => _busy = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final body = _draft.toRequest(publish: _publish);
      if (_editing) {
        await api.updateStartFlow(widget.tenant, body.id, body);
      } else {
        await api.createStartFlow(widget.tenant, body);
      }
      if (mounted) {
        showSnack(context, _editing ? 'Saved new version of ${body.id}' : 'Created ${body.id}');
        Navigator.of(context).pop(true);
      }
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final toolIds = ref.watch(_toolIdsProvider(widget.tenant)).valueOrNull ?? const <String>[];
    return Dialog(
      insetPadding: const EdgeInsets.all(24),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 900, maxHeight: 820),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _header(context),
            const Divider(height: 1),
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(20),
                child: KeyedSubtree(
                  key: ValueKey(_rev),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _identityFields(),
                      const SizedBox(height: 16),
                      _steps(context, toolIds),
                      const SizedBox(height: 20),
                      _greeting(context),
                    ],
                  ),
                ),
              ),
            ),
            const Divider(height: 1),
            _footer(context),
          ],
        ),
      ),
    );
  }

  Widget _header(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 12, 12),
        child: Row(children: [
          Expanded(
            child: Text(_editing ? 'Edit flow — saves a new version' : 'New start flow',
                style: Theme.of(context).textTheme.titleLarge),
          ),
          if (!_editing) ...[
            if (!_showTemplates)
              TextButton.icon(
                onPressed: () => setState(() => _showTemplates = true),
                icon: const Icon(Icons.auto_awesome_outlined, size: 18),
                label: const Text('Start from a template'),
              )
            else
              _templatePicker(),
          ],
          IconButton(onPressed: () => Navigator.of(context).pop(false), icon: const Icon(Icons.close)),
        ]),
      );

  Widget _templatePicker() => SizedBox(
        width: 280,
        child: DropdownButtonFormField<String>(
          initialValue: null,
          isExpanded: true,
          decoration: const InputDecoration(
              labelText: 'Choose a template', border: OutlineInputBorder(), isDense: true),
          items: kFlowTemplates.keys
              .map((k) => DropdownMenuItem(value: k, child: Text(k, overflow: TextOverflow.ellipsis)))
              .toList(),
          onChanged: (k) => k == null ? null : _loadTemplate(kFlowTemplates[k]!()),
        ),
      );

  Widget _identityFields() => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        TextFormField(
          initialValue: _draft.id,
          enabled: !_editing,
          decoration: const InputDecoration(
            labelText: 'Flow id', border: OutlineInputBorder(), isDense: true,
            helperText: 'Stable id (a-z, 0-9, -, _). Bind channels / profiles to this.',
          ),
          onChanged: (v) => _draft.id = v.trim(),
        ),
        const SizedBox(height: 12),
        TextFormField(
          initialValue: _draft.name,
          decoration: const InputDecoration(labelText: 'Name', border: OutlineInputBorder(), isDense: true),
          onChanged: (v) => _draft.name = v,
        ),
        const SizedBox(height: 12),
        TextFormField(
          initialValue: _draft.description,
          decoration:
              const InputDecoration(labelText: 'Description', border: OutlineInputBorder(), isDense: true),
          onChanged: (v) => _draft.description = v,
        ),
      ]);

  Widget _steps(BuildContext context, List<String> toolIds) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Steps', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          FlowStepsEditor(flow: _draft, toolIds: toolIds, onChanged: () => setState(() {})),
        ],
      );

  Widget _greeting(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Opening message', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          GreetingEditor(greeting: _draft.greeting, flow: _draft, onChanged: () => setState(() {})),
        ],
      );

  Widget _footer(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(20, 10, 20, 12),
        child: Row(children: [
          if (!_editing)
            Row(mainAxisSize: MainAxisSize.min, children: [
              Checkbox(value: _publish, onChanged: (v) => setState(() => _publish = v ?? false)),
              const Text('Publish immediately'),
            ])
          else
            Text('New versions start as DRAFT — publish from the list.',
                style: Theme.of(context).textTheme.bodySmall
                    ?.copyWith(color: Theme.of(context).colorScheme.outline)),
          const Spacer(),
          TextButton(
              onPressed: _busy ? null : () => Navigator.of(context).pop(false),
              child: const Text('Cancel')),
          const SizedBox(width: 8),
          FilledButton(
            onPressed: _busy ? null : _save,
            child: Text(_busy ? 'Saving…' : (_editing ? 'Save new version' : 'Create')),
          ),
        ]),
      );
}

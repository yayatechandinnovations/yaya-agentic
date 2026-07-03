import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/start_flow.dart';
import '../shared/admin_shared.dart';

/// Session Start Flow admin: author the ordered, data-bound tool steps that run
/// deterministically when a conversation starts — recognize / verify / register
/// / enrich, and shape the opening message. Bind a flow to a WhatsApp sender or
/// chat entry point (their "Start flow id" field) or to a profile default.
/// See `docs/design/session-start-flows.md`.

final startFlowsProvider =
    FutureProvider.family<List<StartFlowResponse>, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listStartFlows(tenantId);
});

/// Carries a selected flow into the create/edit form for "Clone & edit".
final _formSeedProvider = StateProvider<StartFlowResponse?>((ref) => null);

const _encoder = JsonEncoder.withIndent('  ');

class StartFlowsAdminScreen extends ConsumerWidget {
  const StartFlowsAdminScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Start flows');
    }
    return AdminTwoPane(
      title: 'Start flows',
      subtitle: 'Automation that runs when a conversation starts — top to bottom. '
          'Bind a flow to a sender / chat entry point, or a profile default.',
      list: _List(tenant: tenant),
      form: _CreateForm(tenant: tenant),
    );
  }
}

class _List extends ConsumerWidget {
  const _List({required this.tenant});
  final String tenant;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(startFlowsProvider(tenant));
    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(child: Text(formatError(e))),
      data: (flows) {
        if (flows.isEmpty) {
          return const Center(child: Text('No start flows yet. Create one on the right.'));
        }
        return ListView.separated(
          itemCount: flows.length,
          separatorBuilder: (_, __) => const SizedBox(height: 8),
          itemBuilder: (_, i) => _FlowCard(tenant: tenant, flow: flows[i]),
        );
      },
    );
  }
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
    return EditableRecordCard(
      leading: Icon(Icons.play_circle_outline, color: theme.colorScheme.primary),
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(flow.name, style: theme.textTheme.titleSmall),
          const SizedBox(height: 2),
          Text('${flow.id}  ·  v${flow.version}  ·  ${flow.stepCount} step(s)  ·  ${flow.greetingMode}',
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.outline)),
        ],
      ),
      trailing: Chip(
        label: Text(flow.status, style: const TextStyle(fontSize: 11)),
        backgroundColor: statusColor.withValues(alpha: 0.15),
        side: BorderSide(color: statusColor.withValues(alpha: 0.4)),
        visualDensity: VisualDensity.compact,
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (flow.description != null && flow.description!.isNotEmpty)
            DetailRow(label: 'Description', value: flow.description!),
          DetailRow(label: 'Steps', value: _encoder.convert(flow.steps), mono: true),
          DetailRow(label: 'Greeting', value: _encoder.convert(flow.greeting), mono: true),
        ],
      ),
      extraActions: [
        if (flow.isDraft)
          FilledButton.tonalIcon(
            onPressed: () => _publish(context, ref),
            icon: const Icon(Icons.publish_outlined, size: 18),
            label: const Text('Publish'),
          ),
      ],
      onCloneEdit: () => ref.read(_formSeedProvider.notifier).state = flow,
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

// ---- create / edit form ------------------------------------------------

/// Starter templates — pre-fill the JSON editors with a working flow.
const _templates = <String, Map<String, dynamic>>{
  'Returning-customer recognition (LLM greeting)': {
    'id': 'returning-customer',
    'name': 'Returning customer recognition',
    'description': 'Look the user up by phone and greet a recognized customer.',
    'steps': [
      {
        'kind': 'RunTool',
        'key': 'lookup',
        'tool': {'value': 'account-lookup'},
        'inputs': [
          {
            'argName': 'phone',
            'source': {'kind': 'Channel', 'field': 'endUserPhone'},
            'mode': 'FILL_IF_MISSING'
          }
        ],
        'captures': [
          {
            'jsonPointer': '/name',
            'target': {'kind': 'Entity', 'name': 'customerName', 'visibleToLlm': true}
          }
        ],
        'next': [
          {
            'when': {
              'left': {'kind': 'StepOutput', 'stepKey': 'lookup', 'jsonPointer': '/found'},
              'op': 'EQ',
              'right': true
            },
            'target': {'kind': 'End', 'outcomeLabel': 'success'}
          }
        ],
        'onError': {'onError': 'ABORT', 'maxRetries': 0}
      }
    ],
    'greeting': {
      'mode': 'LLM_COMPOSED',
      'outcomeGreetingTemplates': {'success': 'Welcome back, {customerName}!'},
      'fallbackGreeting': 'Hi! How can I help you today?',
      'quickReplyTemplates': ['Track my order'],
      'llmOpening': {
        'instruction': 'Greet the returning customer warmly by name and offer to help — one sentence.',
        'briefingEntities': ['customerName'],
        'quickReplyHints': [],
        'stream': false
      }
    }
  },
  'Phone verification (OTP)': {
    'id': 'phone-otp',
    'name': 'Phone verification',
    'description': 'Recognize the customer, ask for the code, verify it.',
    'steps': [
      {
        'kind': 'RunTool',
        'key': 'lookup',
        'tool': {'value': 'account-lookup'},
        'inputs': [],
        'captures': [
          {
            'jsonPointer': '/name',
            'target': {'kind': 'Entity', 'name': 'customerName', 'visibleToLlm': true}
          }
        ],
        'next': [],
        'onError': {'onError': 'ABORT', 'maxRetries': 0}
      },
      {
        'kind': 'Elicit',
        'key': 'otp',
        'promptTemplate': 'Enter the code we texted you, {customerName}',
        'quickReplyTemplates': [],
        'uiHintKind': 'otp_entry',
        'uiHintPayload': {},
        'captureTarget': {'kind': 'StepVar', 'name': 'code'},
        'next': []
      },
      {
        'kind': 'RunTool',
        'key': 'verify',
        'tool': {'value': 'verify-otp'},
        'inputs': [
          {
            'argName': 'code',
            'source': {'kind': 'Entity', 'entityName': 'code'},
            'mode': 'REQUIRED'
          }
        ],
        'captures': [
          {
            'jsonPointer': '/verified',
            'target': {'kind': 'Entity', 'name': 'phoneVerified', 'visibleToLlm': true}
          }
        ],
        'next': [],
        'onError': {'onError': 'ABORT', 'maxRetries': 0}
      }
    ],
    'greeting': {
      'mode': 'STATIC_TEMPLATE',
      'outcomeGreetingTemplates': {'success': "You're verified, {customerName}!"},
      'fallbackGreeting': 'Hi! How can I help?',
      'quickReplyTemplates': []
    }
  },
};

class _CreateForm extends ConsumerStatefulWidget {
  const _CreateForm({required this.tenant});
  final String tenant;

  @override
  ConsumerState<_CreateForm> createState() => _CreateFormState();
}

class _CreateFormState extends ConsumerState<_CreateForm> {
  final _id = TextEditingController();
  final _name = TextEditingController();
  final _description = TextEditingController();
  final _steps = TextEditingController(text: '[]');
  final _greeting =
      TextEditingController(text: '{\n  "mode": "STATIC_TEMPLATE",\n  "fallbackGreeting": "Hi!"\n}');
  bool _publish = false;
  bool _editing = false;
  bool _busy = false;

  @override
  void dispose() {
    for (final c in [_id, _name, _description, _steps, _greeting]) {
      c.dispose();
    }
    super.dispose();
  }

  void _reset() {
    setState(() {
      _editing = false;
      _publish = false;
      _id.clear();
      _name.clear();
      _description.clear();
      _steps.text = '[]';
      _greeting.text = '{\n  "mode": "STATIC_TEMPLATE",\n  "fallbackGreeting": "Hi!"\n}';
    });
  }

  void _loadFromFlow(StartFlowResponse f) {
    setState(() {
      _editing = true;
      _publish = false;
      _id.text = f.id;
      _name.text = f.name;
      _description.text = f.description ?? '';
      _steps.text = _encoder.convert(f.steps);
      _greeting.text = _encoder.convert(f.greeting);
    });
  }

  void _applyTemplate(Map<String, dynamic> t) {
    setState(() {
      _editing = false;
      _id.text = t['id'] as String;
      _name.text = t['name'] as String;
      _description.text = (t['description'] ?? '') as String;
      _steps.text = _encoder.convert(t['steps']);
      _greeting.text = _encoder.convert(t['greeting']);
    });
  }

  Future<void> _save() async {
    final List<dynamic> steps;
    final Map<String, dynamic> greeting;
    try {
      steps = jsonDecode(_steps.text) as List<dynamic>;
      greeting = Map<String, dynamic>.from(jsonDecode(_greeting.text) as Map);
    } catch (e) {
      showSnack(context, 'Steps / greeting must be valid JSON: $e', error: true);
      return;
    }
    if (_id.text.trim().isEmpty || _name.text.trim().isEmpty) {
      showSnack(context, 'Id and name are required', error: true);
      return;
    }
    setState(() => _busy = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final body = StartFlowRequest(
        id: _id.text.trim(),
        name: _name.text.trim(),
        description: _description.text.trim().isEmpty ? null : _description.text.trim(),
        steps: steps,
        greeting: greeting,
        policy: const {}, // backend applies FlowPolicy.defaults() for an empty policy
        publish: _publish,
      );
      if (_editing) {
        await api.updateStartFlow(widget.tenant, body.id, body);
      } else {
        await api.createStartFlow(widget.tenant, body);
      }
      ref.invalidate(startFlowsProvider(widget.tenant));
      if (mounted) {
        showSnack(context, _editing ? 'Saved new version of ${body.id}' : 'Created ${body.id}');
        _reset();
      }
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.listen<StartFlowResponse?>(_formSeedProvider, (_, next) {
      if (next != null) {
        _loadFromFlow(next);
        ref.read(_formSeedProvider.notifier).state = null;
      }
    });

    return SingleChildScrollView(
      child: FormCard(
        title: _editing ? 'Edit flow (saves a new version)' : 'New start flow',
        actions: [
          if (_editing)
            TextButton(onPressed: _busy ? null : _reset, child: const Text('New')),
          FilledButton(
            onPressed: _busy ? null : _save,
            child: Text(_busy ? 'Saving…' : (_editing ? 'Save new version' : 'Create')),
          ),
        ],
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            DropdownButtonFormField<String>(
              initialValue: null,
              decoration: const InputDecoration(
                  labelText: 'Start from a template', border: OutlineInputBorder()),
              items: _templates.keys
                  .map((k) => DropdownMenuItem(value: k, child: Text(k)))
                  .toList(),
              onChanged: _busy ? null : (k) => k == null ? null : _applyTemplate(_templates[k]!),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _id,
              enabled: !_editing,
              decoration: const InputDecoration(
                  labelText: 'Flow id', border: OutlineInputBorder(),
                  helperText: 'Stable id (a-z, 0-9, -, _). Bind channels/profiles to this.'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _name,
              decoration: const InputDecoration(labelText: 'Name', border: OutlineInputBorder()),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _description,
              decoration: const InputDecoration(labelText: 'Description', border: OutlineInputBorder()),
            ),
            const SizedBox(height: 12),
            _jsonField('Steps (JSON)', _steps, 12),
            const SizedBox(height: 12),
            _jsonField('Greeting policy (JSON)', _greeting, 8),
            const SizedBox(height: 12),
            CheckboxListTile(
              value: _publish,
              onChanged: _busy || _editing ? null : (v) => setState(() => _publish = v ?? false),
              title: const Text('Publish immediately'),
              subtitle: Text(_editing
                  ? 'New versions start as DRAFT — publish from the list.'
                  : 'Otherwise saved as DRAFT.'),
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
            ),
          ],
        ),
      ),
    );
  }

  Widget _jsonField(String label, TextEditingController c, int lines) {
    return TextField(
      controller: c,
      maxLines: lines,
      style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
      decoration: InputDecoration(
        labelText: label,
        border: const OutlineInputBorder(),
        alignLabelWithHint: true,
      ),
    );
  }
}

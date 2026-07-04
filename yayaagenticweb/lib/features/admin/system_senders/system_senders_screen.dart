import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/system_sender.dart';
import '../shared/admin_shared.dart';

/// Refetched after every write so the list reflects the latest config.
final systemSendersProvider =
    FutureProvider.family<List<SystemSenderResponse>, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listSystemSenders(tenantId);
});

/// The "System Inbound" configuration surface (system-messaging-design.md §14):
/// enable a linked application to inject messages into a user's chat, choose HOW
/// it proves trust, whether the LLM mediates, and which tool answers.
class SystemSendersScreen extends ConsumerStatefulWidget {
  const SystemSendersScreen({super.key});

  @override
  ConsumerState<SystemSendersScreen> createState() => _SystemSendersScreenState();
}

class _SystemSendersScreenState extends ConsumerState<SystemSendersScreen> {
  final _entryPointCtrl = TextEditingController();
  final _displayNameCtrl = TextEditingController();
  final _credentialRefCtrl = TextEditingController();
  final _scopesCtrl = TextEditingController(); // one scope per line
  final _responseToolCtrl = TextEditingController();
  final _subjectsCtrl = TextEditingController(); // allowed subjects, one per line
  String _authenticator = 'shared-secret-jwt';
  String _mediation = 'VERBATIM';
  bool _enabled = false;
  String? _editingId;
  bool _saving = false;

  static const _authenticators = [
    'shared-secret-jwt',
    'oidc',
    'api-key',
  ];

  void _resetForm() {
    setState(() {
      _editingId = null;
      _entryPointCtrl.clear();
      _displayNameCtrl.clear();
      _credentialRefCtrl.clear();
      _scopesCtrl.clear();
      _responseToolCtrl.clear();
      _subjectsCtrl.clear();
      _authenticator = 'shared-secret-jwt';
      _mediation = 'VERBATIM';
      _enabled = false;
    });
  }

  void _loadIntoForm(SystemSenderResponse row) {
    setState(() {
      _editingId = row.id;
      _entryPointCtrl.text = row.entryPoint;
      _displayNameCtrl.text = row.displayName;
      _credentialRefCtrl.text = row.trust.credentialRef;
      _scopesCtrl.text = row.trust.requiredScopes.join('\n');
      _responseToolCtrl.text = row.responseToolId ?? '';
      _subjectsCtrl.text = row.addressing.allowedSubjects.join('\n');
      _authenticator = _authenticators.contains(row.trust.authenticatorName)
          ? row.trust.authenticatorName
          : 'shared-secret-jwt';
      _mediation = row.mediation;
      _enabled = row.enabled;
    });
  }

  @override
  void dispose() {
    _entryPointCtrl.dispose();
    _displayNameCtrl.dispose();
    _credentialRefCtrl.dispose();
    _scopesCtrl.dispose();
    _responseToolCtrl.dispose();
    _subjectsCtrl.dispose();
    super.dispose();
  }

  List<String> _lines(TextEditingController c) =>
      c.text.split('\n').map((s) => s.trim()).where((s) => s.isNotEmpty).toList();

  Future<void> _save() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) {
      showSnack(context, 'select a tenant first', error: true);
      return;
    }
    if (_entryPointCtrl.text.trim().isEmpty || _displayNameCtrl.text.trim().isEmpty) {
      showSnack(context, 'entry point and display name are required', error: true);
      return;
    }
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final body = SystemSenderRequest(
        entryPoint: _entryPointCtrl.text.trim(),
        displayName: _displayNameCtrl.text.trim(),
        enabled: _enabled,
        trust: SenderTrust(
          authenticatorName: _authenticator,
          requiredScopes: _lines(_scopesCtrl),
          credentialRef: _credentialRefCtrl.text.trim(),
        ),
        mediation: _mediation,
        addressing: AddressingPolicy(
          allowedKinds: const ['PRINCIPAL'],
          allowedSubjects: _lines(_subjectsCtrl),
        ),
        responseToolId:
            _responseToolCtrl.text.trim().isEmpty ? null : _responseToolCtrl.text.trim(),
      );
      if (_editingId == null) {
        await api.createSystemSender(tenant, body);
      } else {
        await api.updateSystemSender(tenant, _editingId!, body);
      }
      ref.invalidate(systemSendersProvider(tenant));
      if (!mounted) return;
      final wasEdit = _editingId != null;
      _resetForm();
      showSnack(context, wasEdit ? 'sender updated' : 'sender created');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _delete(SystemSenderResponse row) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final ok = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('Delete system sender?'),
            content: Text('"${row.displayName}" will no longer be able to inject messages.'),
            actions: [
              TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
              FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Delete')),
            ],
          ),
        ) ??
        false;
    if (!ok) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.deleteSystemSender(tenant, row.id);
      ref.invalidate(systemSendersProvider(tenant));
      if (mounted) showSnack(context, 'sender deleted');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  Future<void> _simulate(SystemSenderResponse row) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final subjectCtrl = TextEditingController();
    final bodyCtrl = TextEditingController(text: 'cash closed: 1000');
    final ok = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: Text('Simulate a message from ${row.displayName}'),
            content: Column(mainAxisSize: MainAxisSize.min, children: [
              TextField(
                controller: subjectCtrl,
                decoration: const InputDecoration(labelText: 'recipient subject (operator id)'),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: bodyCtrl,
                minLines: 2,
                maxLines: 4,
                decoration: const InputDecoration(labelText: 'message body'),
              ),
            ]),
            actions: [
              TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
              FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Send')),
            ],
          ),
        ) ??
        false;
    if (!ok) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      final res = await api.simulateSystemMessage(tenant, row.id, {
        'subject': subjectCtrl.text.trim(),
        'body': bodyCtrl.text.trim(),
        'originatorName': row.displayName,
        'subjectLine': 'simulated',
      });
      if (mounted) showSnack(context, 'delivered: ${res['disposition']}');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'System senders');
    }
    final senders = ref.watch(systemSendersProvider(tenant));

    return AdminTwoPane(
      title: 'System inbound senders',
      subtitle:
          'Let a linked application (POS / ERP / back-office) inject messages into a user\'s chat for '
          'tenant "$tenant". Trust is a configured Authenticator; mediation decides whether the agent '
          'frames the message; the response tool carries the reply back.',
      list: senders.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e), style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (list) => list.isEmpty
            ? const Center(child: Text('No system senders yet.'))
            : ListView.separated(
                itemBuilder: (_, i) => _SenderTile(
                  row: list[i],
                  onEdit: () => _loadIntoForm(list[i]),
                  onDelete: () => _delete(list[i]),
                  onSimulate: () => _simulate(list[i]),
                ),
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemCount: list.length,
              ),
      ),
      form: SingleChildScrollView(
        child: FormCard(
          title: _editingId == null ? 'Add system sender' : 'Editing "${_displayNameCtrl.text}"',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                controller: _displayNameCtrl,
                decoration: const InputDecoration(
                  labelText: 'display name',
                  helperText: 'Shown to the user, e.g. "Cash Register #3".',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _entryPointCtrl,
                decoration: const InputDecoration(
                  labelText: 'chat entry point id',
                  helperText: 'The chat this sender delivers into (the chat the user added).',
                ),
              ),
              const SizedBox(height: 12),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Enable inbound from systems'),
                value: _enabled,
                onChanged: (v) => setState(() => _enabled = v),
              ),
              const Divider(),
              Text('Trust', style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                initialValue: _authenticator,
                decoration: const InputDecoration(labelText: 'security mechanism'),
                items: _authenticators
                    .map((a) => DropdownMenuItem(value: a, child: Text(a)))
                    .toList(),
                onChanged: (v) => setState(() => _authenticator = v ?? 'shared-secret-jwt'),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _credentialRefCtrl,
                decoration: const InputDecoration(
                  labelText: 'credential ref (iss / key id / issuer)',
                  helperText: 'Which secret/key to accept. Blank = any the authenticator trusts.',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _scopesCtrl,
                minLines: 1,
                maxLines: 3,
                decoration: const InputDecoration(
                  labelText: 'required scopes (one per line)',
                  helperText: 'Beyond the baseline system:inbound:send.',
                ),
              ),
              const Divider(),
              Text('Mediation & reply', style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                initialValue: _mediation,
                decoration: const InputDecoration(labelText: 'mediation'),
                items: const [
                  DropdownMenuItem(value: 'VERBATIM', child: Text('VERBATIM — deliver as-is')),
                  DropdownMenuItem(
                      value: 'LLM_MEDIATED', child: Text('LLM_MEDIATED — agent frames it')),
                ],
                onChanged: (v) => setState(() => _mediation = v ?? 'VERBATIM'),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _responseToolCtrl,
                decoration: const InputDecoration(
                  labelText: 'response tool id (optional)',
                  helperText: 'The tool the agent calls to answer, e.g. system.reply.',
                ),
              ),
              const Divider(),
              Text('Addressing', style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 8),
              TextField(
                controller: _subjectsCtrl,
                minLines: 1,
                maxLines: 4,
                decoration: const InputDecoration(
                  labelText: 'allowed subjects (one per line, optional)',
                  helperText: 'Empty = any subject the sender\'s scope permits.',
                ),
              ),
            ],
          ),
          actions: [
            if (_editingId != null)
              TextButton(onPressed: _saving ? null : _resetForm, child: const Text('Cancel')),
            FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
                  ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : Text(_editingId == null ? 'Add' : 'Save'),
            ),
          ],
        ),
      ),
    );
  }
}

class _SenderTile extends StatelessWidget {
  const _SenderTile({
    required this.row,
    required this.onEdit,
    required this.onDelete,
    required this.onSimulate,
  });

  final SystemSenderResponse row;
  final VoidCallback onEdit;
  final VoidCallback onDelete;
  final VoidCallback onSimulate;

  @override
  Widget build(BuildContext context) {
    return EditableRecordCard(
      onCloneEdit: onEdit,
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Expanded(child: Text(row.displayName, style: Theme.of(context).textTheme.titleSmall)),
            Chip(
              visualDensity: VisualDensity.compact,
              label: Text(row.enabled ? 'enabled' : 'disabled'),
              backgroundColor: row.enabled
                  ? Theme.of(context).colorScheme.secondaryContainer
                  : Theme.of(context).colorScheme.surfaceContainerHighest,
            ),
          ]),
          Text('${row.trust.authenticatorName} · ${row.mediation}',
              style: const TextStyle(fontSize: 12, fontStyle: FontStyle.italic)),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'entry point', value: row.entryPoint, mono: true),
          DetailRow(label: 'credential', value: row.trust.credentialRef.isEmpty ? '(any)' : row.trust.credentialRef),
          if (row.responseToolId != null)
            DetailRow(label: 'response tool', value: row.responseToolId!, mono: true),
          DetailRow(
            label: 'addressing',
            value: row.addressing.allowedSubjects.isEmpty
                ? 'any ${row.addressing.allowedKinds.join("/")}'
                : row.addressing.allowedSubjects.join(', '),
          ),
        ],
      ),
      extraActions: [
        TextButton.icon(
          onPressed: onSimulate,
          icon: const Icon(Icons.send_outlined, size: 18),
          label: const Text('Simulate'),
        ),
        TextButton.icon(
          onPressed: onDelete,
          icon: const Icon(Icons.delete_outline, size: 18),
          label: const Text('Delete'),
          style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
        ),
      ],
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/chat_entry_point.dart';
import '../../../models/admin/system_sender.dart';
import '../../../models/admin/whatsapp_sender.dart';
import '../shared/admin_shared.dart';

/// Refetched after every write so the list reflects the latest config.
final systemSendersProvider =
    FutureProvider.family<List<SystemSenderResponse>, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listSystemSenders(tenantId);
});

// ---- Option sources for the config dropdowns -------------------------------
// The form binds configured resources, not free text: an operator picks a JWT
// secret / OIDC issuer / tool that already exists rather than retyping its id.

/// Chat entry points this sender can deliver into (value = id, label = handle).
final _entryPointsProvider =
    FutureProvider.family<List<ChatEntryPointResponse>, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listChatEntryPoints(tenantId);
});

/// WhatsApp numbers this tenant owns — the optional proactive WhatsApp leg.
final _whatsappSendersProvider =
    FutureProvider.family<List<WhatsAppSenderResponse>, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listWhatsAppSenders(tenantId);
});

/// `iss` of every configured HS256 shared secret — the credentialRef a
/// `shared-secret-jwt` sender pins (matched against the token's iss).
final _jwtSecretIssProvider =
    FutureProvider.family<List<String>, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  final rows = await api.listTenantJwtSecrets(tenantId);
  return rows.map((r) => r.iss).where((s) => s.isNotEmpty).toList()..sort();
});

/// `iss` of every trusted OIDC issuer — the credentialRef an `oidc` sender pins.
final _oidcIssuerIssProvider =
    FutureProvider.family<List<String>, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  final rows = await api.listTenantOidcIssuers(tenantId);
  return rows.map((r) => r.iss).where((s) => s.isNotEmpty).toList()..sort();
});

/// Tool ids the agent could call to answer (the response tool).
final _toolIdsProvider =
    FutureProvider.family<List<String>, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  final tools = await api.listTools(tenant: tenantId);
  return tools.map((t) => t.id).toList()..sort();
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
  String _whatsappSenderId = '';
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
      _whatsappSenderId = '';
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
      _whatsappSenderId = row.whatsappSenderId ?? '';
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

  /// A dropdown backed by a live list of configured values (jwt iss, oidc iss,
  /// tool ids). Falls back to a plain text field if the list can't be loaded so
  /// the operator is never blocked. Tolerates a stored value that no longer
  /// exists by surfacing it as a "(not configured)" option instead of dropping it.
  Widget _asyncStringDropdown({
    required String label,
    String? helperText,
    required String emptyLabel,
    required String emptyListHelper,
    required String currentValue,
    required AsyncValue<List<String>> options,
    required ValueChanged<String> onChanged,
  }) {
    return options.when(
      loading: () => InputDecorator(
        decoration: InputDecoration(labelText: label, helperText: helperText),
        child: const Padding(
          padding: EdgeInsets.symmetric(vertical: 10),
          child: LinearProgressIndicator(),
        ),
      ),
      error: (_, _) => TextFormField(
        initialValue: currentValue,
        decoration: InputDecoration(
          labelText: label,
          helperText: 'Could not load options — enter the value manually.',
        ),
        onChanged: onChanged,
      ),
      data: (list) {
        final missing = currentValue.isNotEmpty && !list.contains(currentValue);
        return DropdownButtonFormField<String>(
          initialValue: currentValue,
          isExpanded: true,
          decoration: InputDecoration(
            labelText: label,
            helperText: list.isEmpty ? emptyListHelper : helperText,
          ),
          items: [
            DropdownMenuItem(
              value: '',
              child: Text(emptyLabel, style: const TextStyle(fontStyle: FontStyle.italic)),
            ),
            ...list.map((v) => DropdownMenuItem(value: v, child: Text(v, overflow: TextOverflow.ellipsis))),
            if (missing)
              DropdownMenuItem(value: currentValue, child: Text('$currentValue  (not configured)')),
          ],
          onChanged: (v) => onChanged(v ?? ''),
        );
      },
    );
  }

  /// The credential the sender's token must carry (matched against its `iss`).
  /// Which resource that is depends on the chosen authenticator, so the field
  /// itself changes: a JWT-secret picker, an OIDC-issuer picker, or free text.
  Widget _credentialField(AsyncValue<List<String>> jwtIss, AsyncValue<List<String>> oidcIss) {
    switch (_authenticator) {
      case 'shared-secret-jwt':
        return _asyncStringDropdown(
          label: 'JWT shared secret (iss)',
          helperText: 'The configured shared secret that signs this sender\'s token.',
          emptyLabel: '(any trusted secret)',
          emptyListHelper: 'No JWT secrets yet — add one under "JWT secrets".',
          currentValue: _credentialRefCtrl.text,
          options: jwtIss,
          onChanged: (v) => setState(() => _credentialRefCtrl.text = v),
        );
      case 'oidc':
        return _asyncStringDropdown(
          label: 'OIDC issuer (iss)',
          helperText: 'The trusted issuer whose tokens this sender presents.',
          emptyLabel: '(any trusted issuer)',
          emptyListHelper: 'No OIDC issuers yet — add one under "OIDC issuers".',
          currentValue: _credentialRefCtrl.text,
          options: oidcIss,
          onChanged: (v) => setState(() => _credentialRefCtrl.text = v),
        );
      default:
        return TextField(
          controller: _credentialRefCtrl,
          decoration: const InputDecoration(
            labelText: 'API key id / issuer (optional)',
            helperText: 'Which credential to accept. Blank = any the authenticator trusts.',
          ),
        );
    }
  }

  /// Entry-point picker (required). Value is the entry-point id; the label shows
  /// the human name + handle so the operator recognises the chat.
  Widget _entryPointField(AsyncValue<List<ChatEntryPointResponse>> options) {
    final current = _entryPointCtrl.text;
    return options.when(
      loading: () => const InputDecorator(
        decoration: InputDecoration(labelText: 'chat entry point'),
        child: Padding(
          padding: EdgeInsets.symmetric(vertical: 10),
          child: LinearProgressIndicator(),
        ),
      ),
      error: (_, _) => TextFormField(
        initialValue: current,
        decoration: const InputDecoration(
          labelText: 'chat entry point id',
          helperText: 'Could not load entry points — enter the id manually.',
        ),
        onChanged: (v) => _entryPointCtrl.text = v,
      ),
      data: (list) {
        final missing = current.isNotEmpty && !list.any((e) => e.id == current);
        return DropdownButtonFormField<String>(
          initialValue: current.isEmpty ? null : current,
          isExpanded: true,
          hint: const Text('select a chat…'),
          decoration: InputDecoration(
            labelText: 'chat entry point',
            helperText: list.isEmpty
                ? 'No chat entry points yet — create one under "Chats".'
                : 'The chat this sender delivers into (the chat the user added).',
          ),
          items: [
            ...list.map((e) => DropdownMenuItem(
                  value: e.id,
                  child: Text('${e.displayName}  ·  @${e.handle}', overflow: TextOverflow.ellipsis),
                )),
            if (missing)
              DropdownMenuItem(value: current, child: Text('$current  (not found)')),
          ],
          onChanged: (v) => setState(() => _entryPointCtrl.text = v ?? ''),
        );
      },
    );
  }

  /// Id prefix of the per-workspace Playground simulator (backend `PlaygroundSenders.PREFIX`).
  static const _playgroundSenderPrefix = 'snd_playground_sim__';

  /// Optional WhatsApp leg: when linked, a message whose envelope asks for
  /// WhatsApp goes out on this number (template outside the 24 h window) and
  /// falls back to the chat when it cannot. The workspace's own Playground
  /// simulator is offered too: its messages appear in the Playground
  /// conversation of the recipient phone, buttons included.
  Widget _whatsappField(AsyncValue<List<WhatsAppSenderResponse>> options) {
    return options.when(
      loading: () => const LinearProgressIndicator(),
      error: (_, _) => const Text('Could not load WhatsApp numbers.'),
      data: (all) {
        final tenant = ref.watch(currentTenantOrNull);
        final ownSimulator = tenant == null ? null : '$_playgroundSenderPrefix$tenant';
        final list = all.where((s) => !s.id.startsWith(_playgroundSenderPrefix)).toList();
        final onSimulator = _whatsappSenderId.isNotEmpty && _whatsappSenderId == ownSimulator;
        final missing = _whatsappSenderId.isNotEmpty && !onSimulator && !list.any((s) => s.id == _whatsappSenderId);
        return DropdownButtonFormField<String>(
          initialValue: _whatsappSenderId,
          isExpanded: true,
          decoration: InputDecoration(
            labelText: 'WhatsApp number (optional)',
            helperText: onSimulator
                ? 'Messages appear in the Playground, in the conversation of the recipient phone '
                    '(it must have written there first).'
                : 'Proactive messages that ask for WhatsApp go out on this number; '
                    'otherwise they land in the chat.',
            helperMaxLines: 2,
          ),
          items: [
            const DropdownMenuItem(value: '', child: Text('Chat only')),
            ...list.map((s) => DropdownMenuItem(
                  value: s.id,
                  child: Text('${s.displayName}  ·  ${s.phoneE164}  ·  ${s.status}',
                      overflow: TextOverflow.ellipsis),
                )),
            if (ownSimulator != null)
              DropdownMenuItem(
                value: ownSimulator,
                child: const Text('Playground simulator  ·  messages appear in the Playground',
                    overflow: TextOverflow.ellipsis),
              ),
            if (missing)
              DropdownMenuItem(value: _whatsappSenderId, child: Text('$_whatsappSenderId  (not found)')),
          ],
          onChanged: (v) => setState(() => _whatsappSenderId = v ?? ''),
        );
      },
    );
  }

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
        whatsappSenderId: _whatsappSenderId,
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
    final entryPoints = ref.watch(_entryPointsProvider(tenant));
    final jwtIss = ref.watch(_jwtSecretIssProvider(tenant));
    final oidcIss = ref.watch(_oidcIssuerIssProvider(tenant));
    final toolIds = ref.watch(_toolIdsProvider(tenant));
    final whatsappSenders = ref.watch(_whatsappSendersProvider(tenant));

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
              _entryPointField(entryPoints),
              const SizedBox(height: 12),
              _whatsappField(whatsappSenders),
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
                decoration: const InputDecoration(
                  labelText: 'security mechanism',
                  helperText: 'How the sender proves trust — determines the credential field below.',
                ),
                items: _authenticators
                    .map((a) => DropdownMenuItem(value: a, child: Text(a)))
                    .toList(),
                // Credential meaning differs per authenticator, so clear it on switch.
                onChanged: (v) => setState(() {
                  _authenticator = v ?? 'shared-secret-jwt';
                  _credentialRefCtrl.clear();
                }),
              ),
              const SizedBox(height: 12),
              _credentialField(jwtIss, oidcIss),
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
              _asyncStringDropdown(
                label: 'response tool (optional)',
                helperText: 'The tool the agent calls to answer, e.g. system.reply.',
                emptyLabel: '(no reply tool)',
                emptyListHelper: 'No tools configured yet — add one under "Tools".',
                currentValue: _responseToolCtrl.text,
                options: toolIds,
                onChanged: (v) => setState(() => _responseToolCtrl.text = v),
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

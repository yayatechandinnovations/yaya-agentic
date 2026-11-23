import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/chat_entry_point.dart';
import '../../../models/admin/system_sender.dart';
import '../../../models/admin/whatsapp_sender.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/form/y_resource_picker.dart';
import '../../../ui/tokens.dart';
import '../channel_bindings/channel_ui.dart';
import '../shared/admin_shared.dart' show TenantScopedEmptyState;

final systemSendersProvider = FutureProvider.family<List<SystemSenderResponse>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.systemSender);
  final api = await ref.watch(adminApiProvider.future);
  return api.listSystemSenders(tenantId);
});

// ---- Option sources for the pickers ---------------------------------------
// The form binds configured resources, not free text: an operator picks a JWT
// secret / OIDC issuer / tool that already exists rather than retyping its id.

final _entryPointsProvider = FutureProvider.family<List<ChatEntryPointResponse>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.chatEntryPoint);
  final api = await ref.watch(adminApiProvider.future);
  return api.listChatEntryPoints(tenantId);
});

final _whatsappSendersProvider = FutureProvider.family<List<WhatsAppSenderResponse>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.whatsappSender);
  final api = await ref.watch(adminApiProvider.future);
  return api.listWhatsAppSenders(tenantId);
});

/// `iss` of every configured HS256 shared secret — the credentialRef a
/// `shared-secret-jwt` sender pins (matched against the token's iss).
final _jwtSecretIssProvider = FutureProvider.family<List<String>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.jwtSecret);
  final api = await ref.watch(adminApiProvider.future);
  final rows = await api.listTenantJwtSecrets(tenantId);
  return rows.map((r) => r.iss).where((s) => s.isNotEmpty).toSet().toList()..sort();
});

/// `iss` of every trusted OIDC issuer — the credentialRef an `oidc` sender pins.
final _oidcIssuerIssProvider = FutureProvider.family<List<String>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.oidcIssuer);
  final api = await ref.watch(adminApiProvider.future);
  final rows = await api.listTenantOidcIssuers(tenantId);
  return rows.map((r) => r.iss).where((s) => s.isNotEmpty).toSet().toList()..sort();
});

/// Tool ids the agent could call to answer (the response tool).
final _toolIdsProvider = FutureProvider.family<List<String>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.tool);
  final api = await ref.watch(adminApiProvider.future);
  final tools = await api.listTools(tenant: tenantId);
  return tools.map((t) => t.id).toSet().toList()..sort();
});

/// Id prefix of the per-workspace Playground simulator (backend `PlaygroundSenders.PREFIX`).
const _playgroundSenderPrefix = 'snd_playground_sim__';

const _authenticators = {
  'shared-secret-jwt': ('Shared-secret JWT', 'The system signs an HS256 token with a secret from “JWT secrets”.'),
  'oidc': ('OIDC token', 'The system presents a token from a trusted issuer in “OIDC issuers”.'),
  'api-key': ('API key', 'The system calls with a workspace API key.'),
};

String _authLabel(String name) => _authenticators[name]?.$1 ?? (name.isEmpty ? 'Not set' : name);

/// The "System inbound" configuration surface (system-messaging-design.md §14):
/// let a linked application inject messages into a user's chat, choose HOW it
/// proves trust, whether the assistant mediates, and which tool answers.
class SystemSendersScreen extends ConsumerStatefulWidget {
  const SystemSendersScreen({super.key});

  @override
  ConsumerState<SystemSendersScreen> createState() => _SystemSendersScreenState();
}

class _SystemSendersScreenState extends ConsumerState<SystemSendersScreen> {
  String? _handledLink;
  String? _openId;

  Future<void> _open(SystemSenderResponse? existing) async {
    setState(() => _openId = existing?.id);
    await showYSheet<void>(
      context,
      width: 680,
      label: existing == null ? 'New system sender' : 'Edit system sender',
      builder: (_) => SystemSenderEditorSheet(existing: existing),
    );
    if (mounted) setState(() => _openId = null);
  }

  void _handleDeepLink(List<SystemSenderResponse> list) {
    final params = routeQuery(context);
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final match = link.isEmpty ? null : list.where((s) => s.id == link).firstOrNull;
      if (link.isNotEmpty && match == null) {
        showToast(context, 'No system sender “$link” in this workspace', error: true);
      } else {
        await _open(match);
      }
      if (mounted) clearRouteQuery(context, '/admin/system-senders');
      _handledLink = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'System senders');
    }
    final senders = ref.watch(systemSendersProvider(tenant));
    final entryPoints = {for (final e in ref.watch(_entryPointsProvider(tenant)).valueOrNull ?? const <ChatEntryPointResponse>[]) e.id: e};
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.systemSender));
    senders.whenData(_handleDeepLink);

    final create = FilledButton.icon(
      onPressed: () => _open(null),
      icon: const Icon(Icons.add, size: 18),
      label: const Text('New system sender'),
    );

    String entryLabel(SystemSenderResponse s) {
      final e = entryPoints[s.entryPoint];
      return e == null ? s.entryPoint : '${e.displayName} · @${e.handle}';
    }

    return YPage(
      title: 'System inbound',
      subtitle: 'Let a linked application (POS, ERP, back office) post messages into a user’s chat. Trust is a '
          'configured authenticator; mediation decides whether the assistant frames the message.',
      actions: [create],
      child: LiveAsyncView<List<SystemSenderResponse>>(
        value: senders,
        what: 'system senders',
        onRetry: () => ref.invalidate(systemSendersProvider(tenant)),
        data: (context, list) => YTable<SystemSenderResponse>(
          rows: list,
          noun: 'senders',
          idOf: (s) => s.id,
          selectedId: _openId,
          highlightIds: fresh,
          searchHint: 'Search by name, chat or credential',
          searchTextOf: (s) => '${s.displayName} ${s.id} ${entryLabel(s)} ${s.trust.authenticatorName} ${s.trust.credentialRef}',
          onRowTap: _open,
          filters: [
            YFilter('Enabled', (s) => s.enabled),
            YFilter('Off', (s) => !s.enabled),
            YFilter('Assistant-framed', (s) => s.mediation == 'LLM_MEDIATED'),
            YFilter('WhatsApp leg', (s) => (s.whatsappSenderId ?? '').isNotEmpty),
          ],
          empty: YEmptyState(
            icon: Icons.mail_outline,
            title: 'No system senders yet',
            message: 'A system sender is an application you trust to post into users’ chats — a cash register '
                'closing, a ride arriving, an order shipping.',
            action: FilledButton.icon(
              onPressed: () => _open(null),
              icon: const Icon(Icons.add, size: 18),
              label: const Text('Create your first system sender'),
            ),
          ),
          columns: [
            YColumn(
              label: 'Sender',
              flex: 3,
              sortKey: (s) => s.displayName.toLowerCase(),
              cell: (context, s) => YCellStack(primary: s.displayName, secondary: s.id),
            ),
            YColumn(
              label: 'Delivers into',
              flex: 3,
              sortKey: entryLabel,
              cell: (context, s) => Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
                Text(entryLabel(s), overflow: TextOverflow.ellipsis),
                if ((s.whatsappSenderId ?? '').isNotEmpty)
                  Text(
                    s.whatsappSenderId!.startsWith(_playgroundSenderPrefix) ? '+ Playground simulator' : '+ WhatsApp',
                    style: TextStyle(fontSize: 13, color: Theme.of(context).colorScheme.onSurfaceVariant),
                  ),
              ]),
            ),
            YColumn(
              label: 'Trust',
              flex: 3,
              sortKey: (s) => s.trust.authenticatorName,
              cell: (context, s) => YCellStack(
                primary: _authLabel(s.trust.authenticatorName),
                secondary: s.trust.credentialRef.isEmpty ? 'Any trusted credential' : s.trust.credentialRef,
              ),
            ),
            YColumn(
              label: 'Mediation',
              width: 150,
              sortKey: (s) => s.mediation,
              cell: (context, s) => YBadge(
                s.mediation == 'LLM_MEDIATED' ? 'Assistant frames' : 'Verbatim',
                tone: s.mediation == 'LLM_MEDIATED' ? YTone.brand : YTone.neutral,
              ),
            ),
            YColumn(
              label: 'Status',
              width: 120,
              sortKey: (s) => s.enabled ? 0 : 1,
              cell: (context, s) => YBadge(
                s.enabled ? 'Enabled' : 'Off',
                tone: s.enabled ? YTone.success : YTone.neutral,
                icon: s.enabled ? Icons.check_circle_outline : Icons.pause_circle_outline,
              ),
            ),
            YColumn(
              label: 'Updated',
              width: 120,
              sortKey: (s) => s.updatedAt ?? s.createdAt ?? '',
              cell: (context, s) => Text(fresh.contains(s.id) ? 'Just now' : formatRelativeIso(s.updatedAt ?? s.createdAt)),
            ),
          ],
        ),
      ),
    );
  }
}

const _labels = {
  'displayName': 'Display name',
  'entryPoint': 'Chat',
  'whatsappSenderId': 'WhatsApp number',
  'authenticatorName': 'Security mechanism',
  'credentialRef': 'Credential',
  'requiredScopes': 'Required scopes',
  'responseToolId': 'Response tool',
  'allowedSubjects': 'Allowed recipients',
};

/// Create or edit one system sender, in a side sheet.
class SystemSenderEditorSheet extends ConsumerStatefulWidget {
  const SystemSenderEditorSheet({super.key, this.existing});
  final SystemSenderResponse? existing;

  @override
  ConsumerState<SystemSenderEditorSheet> createState() => _SystemSenderEditorSheetState();
}

class _SystemSenderEditorSheetState extends ConsumerState<SystemSenderEditorSheet> {
  final _displayName = TextEditingController();
  final _credentialRef = TextEditingController();
  final _scopes = TextEditingController(); // one per line
  final _subjects = TextEditingController(); // one per line
  String? _entryPoint;
  String? _responseTool;
  String _authenticator = 'shared-secret-jwt';
  String _mediation = 'VERBATIM';
  String _whatsappSenderId = '';
  bool _enabled = false;
  late final YFormController _form;

  SystemSenderResponse? get _existing => widget.existing;
  bool get _editing => _existing != null;

  @override
  void initState() {
    super.initState();
    final row = _existing;
    if (row != null) {
      _displayName.text = row.displayName;
      _entryPoint = row.entryPoint.isEmpty ? null : row.entryPoint;
      _credentialRef.text = row.trust.credentialRef;
      _scopes.text = row.trust.requiredScopes.join('\n');
      _responseTool = row.responseToolId;
      _subjects.text = row.addressing.allowedSubjects.join('\n');
      _authenticator = row.trust.authenticatorName.isEmpty ? 'shared-secret-jwt' : row.trust.authenticatorName;
      _mediation = row.mediation;
      _whatsappSenderId = row.whatsappSenderId ?? '';
      _enabled = row.enabled;
    }
    final tenant = ref.read(currentTenantOrNull);
    _form = YFormController(
      snapshot: () => {
        'displayName': _displayName.text.trim(),
        'entryPoint': _entryPoint,
        'whatsappSenderId': _whatsappSenderId,
        'enabled': _enabled,
        'authenticatorName': _authenticator,
        'credentialRef': _credentialRef.text.trim(),
        'requiredScopes': _lines(_scopes),
        'mediation': _mediation,
        'responseToolId': _responseTool,
        'allowedSubjects': _lines(_subjects),
      },
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.systemSender.$tenant.${_existing?.id ?? 'new'}',
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      final v = d.values;
      _displayName.text = (v['displayName'] as String?) ?? _displayName.text;
      _entryPoint = (v['entryPoint'] as String?) ?? _entryPoint;
      _whatsappSenderId = (v['whatsappSenderId'] as String?) ?? _whatsappSenderId;
      _enabled = (v['enabled'] as bool?) ?? _enabled;
      _authenticator = (v['authenticatorName'] as String?) ?? _authenticator;
      _credentialRef.text = (v['credentialRef'] as String?) ?? _credentialRef.text;
      if (v['requiredScopes'] is List) _scopes.text = (v['requiredScopes'] as List).join('\n');
      _mediation = (v['mediation'] as String?) ?? _mediation;
      _responseTool = v.containsKey('responseToolId') ? v['responseToolId'] as String? : _responseTool;
      if (v['allowedSubjects'] is List) _subjects.text = (v['allowedSubjects'] as List).join('\n');
      _form.refresh();
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    for (final c in [_displayName, _credentialRef, _scopes, _subjects]) {
      c.dispose();
    }
    super.dispose();
  }

  List<String> _lines(TextEditingController c) => c.text.split('\n').map((s) => s.trim()).where((s) => s.isNotEmpty).toList();

  Map<String, String?> _validate() {
    final scopes = _lines(_scopes);
    return {
      'displayName': Validators.required(_displayName.text, 'the name users see'),
      'entryPoint': _entryPoint == null || _entryPoint!.isEmpty ? 'Choose the chat this sender posts into.' : null,
      'requiredScopes': scopes.any((s) => s.contains(' ')) ? 'One scope per line, without spaces.' : null,
    };
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final old = _existing?.addressing;
      final body = SystemSenderRequest(
        entryPoint: _entryPoint!,
        displayName: _displayName.text.trim(),
        enabled: _enabled,
        trust: SenderTrust(
          authenticatorName: _authenticator,
          requiredScopes: _lines(_scopes),
          credentialRef: _credentialRef.text.trim(),
        ),
        mediation: _mediation,
        // Kinds and roles aren't edited here; keep whatever is stored.
        addressing: AddressingPolicy(
          allowedKinds: old?.allowedKinds ?? const ['PRINCIPAL'],
          allowedSubjects: _lines(_subjects),
          allowedRoles: old?.allowedRoles ?? const [],
        ),
        responseToolId: _responseTool,
        whatsappSenderId: _whatsappSenderId,
        capabilityMask: _existing?.capabilityMask,
        limits: _existing?.limits,
      );
      final saved = _editing
          ? await api.updateSystemSender(tenant, _existing!.id, body)
          : await api.createSystemSender(tenant, body);
      ref.invalidate(systemSendersProvider(tenant));
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, _editing ? 'Saved ${saved.displayName}' : 'Created ${saved.displayName}');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  Future<void> _delete() async {
    final row = _existing!;
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final ok = await confirmTyped(
      context,
      title: 'Delete ${row.displayName}?',
      message: 'The linked system can no longer post into “${row.entryPoint}” — its next message is rejected. '
          'Messages already delivered stay in users’ chats.',
      expected: row.displayName,
      confirmLabel: 'Delete sender',
    );
    if (!ok) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.deleteSystemSender(tenant, row.id);
      ref.invalidate(systemSendersProvider(tenant));
      _form.markClean();
      if (!mounted) return;
      showToast(context, 'Deleted ${row.displayName}');
      Navigator.of(context).pop();
    } catch (e) {
      if (mounted) showToast(context, ApiErrorInfo.from(e).message, error: true);
    }
  }

  Future<void> _simulate() async {
    final row = _existing!;
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    await showDialog<void>(context: context, builder: (_) => _SimulateDialog(tenant: tenant, sender: row));
  }

  Widget _credentialField(String tenant) {
    switch (_authenticator) {
      case 'shared-secret-jwt':
      case 'oidc':
        final jwt = _authenticator == 'shared-secret-jwt';
        final options = ref.watch(jwt ? _jwtSecretIssProvider(tenant) : _oidcIssuerIssProvider(tenant));
        final list = options.valueOrNull ?? const <String>[];
        if (options.hasError && !options.hasValue) {
          return YTextField(
            label: jwt ? 'JWT shared secret (iss)' : 'OIDC issuer (iss)',
            wireKey: 'trust.credentialRef',
            controller: _credentialRef,
            form: _form,
            name: 'credentialRef',
            optional: true,
            mono: true,
            helper: 'Couldn’t load the configured ${jwt ? 'secrets' : 'issuers'} — enter the iss by hand. Blank accepts any trusted one.',
          );
        }
        return YResourcePicker(
          label: jwt ? 'JWT shared secret (iss)' : 'OIDC issuer (iss)',
          wireKey: 'trust.credentialRef',
          form: _form,
          name: 'credentialRef',
          optional: true,
          loading: options.isLoading,
          value: _credentialRef.text.trim().isEmpty ? null : _credentialRef.text.trim(),
          noneLabel: jwt ? 'Any trusted secret' : 'Any trusted issuer',
          resources: [for (final iss in list) YResource(id: iss)],
          helper: list.isEmpty && !options.isLoading
              ? 'None configured yet — add one under “${jwt ? 'JWT secrets' : 'OIDC issuers'}”.'
              : jwt
                  ? 'The secret that signs this system’s token, matched by its iss.'
                  : 'The issuer whose tokens this system presents.',
          onChanged: (v) => setState(() => _credentialRef.text = v ?? ''),
        );
      default:
        return YTextField(
          label: 'API key ID or issuer',
          wireKey: 'trust.credentialRef',
          controller: _credentialRef,
          form: _form,
          name: 'credentialRef',
          optional: true,
          mono: true,
          helper: 'Which credential to accept. Blank accepts any the authenticator trusts.',
        );
    }
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull) ?? '';
    final entryPoints = ref.watch(_entryPointsProvider(tenant));
    final whatsapp = ref.watch(_whatsappSendersProvider(tenant));
    final tools = ref.watch(_toolIdsProvider(tenant));
    final ownSimulator = '$_playgroundSenderPrefix$tenant';
    final onSimulator = _whatsappSenderId.isNotEmpty && _whatsappSenderId == ownSimulator;
    final numbers = (whatsapp.valueOrNull ?? const <WhatsAppSenderResponse>[])
        .where((s) => !s.id.startsWith(_playgroundSenderPrefix))
        .toList();
    final authOptions = [
      for (final e in _authenticators.entries) YEnumOption(e.key, e.value.$1, wire: e.key, description: e.value.$2),
      // A mechanism configured outside the console stays selectable rather than being silently replaced.
      if (!_authenticators.containsKey(_authenticator))
        YEnumOption(_authenticator, _authenticator, wire: _authenticator, description: 'Configured outside the console.'),
    ];

    final fields = <Widget>[
      if (_form.summary.isNotEmpty || _form.formError != null)
        YErrorSummary(form: _form, action: _editing ? 'saving' : 'creating this sender'),
      YTextField(
        label: 'Display name',
        wireKey: 'displayName',
        controller: _displayName,
        form: _form,
        name: 'displayName',
        required: true,
        autofocus: !_editing,
        hint: 'e.g. Cash Register #3',
        helper: 'Shown to the user as the sender of the message.',
      ),
      YResourcePicker(
        label: 'Chat',
        wireKey: 'entryPoint',
        form: _form,
        name: 'entryPoint',
        required: true,
        value: _entryPoint,
        loading: entryPoints.isLoading,
        resources: [
          for (final e in entryPoints.valueOrNull ?? const <ChatEntryPointResponse>[])
            YResource(id: e.id, name: e.displayName, meta: '@${e.handle}'),
        ],
        hint: 'Search chats…',
        helper: (entryPoints.valueOrNull ?? const []).isEmpty && !entryPoints.isLoading
            ? 'No chats yet — create one under “Chats”.'
            : 'The chat this sender posts into (the one the user added).',
        onChanged: (v) => setState(() => _entryPoint = v),
      ),
      YResourcePicker(
        label: 'WhatsApp number',
        wireKey: 'whatsappSenderId',
        form: _form,
        name: 'whatsappSenderId',
        optional: true,
        value: _whatsappSenderId.isEmpty ? null : _whatsappSenderId,
        noneLabel: 'Chat only',
        loading: whatsapp.isLoading,
        resources: [
          for (final s in numbers)
            YResource(id: s.id, name: '${s.displayName} · ${s.phoneE164}', meta: SenderStatusInfo.of(s.status).label),
          if (tenant.isNotEmpty) YResource(id: ownSimulator, name: 'Playground simulator'),
        ],
        helper: onSimulator
            ? 'Messages appear in the Playground, in the conversation of the recipient phone (it must have written '
                'there first).'
            : 'Messages that ask for WhatsApp go out on this number (a template outside the 24-hour window); '
                'otherwise they land in the chat.',
        onChanged: (v) => setState(() => _whatsappSenderId = v ?? ''),
      ),
      YSwitchField(
        title: 'Accept messages from this system',
        wireKey: 'enabled',
        description: _enabled
            ? 'The ingest endpoint accepts this sender’s messages.'
            : 'Off — the system’s messages are rejected until you turn this on.',
        value: _enabled,
        onChanged: (v) {
          setState(() => _enabled = v);
          _form.refresh();
        },
      ),
      YFormSection(
        title: 'Trust',
        description: 'How the system proves it is who it says it is. Baseline scope system:inbound:send is always required.',
        card: false,
        children: [
          YEnumCards<String>(
            label: 'Security mechanism',
            wireKey: 'trust.authenticatorName',
            form: _form,
            name: 'authenticatorName',
            value: _authenticator,
            options: authOptions,
            // The credential means something different per mechanism, so it resets on a switch.
            onChanged: (v) => setState(() {
              if (v != _authenticator) _credentialRef.clear();
              _authenticator = v;
            }),
          ),
          _credentialField(tenant),
          YTextField(
            label: 'Required scopes',
            wireKey: 'trust.requiredScopes',
            controller: _scopes,
            form: _form,
            name: 'requiredScopes',
            optional: true,
            mono: true,
            minLines: 1,
            maxLines: 4,
            hint: 'e.g. pos:cash',
            helper: 'One per line, beyond the baseline system:inbound:send.',
          ),
        ],
      ),
      YFormSection(
        title: 'Mediation & reply',
        card: false,
        children: [
          YEnumCards<String>(
            label: 'How the message reaches the user',
            wireKey: 'mediation',
            value: _mediation,
            onChanged: (v) {
              setState(() => _mediation = v);
              _form.refresh();
            },
            options: const [
              YEnumOption('VERBATIM', 'Deliver as written', wire: 'VERBATIM', description: 'The user sees exactly what the system sent.'),
              YEnumOption('LLM_MEDIATED', 'Let the assistant frame it',
                  wire: 'LLM_MEDIATED',
                  caution: true,
                  description: 'The assistant rewrites it in the conversation’s voice — the user may not see the original wording.'),
            ],
          ),
          YResourcePicker(
            label: 'Response tool',
            wireKey: 'responseToolId',
            form: _form,
            name: 'responseToolId',
            optional: true,
            value: _responseTool,
            noneLabel: 'No reply tool',
            loading: tools.isLoading,
            resources: [for (final t in tools.valueOrNull ?? const <String>[]) YResource(id: t)],
            helper: (tools.valueOrNull ?? const []).isEmpty && !tools.isLoading
                ? 'No tools yet — add one under “Tools”.'
                : 'The tool the assistant calls to answer the system, e.g. system.reply.',
            onChanged: (v) => setState(() => _responseTool = v),
          ),
        ],
      ),
      YFormSection(
        title: 'Addressing',
        card: false,
        children: [
          YTextField(
            label: 'Allowed recipients',
            wireKey: 'addressing.allowedSubjects',
            controller: _subjects,
            form: _form,
            name: 'allowedSubjects',
            optional: true,
            mono: true,
            minLines: 1,
            maxLines: 5,
            hint: 'e.g. operator-42',
            helper: 'User subjects this system may write to, one per line. Empty allows any subject its scopes permit.',
          ),
        ],
      ),
    ];

    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: _editing ? _existing!.displayName : 'New system sender',
          subtitle: _editing ? '${_existing!.id} · fields marked * are required.' : 'Fields marked * are required.',
          headerActions: [
            if (_editing) ...[
              IconButton(tooltip: 'Send a test message', icon: const Icon(Icons.send_outlined), onPressed: _simulate),
              IconButton(
                tooltip: 'Delete sender',
                icon: Icon(Icons.delete_outline, color: Theme.of(context).colorScheme.error),
                onPressed: _delete,
              ),
            ],
          ],
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [
              for (var i = 0; i < fields.length; i++) ...[if (i > 0) const SizedBox(height: Gap.fieldGap), fields[i]],
            ],
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: _editing ? 'Save sender' : 'Create sender',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}

/// Posts as if the linked system had — same delivery path, trust skipped.
class _SimulateDialog extends ConsumerStatefulWidget {
  const _SimulateDialog({required this.tenant, required this.sender});
  final String tenant;
  final SystemSenderResponse sender;

  @override
  ConsumerState<_SimulateDialog> createState() => _SimulateDialogState();
}

class _SimulateDialogState extends ConsumerState<_SimulateDialog> {
  final _subject = TextEditingController();
  final _body = TextEditingController(text: 'cash closed: 1000');
  late final YFormController _form = YFormController(
    snapshot: () => {'subject': _subject.text.trim(), 'body': _body.text.trim()},
    validate: () => {
      'subject': Validators.required(_subject.text, 'the recipient’s subject'),
      'body': Validators.required(_body.text, 'a message'),
    },
    labels: const {'subject': 'Recipient', 'body': 'Message'},
  )..addListener(_rebuild);

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    _subject.dispose();
    _body.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    if (!_form.submit()) return;
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final res = await api.simulateSystemMessage(widget.tenant, widget.sender.id, {
        'subject': _subject.text.trim(),
        'body': _body.text.trim(),
        'originatorName': widget.sender.displayName,
        'subjectLine': 'simulated',
      });
      _form.saving = false;
      if (!mounted) return;
      showToast(context, 'Test message ${humanizeWire('${res['disposition'] ?? 'sent'}').toLowerCase()}');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('Send a test message from ${widget.sender.displayName}'),
      content: SizedBox(
        width: 480,
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          if (_form.formError != null) ...[YErrorSummary(form: _form, action: 'sending'), const SizedBox(height: Gap.md)],
          YTextField(
            label: 'Recipient',
            wireKey: 'subject',
            controller: _subject,
            form: _form,
            name: 'subject',
            required: true,
            autofocus: true,
            mono: true,
            hint: 'e.g. operator-42',
            helper: 'The user’s subject (operator id). It goes through the real delivery path.',
          ),
          const SizedBox(height: Gap.fieldGap),
          YTextField(
            label: 'Message',
            wireKey: 'body',
            controller: _body,
            form: _form,
            name: 'body',
            required: true,
            minLines: 2,
            maxLines: 4,
          ),
        ]),
      ),
      actions: [
        OutlinedButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(onPressed: _form.saving ? null : _send, child: const Text('Send test message')),
      ],
    );
  }
}

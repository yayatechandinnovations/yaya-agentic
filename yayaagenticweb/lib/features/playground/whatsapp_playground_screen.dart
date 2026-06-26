import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../api/api_client.dart';
import '../../app/selected_tenant.dart';
import '../../models/admin/channel_binding.dart';
import '../admin/channel_bindings/channel_bindings_screen.dart'
    show phonePrincipalResolversProvider;
import '../admin/profiles/profiles_screen.dart' show profilesProvider;
import '../admin/shared/admin_shared.dart';

/// Simulated WhatsApp playground — sends inbound through the platform
/// playground endpoint and polls outbound from the in-memory sink. No real
/// Twilio dependency. Useful for exercising a profile's WhatsApp affordances
/// (no streaming, single message dispatch, button/list rendering) without
/// shipping a real number.
class WhatsAppPlaygroundScreen extends ConsumerStatefulWidget {
  const WhatsAppPlaygroundScreen({super.key});

  @override
  ConsumerState<WhatsAppPlaygroundScreen> createState() =>
      _WhatsAppPlaygroundScreenState();
}

class _WhatsAppPlaygroundScreenState
    extends ConsumerState<WhatsAppPlaygroundScreen> {
  final _textCtrl = TextEditingController();
  final _scrollCtrl = ScrollController();
  String? _profileSlug;
  String _sessionId = '';
  String? _lastSeenId;
  final List<_Message> _transcript = [];
  Timer? _pollTimer;
  bool _sending = false;

  // Identity override — when 'default-policy', _send omits the field and the
  // dispatch worker uses the binding's actual policy.
  String _identityKind = 'default-policy';
  final List<String> _identityScopes = [];
  final Map<String, String> _identityClaims = {};
  String? _identityResolverId;
  bool _identityDenyOnUnknown = true;

  @override
  void initState() {
    super.initState();
    _resetSession();
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    _textCtrl.dispose();
    _scrollCtrl.dispose();
    super.dispose();
  }

  void _resetSession() {
    _sessionId = 'wa-${DateTime.now().millisecondsSinceEpoch}';
    _lastSeenId = null;
    _transcript.clear();
    _pollTimer?.cancel();
    _pollTimer = Timer.periodic(const Duration(milliseconds: 1500), (_) => _poll());
  }

  Future<void> _poll() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    try {
      final dio = await ref.read(dioProvider.future);
      final res = await dio.get(
          '/v1/admin/tenants/$tenant/whatsapp-playground/outbound/$_sessionId',
          queryParameters:
              _lastSeenId == null ? null : {'after': _lastSeenId});
      final messages = (res.data['messages'] as List?) ?? const [];
      if (messages.isEmpty) return;
      setState(() {
        for (final raw in messages) {
          final map = Map<String, dynamic>.from(raw as Map);
          _lastSeenId = map['id'] as String?;
          _transcript.add(_Message(
              fromBot: true,
              body: (map['body'] ?? '') as String,
              interactive: map['interactive'] == null
                  ? null
                  : Map<String, dynamic>.from(map['interactive'] as Map)));
        }
      });
      _scrollToBottom();
    } catch (e) {
      // Quiet polling failure — surface only when sending.
    }
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollCtrl.hasClients) return;
      _scrollCtrl.animateTo(_scrollCtrl.position.maxScrollExtent,
          duration: const Duration(milliseconds: 250),
          curve: Curves.easeOut);
    });
  }

  Future<void> _send([String? prefilled, String? interactiveId]) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    if (_profileSlug == null) {
      showSnack(context, 'Pick a profile first.', error: true);
      return;
    }
    final text = (prefilled ?? _textCtrl.text).trim();
    if (text.isEmpty) return;

    setState(() {
      _sending = true;
      _transcript.add(_Message(fromBot: false, body: text));
    });
    _scrollToBottom();
    _textCtrl.clear();

    try {
      final parts = _profileSlug!.split('@');
      final profileId = parts[0];
      final profileVersion = int.tryParse(parts.length > 1 ? parts[1] : '1') ?? 1;
      final dio = await ref.read(dioProvider.future);
      final override = _buildIdentityOverride();
      await dio.post(
        '/v1/admin/tenants/$tenant/whatsapp-playground/inbound',
        data: {
          'text': text,
          'fromPhone': '+15558675309',
          'profileId': profileId,
          'profileVersion': profileVersion,
          if (interactiveId != null) 'interactiveId': interactiveId,
          if (override != null) 'defaultPrincipalPolicyOverride': override.toJson(),
        },
        options: Options(contentType: 'application/json'),
      );
      // Poll a couple of times faster after a send to feel snappy.
      Future.delayed(const Duration(milliseconds: 500), _poll);
      Future.delayed(const Duration(milliseconds: 1200), _poll);
    } catch (e) {
      if (mounted) {
        setState(() {
          _transcript.add(_Message(
              fromBot: true, body: '[error] ${formatError(e)}', isError: true));
        });
      }
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  DefaultPrincipalPolicyWire? _buildIdentityOverride() {
    switch (_identityKind) {
      case 'anonymous':
        return DefaultPrincipalPolicyWire.anonymous();
      case 'phone-as-principal':
        return DefaultPrincipalPolicyWire.phoneAsPrincipal(
          additionalScopes: List.unmodifiable(_identityScopes),
          additionalClaims:
              Map<String, dynamic>.unmodifiable(_identityClaims),
        );
      case 'lookup-principal':
        if (_identityResolverId == null || _identityResolverId!.isEmpty) {
          return null;   // omits the override; binding default kicks in
        }
        return DefaultPrincipalPolicyWire.lookupPrincipal(
          resolverId: _identityResolverId!,
          denyOnUnknown: _identityDenyOnUnknown,
        );
      default:
        return null;
    }
  }

  String _identitySummary() {
    switch (_identityKind) {
      case 'anonymous':
        return 'Force Anonymous';
      case 'phone-as-principal':
        final extra = _identityScopes.length + _identityClaims.length;
        return extra == 0
            ? 'Force Phone-as-principal'
            : 'Force Phone-as-principal · +$extra';
      case 'lookup-principal':
        return _identityResolverId == null
            ? 'Force Lookup principal · (pick resolver)'
            : 'Force Lookup principal · ${_identityResolverId!}';
      default:
        return 'Use binding default';
    }
  }

  Future<void> _openIdentityEditor() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final resolvers = ref.read(phonePrincipalResolversProvider(tenant));
    await showDialog<void>(
      context: context,
      builder: (ctx) => _IdentityEditorDialog(
        initialKind: _identityKind,
        initialScopes: List.from(_identityScopes),
        initialClaims: Map.from(_identityClaims),
        initialResolverId: _identityResolverId,
        initialDenyOnUnknown: _identityDenyOnUnknown,
        resolvers: resolvers,
        onApply: (kind, scopes, claims, resolverId, denyOnUnknown) {
          setState(() {
            _identityKind = kind;
            _identityScopes
              ..clear()
              ..addAll(scopes);
            _identityClaims
              ..clear()
              ..addAll(claims);
            _identityResolverId = resolverId;
            _identityDenyOnUnknown = denyOnUnknown;
          });
        },
      ),
    );
  }

  Future<void> _resetConversation() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    try {
      final dio = await ref.read(dioProvider.future);
      await dio.delete('/v1/admin/tenants/$tenant/whatsapp-playground/outbound/$_sessionId');
    } catch (_) {}
    setState(_resetSession);
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(
          resourceLabel: 'WhatsApp playground');
    }
    final profiles = ref.watch(profilesProvider);

    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
          Row(
            children: [
              const Icon(Icons.chat),
              const SizedBox(width: 8),
              Text('WhatsApp playground',
                  style: Theme.of(context).textTheme.titleMedium),
              const Spacer(),
              SizedBox(
                width: 320,
                child: profiles.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (e, _) => Text(formatError(e),
                      style: TextStyle(
                          color: Theme.of(context).colorScheme.error)),
                  data: (list) {
                    final filtered =
                        list.where((p) => p.tenant == tenant).toList();
                    return DropdownButtonFormField<String>(
                      initialValue: _profileSlug,
                      isExpanded: true,
                      decoration: const InputDecoration(
                          labelText: 'Profile', isDense: true),
                      items: filtered
                          .map((p) => DropdownMenuItem(
                                value: '${p.id}@${p.version}',
                                child: Text('${p.id}@${p.version}'),
                              ))
                          .toList(),
                      onChanged: (v) {
                        setState(() => _profileSlug = v);
                      },
                    );
                  },
                ),
              ),
              const SizedBox(width: 12),
              TextButton.icon(
                onPressed: _resetConversation,
                icon: const Icon(Icons.refresh),
                label: const Text('Reset'),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              const Icon(Icons.fingerprint, size: 18),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  'Identity: ${_identitySummary()}',
                  style: Theme.of(context).textTheme.bodySmall,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              TextButton.icon(
                onPressed: _openIdentityEditor,
                icon: const Icon(Icons.edit, size: 16),
                label: const Text('Edit identity'),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Expanded(
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(8),
                child: ListView.builder(
                  controller: _scrollCtrl,
                  itemCount: _transcript.length,
                  itemBuilder: (_, i) =>
                      _MessageBubble(message: _transcript[i], onTap: _send),
                ),
              ),
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _textCtrl,
                  decoration: const InputDecoration(
                    border: OutlineInputBorder(),
                    hintText: 'Type a message',
                    isDense: true,
                  ),
                  onSubmitted: (_) => _send(),
                ),
              ),
              const SizedBox(width: 8),
              FilledButton.icon(
                onPressed: _sending ? null : () => _send(),
                icon: const Icon(Icons.send),
                label: const Text('Send'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _Message {
  _Message({
    required this.fromBot,
    required this.body,
    this.interactive,
    this.isError = false,
  });
  final bool fromBot;
  final String body;
  final Map<String, dynamic>? interactive;
  final bool isError;
}

class _IdentityEditorDialog extends StatefulWidget {
  const _IdentityEditorDialog({
    required this.initialKind,
    required this.initialScopes,
    required this.initialClaims,
    required this.initialResolverId,
    required this.initialDenyOnUnknown,
    required this.resolvers,
    required this.onApply,
  });

  final String initialKind;
  final List<String> initialScopes;
  final Map<String, String> initialClaims;
  final String? initialResolverId;
  final bool initialDenyOnUnknown;
  final AsyncValue<List<PhonePrincipalResolverSummary>> resolvers;
  final void Function(
    String kind,
    List<String> scopes,
    Map<String, String> claims,
    String? resolverId,
    bool denyOnUnknown,
  ) onApply;

  @override
  State<_IdentityEditorDialog> createState() => _IdentityEditorDialogState();
}

class _IdentityEditorDialogState extends State<_IdentityEditorDialog> {
  late String _kind;
  late List<String> _scopes;
  late Map<String, String> _claims;
  String? _resolverId;
  late bool _denyOnUnknown;
  final _scopeCtrl = TextEditingController();
  final _claimKeyCtrl = TextEditingController();
  final _claimValueCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _kind = widget.initialKind;
    _scopes = List.from(widget.initialScopes);
    _claims = Map.from(widget.initialClaims);
    _resolverId = widget.initialResolverId;
    _denyOnUnknown = widget.initialDenyOnUnknown;
  }

  @override
  void dispose() {
    _scopeCtrl.dispose();
    _claimKeyCtrl.dispose();
    _claimValueCtrl.dispose();
    super.dispose();
  }

  void _addScope() {
    final s = _scopeCtrl.text.trim();
    if (s.isEmpty || _scopes.contains(s)) return;
    setState(() {
      _scopes.add(s);
      _scopeCtrl.clear();
    });
  }

  void _addClaim() {
    final k = _claimKeyCtrl.text.trim();
    final v = _claimValueCtrl.text.trim();
    if (k.isEmpty || v.isEmpty) return;
    if (DefaultPrincipalPolicyWire.reservedClaimKeys.contains(k)) {
      showSnack(context,
          'Reserved channel claim — pick a different key '
          '(${DefaultPrincipalPolicyWire.reservedClaimKeys.join(", ")}).',
          error: true);
      return;
    }
    setState(() {
      _claims[k] = v;
      _claimKeyCtrl.clear();
      _claimValueCtrl.clear();
    });
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Playground identity'),
      content: SizedBox(
        width: 480,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                  'Override how the dispatch worker mints the end-user '
                  "Principal. Pick 'Use binding default' to run with the "
                  'binding policy unchanged.',
                  style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: 8),
              RadioListTile<String>(
                dense: true,
                contentPadding: EdgeInsets.zero,
                value: 'default-policy',
                groupValue: _kind,
                onChanged: (v) => setState(() => _kind = v ?? 'default-policy'),
                title: const Text('Use binding default'),
                subtitle: const Text("Don't override; production-faithful."),
              ),
              RadioListTile<String>(
                dense: true,
                contentPadding: EdgeInsets.zero,
                value: 'anonymous',
                groupValue: _kind,
                onChanged: (v) => setState(() => _kind = v ?? 'anonymous'),
                title: const Text('Force Anonymous'),
                subtitle: const Text('Channel scopes only.'),
              ),
              RadioListTile<String>(
                dense: true,
                contentPadding: EdgeInsets.zero,
                value: 'phone-as-principal',
                groupValue: _kind,
                onChanged: (v) =>
                    setState(() => _kind = v ?? 'phone-as-principal'),
                title: const Text('Force Phone as principal'),
                subtitle: const Text(
                    'Phone is the identity; supply extra scopes/claims.'),
              ),
              if (_kind == 'phone-as-principal') _phoneAsPrincipalEditor(),
              RadioListTile<String>(
                dense: true,
                contentPadding: EdgeInsets.zero,
                value: 'lookup-principal',
                groupValue: _kind,
                onChanged: (v) =>
                    setState(() => _kind = v ?? 'lookup-principal'),
                title: const Text('Force Lookup principal'),
                subtitle: const Text(
                    'Resolve phone via a registered tenant resolver.'),
              ),
              if (_kind == 'lookup-principal') _lookupPrincipalEditor(),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
          onPressed: () {
            widget.onApply(
                _kind, _scopes, _claims, _resolverId, _denyOnUnknown);
            Navigator.of(context).pop();
          },
          child: const Text('Apply'),
        ),
      ],
    );
  }

  Widget _phoneAsPrincipalEditor() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(28, 4, 0, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Additional scopes',
              style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 4),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final s in _scopes)
                InputChip(
                  label: Text(s),
                  onDeleted: () => setState(() => _scopes.remove(s)),
                ),
            ],
          ),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _scopeCtrl,
                  decoration: const InputDecoration(
                      isDense: true, hintText: 'e.g. order:read'),
                  onSubmitted: (_) => _addScope(),
                ),
              ),
              const SizedBox(width: 8),
              TextButton.icon(
                onPressed: _addScope,
                icon: const Icon(Icons.add, size: 16),
                label: const Text('Add'),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text('Additional claims',
              style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 4),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final e in _claims.entries)
                InputChip(
                  label: Text('${e.key}: ${e.value}'),
                  onDeleted: () => setState(() => _claims.remove(e.key)),
                ),
            ],
          ),
          Row(
            children: [
              Expanded(
                  child: TextField(
                controller: _claimKeyCtrl,
                decoration: const InputDecoration(
                    isDense: true, hintText: 'claim key'),
              )),
              const SizedBox(width: 8),
              Expanded(
                  child: TextField(
                controller: _claimValueCtrl,
                decoration: const InputDecoration(
                    isDense: true, hintText: 'value'),
                onSubmitted: (_) => _addClaim(),
              )),
              const SizedBox(width: 8),
              TextButton.icon(
                onPressed: _addClaim,
                icon: const Icon(Icons.add, size: 16),
                label: const Text('Add'),
              ),
            ],
          ),
          Text(
            'Reserved: ${DefaultPrincipalPolicyWire.reservedClaimKeys.join(", ")}.',
            style: Theme.of(context)
                .textTheme
                .bodySmall
                ?.copyWith(fontStyle: FontStyle.italic),
          ),
        ],
      ),
    );
  }

  Widget _lookupPrincipalEditor() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(28, 4, 0, 8),
      child: widget.resolvers.when(
        loading: () => const LinearProgressIndicator(),
        error: (e, _) => Text('Could not load resolvers: ${formatError(e)}',
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (list) {
          if (list.isEmpty) {
            return Text(
                'No phone-to-principal resolvers are registered on this '
                'instance. Force Lookup is unavailable.',
                style: Theme.of(context)
                    .textTheme
                    .bodySmall
                    ?.copyWith(color: Theme.of(context).colorScheme.error));
          }
          final known = list.map((r) => r.id).toSet();
          final selected =
              (_resolverId != null && known.contains(_resolverId!)) ? _resolverId : null;
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              DropdownButtonFormField<String>(
                initialValue: selected,
                isExpanded: true,
                decoration: const InputDecoration(
                    labelText: 'Phone-to-principal resolver',
                    isDense: true),
                items: [
                  for (final r in list)
                    DropdownMenuItem(
                      value: r.id,
                      child: Text(r.description.isEmpty
                          ? r.id
                          : '${r.id} — ${r.description}'),
                    ),
                ],
                onChanged: (v) => setState(() => _resolverId = v),
              ),
              SwitchListTile.adaptive(
                dense: true,
                contentPadding: EdgeInsets.zero,
                value: _denyOnUnknown,
                onChanged: (v) => setState(() => _denyOnUnknown = v),
                title: const Text('Deny unknown phones'),
                subtitle: const Text(
                    'When OFF, unknown phones fall back to Anonymous.'),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _MessageBubble extends StatelessWidget {
  const _MessageBubble({required this.message, required this.onTap});
  final _Message message;
  final void Function(String text, String interactiveId) onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final align = message.fromBot ? Alignment.centerLeft : Alignment.centerRight;
    final bg = message.fromBot
        ? scheme.surfaceContainerHighest
        : scheme.primaryContainer;
    final fg = message.fromBot ? scheme.onSurface : scheme.onPrimaryContainer;

    final options =
        ((message.interactive?['options'] as List?) ?? const [])
            .cast<Map<dynamic, dynamic>>()
            .map((m) => Map<String, dynamic>.from(m))
            .toList();

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4, horizontal: 6),
      child: Align(
        alignment: align,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: Column(
            crossAxisAlignment: message.fromBot
                ? CrossAxisAlignment.start
                : CrossAxisAlignment.end,
            children: [
              Container(
                decoration: BoxDecoration(
                  color: message.isError ? scheme.errorContainer : bg,
                  borderRadius: BorderRadius.circular(12),
                ),
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                child: Text(message.body,
                    style: TextStyle(
                        color: message.isError
                            ? scheme.onErrorContainer
                            : fg)),
              ),
              if (options.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: options
                        .map((o) => OutlinedButton(
                              onPressed: () => onTap(
                                  (o['label'] ?? '') as String,
                                  (o['id'] ?? '') as String),
                              child: Text((o['label'] ?? '') as String),
                            ))
                        .toList(),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../api/api_client.dart';
import '../../app/selected_tenant.dart';
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
  // A distinct synthetic end-user phone per session so each conversation is a
  // separate engine session — otherwise a shared phone makes "Reset" reuse the
  // prior session and the bot remembers across resets.
  String _fromPhone = '';
  String? _lastSeenId;
  final List<_Message> _transcript = [];
  Timer? _pollTimer;
  bool _sending = false;

  // Identity override — 'default' uses the binding's authentication chain
  // unchanged (production-faithful); 'pre-elevated' stamps a W-A8 step-up
  // shortcut for testing sensitive flows without running the OTP dance.
  String _identityKind = 'default';
  String _preElevationSubject = '';
  String _preElevationAudience = '';
  final List<String> _preElevationScopes = [];
  int _preElevationTtlSeconds = 1800;

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
    final ms = DateTime.now().millisecondsSinceEpoch;
    _sessionId = 'wa-$ms';
    // E.164-shaped (+1 555 + 7 digits) and unique per session.
    _fromPhone = '+1555${(ms % 10000000).toString().padLeft(7, '0')}';
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
      final preElevation = _buildPreElevation();
      await dio.post(
        '/v1/admin/tenants/$tenant/whatsapp-playground/inbound',
        data: {
          'text': text,
          'fromPhone': _fromPhone,
          'sessionId': _sessionId,
          'profileId': profileId,
          'profileVersion': profileVersion,
          if (interactiveId != null) 'interactiveId': interactiveId,
          if (preElevation != null) 'preElevation': preElevation,
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

  String _identitySummary() {
    switch (_identityKind) {
      case 'pre-elevated':
        if (_preElevationSubject.isEmpty || _preElevationAudience.isEmpty) {
          return 'Pre-elevated · (incomplete)';
        }
        return 'Pre-elevated · $_preElevationSubject for $_preElevationAudience';
      default:
        return 'Use binding default';
    }
  }

  /// Builds the {subject, audience, addedScopes, ttlSeconds} payload the
  /// backend writes into channel_session_elevation. Returns null when not in
  /// pre-elevated mode or when required fields are missing — the controller
  /// would 400, but failing client-side keeps the UX snappy.
  Map<String, dynamic>? _buildPreElevation() {
    if (_identityKind != 'pre-elevated') return null;
    if (_preElevationSubject.isEmpty || _preElevationAudience.isEmpty
        || _preElevationScopes.isEmpty) {
      return null;
    }
    return {
      'subject': _preElevationSubject,
      'audience': _preElevationAudience,
      'addedScopes': List<String>.from(_preElevationScopes),
      'ttlSeconds': _preElevationTtlSeconds,
    };
  }

  Future<void> _openIdentityEditor() async {
    await showDialog<void>(
      context: context,
      builder: (ctx) => _IdentityEditorDialog(
        initialKind: _identityKind,
        initialPreElevationSubject: _preElevationSubject,
        initialPreElevationAudience: _preElevationAudience,
        initialPreElevationScopes: List.from(_preElevationScopes),
        initialPreElevationTtl: _preElevationTtlSeconds,
        onApply: (kind, preSubject, preAudience, preScopes, preTtl) {
          setState(() {
            _identityKind = kind;
            _preElevationSubject = preSubject;
            _preElevationAudience = preAudience;
            _preElevationScopes
              ..clear()
              ..addAll(preScopes);
            _preElevationTtlSeconds = preTtl;
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
    required this.initialPreElevationSubject,
    required this.initialPreElevationAudience,
    required this.initialPreElevationScopes,
    required this.initialPreElevationTtl,
    required this.onApply,
  });

  final String initialKind;
  final String initialPreElevationSubject;
  final String initialPreElevationAudience;
  final List<String> initialPreElevationScopes;
  final int initialPreElevationTtl;
  final void Function(
    String kind,
    String preElevationSubject,
    String preElevationAudience,
    List<String> preElevationScopes,
    int preElevationTtl,
  ) onApply;

  @override
  State<_IdentityEditorDialog> createState() => _IdentityEditorDialogState();
}

class _IdentityEditorDialogState extends State<_IdentityEditorDialog> {
  late String _kind;
  late TextEditingController _preSubjectCtrl;
  late TextEditingController _preAudienceCtrl;
  final _preScopeCtrl = TextEditingController();
  late List<String> _preScopes;
  late TextEditingController _preTtlCtrl;

  @override
  void initState() {
    super.initState();
    _kind = widget.initialKind;
    _preSubjectCtrl = TextEditingController(text: widget.initialPreElevationSubject);
    _preAudienceCtrl = TextEditingController(text: widget.initialPreElevationAudience);
    _preScopes = List.from(widget.initialPreElevationScopes);
    _preTtlCtrl = TextEditingController(text: widget.initialPreElevationTtl.toString());
  }

  @override
  void dispose() {
    _preSubjectCtrl.dispose();
    _preAudienceCtrl.dispose();
    _preScopeCtrl.dispose();
    _preTtlCtrl.dispose();
    super.dispose();
  }

  void _addPreScope() {
    final s = _preScopeCtrl.text.trim();
    if (s.isEmpty || _preScopes.contains(s)) return;
    setState(() {
      _preScopes.add(s);
      _preScopeCtrl.clear();
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
                  'The auth resolver bound to the sender mints the end-user '
                  'identity. Choose pre-elevated to short-circuit the W-A8 '
                  'step-up flow when testing sensitive tools.',
                  style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: 8),
              RadioListTile<String>(
                dense: true,
                contentPadding: EdgeInsets.zero,
                value: 'default',
                groupValue: _kind,
                onChanged: (v) => setState(() => _kind = v ?? 'default'),
                title: const Text('Use binding default'),
                subtitle: const Text('Production-faithful — runs the auth resolver.'),
              ),
              RadioListTile<String>(
                dense: true,
                contentPadding: EdgeInsets.zero,
                value: 'pre-elevated',
                groupValue: _kind,
                onChanged: (v) =>
                    setState(() => _kind = v ?? 'pre-elevated'),
                title: const Text('Pre-elevated (skip step-up)'),
                subtitle: const Text(
                    'Operator-tested. Stamps channel_session_elevation '
                    'directly. Needs workspace ADMIN.'),
              ),
              if (_kind == 'pre-elevated') _preElevatedEditor(),
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
            final ttl = int.tryParse(_preTtlCtrl.text.trim()) ?? 1800;
            widget.onApply(
                _kind,
                _preSubjectCtrl.text.trim(),
                _preAudienceCtrl.text.trim(),
                List.from(_preScopes), ttl);
            Navigator.of(context).pop();
          },
          child: const Text('Apply'),
        ),
      ],
    );
  }

  Widget _preElevatedEditor() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(28, 4, 0, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          TextField(
            controller: _preSubjectCtrl,
            decoration: const InputDecoration(
                isDense: true, labelText: 'Subject (e.g. u_42)'),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _preAudienceCtrl,
            decoration: const InputDecoration(
                isDense: true,
                labelText: 'Audience (matches StepUpAuthorizer rule)',
                hintText: 'e.g. tool:transfer_funds'),
          ),
          const SizedBox(height: 8),
          Text('Added scopes',
              style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 4),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final s in _preScopes)
                InputChip(
                  label: Text(s),
                  onDeleted: () => setState(() => _preScopes.remove(s)),
                ),
            ],
          ),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _preScopeCtrl,
                  decoration: const InputDecoration(
                      isDense: true,
                      hintText: 'e.g. stepup:tool:transfer_funds'),
                  onSubmitted: (_) => _addPreScope(),
                ),
              ),
              const SizedBox(width: 8),
              TextButton.icon(
                onPressed: _addPreScope,
                icon: const Icon(Icons.add, size: 16),
                label: const Text('Add'),
              ),
            ],
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _preTtlCtrl,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
                isDense: true,
                labelText: 'TTL (seconds)',
                helperText: 'Capped server-side at 3600 (1 hour).'),
          ),
        ],
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

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
  String? _lastSeenId;
  final List<_Message> _transcript = [];
  Timer? _pollTimer;
  bool _sending = false;

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
          '/v1/playground/whatsapp/$tenant/outbound/$_sessionId',
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
      await dio.post(
        '/v1/playground/whatsapp/$tenant/inbound',
        data: {
          'text': text,
          'fromPhone': '+15558675309',
          'profileId': profileId,
          'profileVersion': profileVersion,
          if (interactiveId != null) 'interactiveId': interactiveId,
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

  Future<void> _resetConversation() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    try {
      final dio = await ref.read(dioProvider.future);
      await dio.delete('/v1/playground/whatsapp/$tenant/outbound/$_sessionId');
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
          const SizedBox(height: 12),
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

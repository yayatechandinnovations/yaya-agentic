import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/selected_tenant.dart';
import '../../models/turn_event.dart';
import '../admin/chats/chats_admin_screen.dart' show chatEntryPointsProvider;
import '../admin/shared/admin_shared.dart' show TenantScopedEmptyState;
import 'chat_playground_controller.dart';
import 'commerce_widgets.dart';
import 'inspector_panel.dart';
import 'playground_controller.dart' show ChatMessage, Role;

/// Chat playground — exercise a branded chat entry point end-to-end through the
/// same engine + auth path the public front door uses, while simulating the
/// caller identity (anonymous / phone-verified / tenant-bearer) server-side.
/// The right-side inspector shows the auth flow (binding → resolver exchange →
/// verification) so a failing resolver-exchange sign-in is debuggable without a
/// real Twilio verify or a code change on the tenant side.
class ChatPlaygroundScreen extends ConsumerStatefulWidget {
  const ChatPlaygroundScreen({super.key});

  @override
  ConsumerState<ChatPlaygroundScreen> createState() => _ChatPlaygroundScreenState();
}

class _ChatPlaygroundScreenState extends ConsumerState<ChatPlaygroundScreen> {
  final _msgCtrl = TextEditingController();

  @override
  void dispose() {
    _msgCtrl.dispose();
    super.dispose();
  }

  void _send([String? prefilled]) {
    final text = (prefilled ?? _msgCtrl.text).trim();
    if (text.isEmpty) return;
    _msgCtrl.clear();
    ref.read(chatPlaygroundProvider.notifier).sendMessage(text);
  }

  @override
  Widget build(BuildContext context) {
    if (ref.watch(currentTenantOrNull) == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Chat playground');
    }
    final state = ref.watch(chatPlaygroundProvider);
    if (!state.hasSession) return const _ChatPicker();

    final notifier = ref.read(chatPlaygroundProvider.notifier);
    return Row(
      children: [
        Expanded(
          child: Column(
            children: [
              _SessionHeader(state: state),
              if (state.startFailed) _StartFailedBanner(message: state.failureMessage),
              Expanded(
                child: ListView.builder(
                  padding: const EdgeInsets.all(12),
                  itemCount: state.messages.length,
                  itemBuilder: (_, i) => _ChatBubble(
                    message: state.messages[i],
                    dispatch: notifier.dispatchAction,
                  ),
                ),
              ),
              if (state.pendingConfirm != null)
                _ConfirmCard(
                  hint: state.pendingConfirm!,
                  onConfirm: () => _send('yes'),
                  onCancel: () => _send('no'),
                ),
              if (state.live && state.currentQuickReplies.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
                  child: Wrap(
                    spacing: 8,
                    children: state.currentQuickReplies
                        .map((q) => ActionChip(label: Text(q), onPressed: () => _send(q)))
                        .toList(),
                  ),
                ),
              CartBar(
                cartSource: chatPlaygroundProvider.select((s) => s.cart),
                dispatch: notifier.dispatchAction,
              ),
              SafeArea(
                top: false,
                child: Padding(
                  padding: const EdgeInsets.all(8),
                  child: Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: _msgCtrl,
                          enabled: state.live && !state.streaming,
                          onSubmitted: (_) => _send(),
                          decoration: InputDecoration(
                            hintText: state.live
                                ? 'Type a message'
                                : 'Session did not start — inspect the auth flow →',
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      FilledButton.icon(
                        onPressed: state.live && !state.streaming ? _send : null,
                        icon: const Icon(Icons.send),
                        label: const Text('Send'),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
        VerticalDivider(width: 1, color: Theme.of(context).dividerColor),
        SizedBox(
          width: 380,
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: InspectorPanel(
              snapshot: state.inspector,
              emptyHint: 'Send a message to inspect the turn.',
            ),
          ),
        ),
      ],
    );
  }
}

class _SessionHeader extends ConsumerWidget {
  const _SessionHeader({required this.state});
  final ChatPlaygroundState state;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final sid = state.sessionId ?? '';
    return Material(
      color: Theme.of(context).colorScheme.surfaceContainerLow,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            const Icon(Icons.chat_bubble_outline),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(state.greeting.isEmpty ? 'Chat session' : state.greeting,
                      style: Theme.of(context).textTheme.bodyLarge),
                  Text(
                    '${state.identity.summary} · ${sid.length >= 8 ? '${sid.substring(0, 8)}…' : sid}',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
            TextButton.icon(
              onPressed: () => ref.read(chatPlaygroundProvider.notifier).endSession(),
              icon: const Icon(Icons.swap_horiz),
              label: const Text('New session'),
            ),
          ],
        ),
      ),
    );
  }
}

class _StartFailedBanner extends StatelessWidget {
  const _StartFailedBanner({required this.message});
  final String? message;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      color: scheme.errorContainer,
      padding: const EdgeInsets.all(12),
      child: Row(
        children: [
          Icon(Icons.report_gmailerrorred, color: scheme.onErrorContainer, size: 20),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message ?? 'The session could not start.',
              style: TextStyle(color: scheme.onErrorContainer),
            ),
          ),
        ],
      ),
    );
  }
}

class _ChatBubble extends StatelessWidget {
  const _ChatBubble({required this.message, required this.dispatch});
  final ChatMessage message;
  final CommerceDispatch dispatch;

  @override
  Widget build(BuildContext context) {
    final isUser = message.role == Role.user;
    final hasBubble = message.text.isNotEmpty || message.citations.isNotEmpty;
    final hints = message.commerceHints;
    if (!hasBubble && hints.isEmpty) return const SizedBox.shrink();

    final color = isUser
        ? Theme.of(context).colorScheme.primaryContainer
        : Theme.of(context).colorScheme.surfaceContainerHigh;
    return Column(
      crossAxisAlignment: isUser ? CrossAxisAlignment.end : CrossAxisAlignment.start,
      children: [
        if (hasBubble)
          Align(
            alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
            child: Container(
              margin: const EdgeInsets.symmetric(vertical: 4),
              padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 12),
              constraints: const BoxConstraints(maxWidth: 520),
              decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(12)),
              child: Text(message.text.isEmpty ? '…' : message.text),
            ),
          ),
        ...hints.map((h) => ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 620),
              child: CommerceHintCard(hint: h, dispatch: dispatch),
            )),
      ],
    );
  }
}

class _ConfirmCard extends StatelessWidget {
  const _ConfirmCard({required this.hint, required this.onConfirm, required this.onCancel});
  final UiHintEvent hint;
  final VoidCallback onConfirm;
  final VoidCallback onCancel;

  @override
  Widget build(BuildContext context) {
    final summary = hint.payload['summary']?.toString() ?? 'Run this action?';
    final args = hint.payload['args'];
    final toolId = hint.payload['toolId']?.toString();
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
      child: Card(
        color: Theme.of(context).colorScheme.secondaryContainer,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  const Icon(Icons.help_outline, size: 18),
                  const SizedBox(width: 8),
                  Expanded(
                      child: Text(summary,
                          style: Theme.of(context).textTheme.titleSmall)),
                ],
              ),
              if (args != null) ...[
                const SizedBox(height: 6),
                Text('${toolId ?? "tool"}  ·  $args',
                    style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
              ],
              const SizedBox(height: 10),
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  OutlinedButton.icon(
                      onPressed: onCancel,
                      icon: const Icon(Icons.close),
                      label: const Text('Cancel')),
                  const SizedBox(width: 8),
                  FilledButton.icon(
                      onPressed: onConfirm,
                      icon: const Icon(Icons.check),
                      label: const Text('Confirm')),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The pre-session setup: pick a chat entry point and a simulated identity.
class _ChatPicker extends ConsumerStatefulWidget {
  const _ChatPicker();

  @override
  ConsumerState<_ChatPicker> createState() => _ChatPickerState();
}

class _ChatPickerState extends ConsumerState<_ChatPicker> {
  String? _selectedEp;
  String _mode = 'ANONYMOUS';
  final _phoneCtrl = TextEditingController();
  final _nameCtrl = TextEditingController();
  final _bearerCtrl = TextEditingController();

  @override
  void dispose() {
    _phoneCtrl.dispose();
    _nameCtrl.dispose();
    _bearerCtrl.dispose();
    super.dispose();
  }

  void _start() {
    final notifier = ref.read(chatPlaygroundProvider.notifier);
    notifier.selectEntryPoint(_selectedEp);
    notifier.setIdentity(ChatIdentity(
      mode: _mode,
      phone: _phoneCtrl.text,
      name: _nameCtrl.text,
      bearer: _bearerCtrl.text,
    ));
    notifier.start();
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull)!;
    final entryPoints = ref.watch(chatEntryPointsProvider(tenant));
    final state = ref.watch(chatPlaygroundProvider);

    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520),
        child: Card(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Test a chat', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 4),
                Text(
                  'Pick a chat entry point and a simulated caller identity. The '
                  'session runs through the same engine + auth path as the public '
                  'front door — the inspector shows the resolver exchange.',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 16),
                entryPoints.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (e, _) => Text('Failed to load chats: $e',
                      style: TextStyle(color: Theme.of(context).colorScheme.error)),
                  data: (list) {
                    if (list.isEmpty) {
                      return Text(
                        'No chat entry points in this tenant yet. Create one under '
                        'Chats to test it here.',
                        style: TextStyle(color: Theme.of(context).colorScheme.outline),
                      );
                    }
                    final available = list.map((e) => e.id).toSet();
                    if (_selectedEp != null && !available.contains(_selectedEp)) {
                      _selectedEp = null;
                    }
                    _selectedEp ??= list.first.id;
                    return DropdownButtonFormField<String>(
                      initialValue: _selectedEp,
                      decoration: const InputDecoration(labelText: 'Chat entry point'),
                      items: list
                          .map((e) => DropdownMenuItem(
                                value: e.id,
                                child: Text('${e.displayName} · ${e.status}'
                                    '${e.authConfig.kind == 'ResolverExchange' ? '  (resolver)' : ''}'),
                              ))
                          .toList(),
                      onChanged: (v) => setState(() => _selectedEp = v),
                    );
                  },
                ),
                const SizedBox(height: 16),
                Text('Simulated identity', style: Theme.of(context).textTheme.labelLarge),
                RadioListTile<String>(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  value: 'ANONYMOUS',
                  groupValue: _mode,
                  onChanged: (v) => setState(() => _mode = v ?? 'ANONYMOUS'),
                  title: const Text('Anonymous'),
                  subtitle: const Text('No caller identity (free session).'),
                ),
                RadioListTile<String>(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  value: 'VERIFIED_PHONE',
                  groupValue: _mode,
                  onChanged: (v) => setState(() => _mode = v ?? 'VERIFIED_PHONE'),
                  title: const Text('Verified phone (L1)'),
                  subtitle: const Text(
                      'Server-mints a device pass for this number — drives the '
                      'resolver-exchange sign-in.'),
                ),
                RadioListTile<String>(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  value: 'BEARER',
                  groupValue: _mode,
                  onChanged: (v) => setState(() => _mode = v ?? 'BEARER'),
                  title: const Text('Tenant bearer (L2)'),
                  subtitle: const Text('Paste a JWT verified by the tenant trust chain.'),
                ),
                if (_mode == 'VERIFIED_PHONE') ...[
                  const SizedBox(height: 8),
                  TextField(
                    controller: _phoneCtrl,
                    decoration: const InputDecoration(
                        isDense: true, labelText: 'Phone (E.164)', hintText: '+15551234567'),
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: _nameCtrl,
                    decoration: const InputDecoration(
                        isDense: true, labelText: 'Name (optional)'),
                  ),
                ],
                if (_mode == 'BEARER') ...[
                  const SizedBox(height: 8),
                  TextField(
                    controller: _bearerCtrl,
                    minLines: 1,
                    maxLines: 3,
                    decoration: const InputDecoration(
                        isDense: true, labelText: 'Bearer JWT'),
                  ),
                ],
                const SizedBox(height: 16),
                FilledButton.icon(
                  icon: state.starting
                      ? const SizedBox(
                          width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.play_arrow),
                  label: const Text('Start session'),
                  onPressed: state.starting ? null : _start,
                ),
                if (state.error != null) ...[
                  const SizedBox(height: 16),
                  Text(state.error!,
                      style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

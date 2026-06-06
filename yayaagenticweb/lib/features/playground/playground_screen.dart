import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../admin/profiles/profiles_screen.dart' show profilesProvider;
import 'playground_controller.dart';

class PlaygroundScreen extends ConsumerStatefulWidget {
  const PlaygroundScreen({super.key});

  @override
  ConsumerState<PlaygroundScreen> createState() => _PlaygroundScreenState();
}

class _PlaygroundScreenState extends ConsumerState<PlaygroundScreen> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _send([String? prefilled]) {
    final text = (prefilled ?? _controller.text).trim();
    if (text.isEmpty) return;
    _controller.clear();
    ref.read(playgroundProvider.notifier).sendMessage(text);
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(playgroundProvider);

    if (state.session == null) {
      return _ProfilePicker(error: state.error);
    }

    final session = state.session!;
    return Row(
      children: [
        Expanded(
          child: Column(
            children: [
              Material(
                color: Theme.of(context).colorScheme.surfaceContainerLow,
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Row(
                    children: [
                      const Icon(Icons.smart_toy_outlined),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(session.greeting,
                                style: Theme.of(context).textTheme.bodyLarge),
                            Text(
                              '${session.profileId}@${session.profileVersion} · ${session.sessionId.substring(0, 8)}…',
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              if (session.quickReplies.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
                  child: Wrap(
                    spacing: 8,
                    children: session.quickReplies
                        .map((q) => ActionChip(label: Text(q), onPressed: () => _send(q)))
                        .toList(),
                  ),
                ),
              Expanded(
                child: ListView.builder(
                  padding: const EdgeInsets.all(12),
                  itemCount: state.messages.length,
                  itemBuilder: (_, i) => _MessageBubble(message: state.messages[i]),
                ),
              ),
              SafeArea(
                top: false,
                child: Padding(
                  padding: const EdgeInsets.all(8),
                  child: Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: _controller,
                          enabled: !state.streaming,
                          onSubmitted: (_) => _send(),
                          decoration: const InputDecoration(
                            hintText: 'Type a message — try "echo hello"',
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      FilledButton.icon(
                        onPressed: state.streaming ? null : _send,
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
          width: 320,
          child: _DebugPanel(state: state),
        ),
      ],
    );
  }
}

class _MessageBubble extends StatelessWidget {
  const _MessageBubble({required this.message});
  final ChatMessage message;

  @override
  Widget build(BuildContext context) {
    final isUser = message.role == Role.user;
    final color = isUser
        ? Theme.of(context).colorScheme.primaryContainer
        : Theme.of(context).colorScheme.surfaceContainerHigh;
    return Align(
      alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 12),
        constraints: const BoxConstraints(maxWidth: 520),
        decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(12)),
        child: Text(message.text.isEmpty ? '…' : message.text),
      ),
    );
  }
}

class _DebugPanel extends StatelessWidget {
  const _DebugPanel({required this.state});
  final PlaygroundState state;

  @override
  Widget build(BuildContext context) {
    final lastAssistant = state.messages.lastWhere(
      (m) => m.role == Role.assistant,
      orElse: () => ChatMessage(role: Role.assistant, text: ''),
    );
    return Padding(
      padding: const EdgeInsets.all(12),
      child: ListView(
        children: [
          Text('Debug', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Tool calls', style: Theme.of(context).textTheme.titleSmall),
                  const SizedBox(height: 4),
                  if (lastAssistant.toolCalls.isEmpty)
                    const Text('none', style: TextStyle(color: Colors.grey))
                  else
                    ...lastAssistant.toolCalls.map((c) => Text('• ${c.tool}  ${c.args}',
                        style: const TextStyle(fontFamily: 'monospace', fontSize: 12))),
                  const SizedBox(height: 8),
                  Text('Tool results', style: Theme.of(context).textTheme.titleSmall),
                  const SizedBox(height: 4),
                  if (lastAssistant.toolResults.isEmpty)
                    const Text('none', style: TextStyle(color: Colors.grey))
                  else
                    ...lastAssistant.toolResults.map((r) => Text('• ${r.status} → ${r.value ?? r.error}',
                        style: const TextStyle(fontFamily: 'monospace', fontSize: 12))),
                ],
              ),
            ),
          ),
          if (state.error != null) ...[
            const SizedBox(height: 8),
            Card(
              color: Theme.of(context).colorScheme.errorContainer,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Text(state.error!),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _ProfilePicker extends ConsumerStatefulWidget {
  const _ProfilePicker({this.error});
  final String? error;

  @override
  ConsumerState<_ProfilePicker> createState() => _ProfilePickerState();
}

class _ProfilePickerState extends ConsumerState<_ProfilePicker> {
  String? _selected;

  @override
  Widget build(BuildContext context) {
    final profiles = ref.watch(profilesProvider);
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 480),
        child: Card(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Start a playground session',
                    style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 4),
                Text(
                  'Pick a profile registered in the admin. Principal impersonation lands in M5.',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 16),
                profiles.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (e, _) => Text('Failed to load profiles: $e',
                      style: TextStyle(color: Theme.of(context).colorScheme.error)),
                  data: (list) {
                    final value =
                        _selected ?? (list.isEmpty ? null : '${list.first.id}@${list.first.version}');
                    _selected ??= value;
                    return DropdownButtonFormField<String?>(
                      initialValue: value,
                      decoration: const InputDecoration(labelText: 'profile'),
                      items: list
                          .map((p) => DropdownMenuItem(
                                value: '${p.id}@${p.version}',
                                child: Text('${p.id}@${p.version}  —  ${p.displayName}'),
                              ))
                          .toList(),
                      onChanged: (v) => setState(() => _selected = v),
                    );
                  },
                ),
                const SizedBox(height: 16),
                FilledButton.icon(
                  icon: const Icon(Icons.play_arrow),
                  label: const Text('Start session'),
                  onPressed: _selected == null
                      ? null
                      : () {
                          final parts = _selected!.split('@');
                          ref.read(playgroundProvider.notifier).startSession(
                                profileId: parts[0],
                                profileVersion: int.tryParse(parts[1]) ?? 1,
                              );
                        },
                ),
                if (widget.error != null) ...[
                  const SizedBox(height: 16),
                  Text(widget.error!,
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

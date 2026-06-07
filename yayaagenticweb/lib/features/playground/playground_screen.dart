import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../models/inspector_snapshot.dart';
import '../../models/turn_event.dart';
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
              Expanded(
                child: ListView.builder(
                  padding: const EdgeInsets.all(12),
                  itemCount: state.messages.length,
                  itemBuilder: (_, i) => _MessageBubble(message: state.messages[i]),
                ),
              ),
              if (state.pendingConfirm != null)
                _ConfirmCard(
                  hint: state.pendingConfirm!,
                  onConfirm: () => _send('yes'),
                  onCancel: () => _send('no'),
                ),
              if (state.currentQuickReplies.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
                  child: Wrap(
                    spacing: 8,
                    children: state.currentQuickReplies
                        .map((q) => ActionChip(label: Text(q), onPressed: () => _send(q)))
                        .toList(),
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
          width: 380,
          child: _InspectorPanel(state: state),
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

/// Right-side inspector. Sections refresh whenever a turn completes
/// (controller calls /v1/sessions/{id}/inspector on stream end).
class _InspectorPanel extends StatelessWidget {
  const _InspectorPanel({required this.state});
  final PlaygroundState state;

  @override
  Widget build(BuildContext context) {
    final lastAssistant = state.messages.lastWhere(
      (m) => m.role == Role.assistant,
      orElse: () => ChatMessage(role: Role.assistant, text: ''),
    );
    final insp = state.inspector;
    return Padding(
      padding: const EdgeInsets.all(12),
      child: ListView(
        children: [
          Text('Inspector', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          _IntentSection(intent: insp?.intent),
          const SizedBox(height: 8),
          _ToolActivitySection(message: lastAssistant),
          const SizedBox(height: 8),
          _WorkingMemorySection(wm: insp?.workingMemory ?? const {}),
          const SizedBox(height: 8),
          if (insp?.lastDenial != null) ...[
            _DenialSection(denial: insp!.lastDenial!),
            const SizedBox(height: 8),
          ],
          _PromptSection(prompt: insp?.lastPrompt),
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

class _SectionCard extends StatelessWidget {
  const _SectionCard({required this.title, required this.child, this.color, this.trailing});
  final String title;
  final Widget child;
  final Color? color;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Card(
      color: color,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                    child: Text(title,
                        style: Theme.of(context).textTheme.titleSmall)),
                if (trailing != null) trailing!,
              ],
            ),
            const SizedBox(height: 6),
            child,
          ],
        ),
      ),
    );
  }
}

class _IntentSection extends StatelessWidget {
  const _IntentSection({required this.intent});
  final IntentSnapshot? intent;

  @override
  Widget build(BuildContext context) {
    final i = intent;
    return _SectionCard(
      title: 'Active intent',
      child: i == null || (i.label == null && i.slots.isEmpty)
          ? const Text('no intent yet', style: TextStyle(color: Colors.grey))
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(i.label ?? '(unlabeled)',
                    style: const TextStyle(fontWeight: FontWeight.w600)),
                const SizedBox(height: 4),
                if (i.slots.isEmpty)
                  const Text('no slots', style: TextStyle(color: Colors.grey, fontSize: 12))
                else
                  ...i.slots.entries.map((e) => Text('• ${e.key} = ${e.value}',
                      style: const TextStyle(fontFamily: 'monospace', fontSize: 12))),
                if (i.parkedStack.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Text('Parked (${i.parkedStack.length})',
                      style: Theme.of(context).textTheme.labelSmall),
                  ...i.parkedStack.map((p) => Text(
                      '↩ ${p.label ?? "(unlabeled)"}  · ${p.reason ?? ""}',
                      style: const TextStyle(fontFamily: 'monospace', fontSize: 12))),
                ],
              ],
            ),
    );
  }
}

class _ToolActivitySection extends StatelessWidget {
  const _ToolActivitySection({required this.message});
  final ChatMessage message;

  @override
  Widget build(BuildContext context) {
    return _SectionCard(
      title: 'Tool activity (this turn)',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (message.toolCalls.isEmpty)
            const Text('no tool calls', style: TextStyle(color: Colors.grey, fontSize: 12))
          else
            ...message.toolCalls.map((c) => Text('→ ${c.tool}  ${c.args}',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12))),
          if (message.toolResults.isNotEmpty) ...[
            const SizedBox(height: 4),
            ...message.toolResults.map((r) => Text('← ${r.status}  ${r.value ?? r.error ?? ""}',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12))),
          ],
        ],
      ),
    );
  }
}

class _WorkingMemorySection extends StatelessWidget {
  const _WorkingMemorySection({required this.wm});
  final Map<String, dynamic> wm;

  @override
  Widget build(BuildContext context) {
    return _SectionCard(
      title: 'Working memory',
      child: wm.isEmpty
          ? const Text('empty', style: TextStyle(color: Colors.grey, fontSize: 12))
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: wm.entries
                  .map((e) => Padding(
                        padding: const EdgeInsets.symmetric(vertical: 1),
                        child: Text('${e.key}: ${e.value}',
                            style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
                      ))
                  .toList(),
            ),
    );
  }
}

class _DenialSection extends StatelessWidget {
  const _DenialSection({required this.denial});
  final DenialSnapshot denial;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return _SectionCard(
      title: 'Last denial',
      color: scheme.errorContainer,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (denial.toolId != null)
            Text('tool: ${denial.toolId}',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
          const SizedBox(height: 4),
          Text('User-safe',
              style: Theme.of(context).textTheme.labelSmall),
          Text(denial.userReason ?? '—',
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
          const SizedBox(height: 4),
          Text('Audit (operator-only)',
              style: Theme.of(context).textTheme.labelSmall),
          Text(denial.auditReason ?? '—',
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
          if (denial.policyTrace.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text('Policy trace',
                style: Theme.of(context).textTheme.labelSmall),
            Text(denial.policyTrace.toString(),
                style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
          ],
        ],
      ),
    );
  }
}

class _PromptSection extends StatefulWidget {
  const _PromptSection({required this.prompt});
  final PromptSnapshot? prompt;

  @override
  State<_PromptSection> createState() => _PromptSectionState();
}

class _PromptSectionState extends State<_PromptSection> {
  bool _expandedPrefix = false;

  @override
  Widget build(BuildContext context) {
    final p = widget.prompt;
    final prefixChars = p?.cacheablePrefix.length ?? 0;
    return _SectionCard(
      title: 'Last prompt',
      trailing: p == null
          ? null
          : _CacheBadge(prefixChars: prefixChars),
      child: p == null
          ? const Text('no prompt yet', style: TextStyle(color: Colors.grey, fontSize: 12))
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text('Cacheable prefix  ($prefixChars chars)',
                        style: Theme.of(context).textTheme.labelSmall),
                    const Spacer(),
                    TextButton(
                      onPressed: () =>
                          setState(() => _expandedPrefix = !_expandedPrefix),
                      child: Text(_expandedPrefix ? 'collapse' : 'expand'),
                    ),
                  ],
                ),
                if (_expandedPrefix)
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(8),
                    color: Theme.of(context).colorScheme.surfaceContainerHighest,
                    child: SelectableText(p.cacheablePrefix,
                        style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
                  ),
                const SizedBox(height: 8),
                Text('Variable suffix  (${p.variableSuffix.length} chars)',
                    style: Theme.of(context).textTheme.labelSmall),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(8),
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                  child: SelectableText(p.variableSuffix,
                      style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
                ),
              ],
            ),
    );
  }
}

/// Heuristic cache-hit badge: Anthropic only caches a prefix when it's at
/// least ~1024 tokens (~4096 chars is a safe lower bound) and stable across
/// turns. We can't observe a real hit without the AnthropicApi-direct path,
/// so this is a "cache-eligible" indicator, not a confirmed hit.
class _CacheBadge extends StatelessWidget {
  const _CacheBadge({required this.prefixChars});
  final int prefixChars;

  @override
  Widget build(BuildContext context) {
    final eligible = prefixChars >= 4096;
    final color = eligible
        ? Colors.green.shade700
        : Theme.of(context).colorScheme.outline;
    return Tooltip(
      message: eligible
          ? 'Prefix is long enough to be cache-eligible on Anthropic.'
          : 'Prefix is below Anthropic\'s rough caching threshold (~4k chars).',
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.15),
          borderRadius: BorderRadius.circular(4),
          border: Border.all(color: color),
        ),
        child: Text(
          eligible ? 'cache-eligible' : 'below cache threshold',
          style: TextStyle(color: color, fontSize: 10, fontWeight: FontWeight.w600),
        ),
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
                        style: Theme.of(context).textTheme.titleSmall),
                  ),
                ],
              ),
              if (args != null) ...[
                const SizedBox(height: 6),
                Text(
                  '${toolId ?? "tool"}  ·  $args',
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                ),
              ],
              const SizedBox(height: 10),
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  OutlinedButton.icon(
                    onPressed: onCancel,
                    icon: const Icon(Icons.close),
                    label: const Text('Cancel'),
                  ),
                  const SizedBox(width: 8),
                  FilledButton.icon(
                    onPressed: onConfirm,
                    icon: const Icon(Icons.check),
                    label: const Text('Confirm'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

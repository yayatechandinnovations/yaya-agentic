import 'dart:async';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:just_audio/just_audio.dart';
import 'package:record/record.dart';

import '../../config.dart';
import '../../models/chat_message.dart';
import '../../l10n/app_localizations.dart';
import 'chat_controller.dart';
import 'recorded_bytes.dart';

/// Composer media affordances (chat-experience-design.md §6): the attach
/// picker, the pending-attachment tray, the voice recorder, and the voice
/// playback bubble.

/* ------------------------------ attach picker ----------------------------- */

Future<void> showAttachPicker(
    BuildContext context, WidgetRef ref, String handle) async {
  final t = AppL10n.of(context);
  final choice = await showModalBottomSheet<String>(
    context: context,
    showDragHandle: true,
    builder: (ctx) => SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          ListTile(
            leading: const Icon(Icons.photo_outlined),
            title: Text(t.attachPhoto),
            onTap: () => Navigator.pop(ctx, 'photo'),
          ),
          ListTile(
            leading: const Icon(Icons.description_outlined),
            title: Text(t.attachDocument),
            onTap: () => Navigator.pop(ctx, 'document'),
          ),
        ],
      ),
    ),
  );
  if (choice == null) return;

  try {
    final result = await FilePicker.platform.pickFiles(
      type: choice == 'photo' ? FileType.image : FileType.custom,
      allowedExtensions: choice == 'photo' ? null : ['pdf'],
      withData: true,
    );
    final file = result?.files.firstOrNull;
    if (file == null || file.bytes == null) return;
    await ref
        .read(chatControllerProvider(handle).notifier)
        .attach(file.bytes!, file.name);
  } catch (_) {
    // A broken/unregistered picker plugin (e.g. a stale web build) must
    // degrade to a message, never an uncaught error.
    if (context.mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(t.attachFailed)));
    }
  }
}

/* ---------------------------- pending tray -------------------------------- */

class AttachmentTray extends ConsumerWidget {
  const AttachmentTray({super.key, required this.handle});

  final String handle;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pending = ref.watch(
        chatControllerProvider(handle).select((s) => s.pendingAttachments));
    if (pending.isEmpty) return const SizedBox.shrink();
    final t = AppL10n.of(context);
    final scheme = Theme.of(context).colorScheme;
    return Container(
      height: 56,
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: pending.length,
        separatorBuilder: (_, _) => const SizedBox(width: 8),
        itemBuilder: (context, i) {
          final p = pending[i];
          return Container(
            padding: const EdgeInsets.symmetric(horizontal: 10),
            decoration: BoxDecoration(
              color: scheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                  color: p.failed ? scheme.error : scheme.outlineVariant),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (p.uploading)
                  const SizedBox(
                      width: 16, height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2))
                else if (p.failed)
                  IconButton(
                    visualDensity: VisualDensity.compact,
                    icon: Icon(Icons.refresh, color: scheme.error, size: 18),
                    tooltip: t.retry,
                    onPressed: () => ref
                        .read(chatControllerProvider(handle).notifier)
                        .retryAttachment(p.localId),
                  )
                else
                  Icon(
                    p.isVoice
                        ? Icons.mic
                        : (p.uploaded?.isImage ?? false)
                            ? Icons.photo_outlined
                            : Icons.description_outlined,
                    size: 18,
                    color: scheme.primary,
                  ),
                const SizedBox(width: 6),
                ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 120),
                  child: Text(
                    p.failed ? (p.error ?? t.attachFailed) : p.filename,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
                IconButton(
                  visualDensity: VisualDensity.compact,
                  icon: const Icon(Icons.close, size: 16),
                  onPressed: () => ref
                      .read(chatControllerProvider(handle).notifier)
                      .removeAttachment(p.localId),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}

/* ---------------------------- voice recorder ------------------------------ */

class VoiceRecorderButton extends ConsumerStatefulWidget {
  const VoiceRecorderButton(
      {super.key, required this.handle, required this.accent, required this.enabled});

  final String handle;
  final Color accent;
  final bool enabled;

  @override
  ConsumerState<VoiceRecorderButton> createState() => _VoiceRecorderButtonState();
}

class _VoiceRecorderButtonState extends ConsumerState<VoiceRecorderButton> {
  static const _maxDuration = Duration(minutes: 2);

  final AudioRecorder _recorder = AudioRecorder();
  bool _recording = false;
  int _seconds = 0;
  Timer? _ticker;

  @override
  void dispose() {
    _ticker?.cancel();
    _recorder.dispose();
    super.dispose();
  }

  Future<void> _start() async {
    final t = AppL10n.of(context);
    final messenger = ScaffoldMessenger.of(context);
    try {
      if (!await _recorder.hasPermission()) {
        messenger.showSnackBar(SnackBar(content: Text(t.voiceMicDenied)));
        return;
      }
      // Opus/webm on web (aac unsupported there); AAC/m4a everywhere else —
      // both are on the server allowlist.
      final config = RecordConfig(
          encoder: kIsWeb ? AudioEncoder.opus : AudioEncoder.aacLc);
      final dir = kIsWeb ? '' : (await _tempPath());
      await _recorder.start(config,
          path: '$dir/voice-note.${kIsWeb ? 'webm' : 'm4a'}');
    } catch (_) {
      // Unregistered/broken recorder plugin: message, never an uncaught error.
      messenger.showSnackBar(SnackBar(content: Text(t.attachFailed)));
      return;
    }
    setState(() {
      _recording = true;
      _seconds = 0;
    });
    _ticker = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) return;
      setState(() => _seconds++);
      if (_seconds >= _maxDuration.inSeconds) _stop(send: true);
    });
  }

  Future<String> _tempPath() async => '.'; // record resolves relative paths

  Future<void> _stop({required bool send}) async {
    _ticker?.cancel();
    String? path;
    try {
      path = await _recorder.stop();
    } catch (_) {
      path = null;
    }
    if (!mounted) return;
    setState(() => _recording = false);
    if (!send || path == null) return;
    try {
      final bytes = await readRecordedBytes(path);
      // A recording that produced (almost) nothing is a failure the user must
      // see — a header-only file would upload "fine" and be unplayable.
      if (bytes.length < 200) {
        throw StateError('empty recording (${bytes.length} bytes)');
      }
      await ref.read(chatControllerProvider(widget.handle).notifier).attach(
          bytes, 'voice-note.${kIsWeb ? 'webm' : 'm4a'}',
          isVoice: true);
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(AppL10n.of(context).attachFailed)));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_recording) {
      final scheme = Theme.of(context).colorScheme;
      final mm = (_seconds ~/ 60).toString();
      final ss = (_seconds % 60).toString().padLeft(2, '0');
      return Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            icon: Icon(Icons.delete_outline, color: scheme.error),
            tooltip: AppL10n.of(context).voiceDiscard,
            onPressed: () => _stop(send: false),
          ),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: scheme.errorContainer,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(children: [
              Icon(Icons.fiber_manual_record, size: 12, color: scheme.error),
              const SizedBox(width: 4),
              Text('$mm:$ss'),
            ]),
          ),
          IconButton.filled(
            style: IconButton.styleFrom(backgroundColor: widget.accent),
            icon: const Icon(Icons.stop),
            tooltip: AppL10n.of(context).voiceStop,
            onPressed: () => _stop(send: true),
          ),
        ],
      );
    }
    return IconButton(
      icon: const Icon(Icons.mic_none),
      tooltip: AppL10n.of(context).voiceRecord,
      onPressed: widget.enabled ? _start : null,
    );
  }
}

/* --------------------------- voice note bubble ---------------------------- */

class VoiceNoteBubble extends StatefulWidget {
  const VoiceNoteBubble({super.key, required this.attachment, required this.accent});

  final ChatAttachment attachment;
  final Color accent;

  @override
  State<VoiceNoteBubble> createState() => _VoiceNoteBubbleState();
}

class _VoiceNoteBubbleState extends State<VoiceNoteBubble> {
  final AudioPlayer _player = AudioPlayer();
  bool _loaded = false;
  bool _showTranscript = false;

  @override
  void dispose() {
    _player.dispose();
    super.dispose();
  }

  Future<void> _toggle() async {
    try {
      if (!_loaded) {
        await _player.setUrl(AppConfig.resolveUrl(widget.attachment.url));
        _loaded = true;
      }
      if (_player.playing) {
        await _player.pause();
      } else {
        if (_player.processingState == ProcessingState.completed) {
          await _player.seek(Duration.zero);
        }
        unawaited(_player.play());
      }
    } catch (_) {
      // Expired signed URL or a broken audio plugin: the transcript is still
      // there; playback failure must never take the screen down.
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(AppL10n.of(context).attachFailed)));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    final scheme = Theme.of(context).colorScheme;
    final transcript = widget.attachment.transcript;
    return Container(
      constraints: const BoxConstraints(maxWidth: 260),
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: scheme.outlineVariant),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              StreamBuilder<PlayerState>(
                stream: _player.playerStateStream,
                builder: (context, snap) {
                  final playing = snap.data?.playing ?? false;
                  final done = snap.data?.processingState == ProcessingState.completed;
                  return IconButton.filled(
                    style: IconButton.styleFrom(backgroundColor: widget.accent),
                    icon: Icon(playing && !done ? Icons.pause : Icons.play_arrow),
                    onPressed: _toggle,
                  );
                },
              ),
              const SizedBox(width: 4),
              Expanded(
                child: StreamBuilder<Duration>(
                  stream: _player.positionStream,
                  builder: (context, snap) {
                    final pos = snap.data ?? Duration.zero;
                    final total = _player.duration ?? Duration.zero;
                    final progress = total.inMilliseconds == 0
                        ? 0.0
                        : (pos.inMilliseconds / total.inMilliseconds).clamp(0.0, 1.0);
                    return Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        LinearProgressIndicator(
                            value: progress, color: widget.accent, minHeight: 3),
                        const SizedBox(height: 4),
                        Text(_fmt(total == Duration.zero ? pos : total),
                            style: Theme.of(context).textTheme.bodySmall),
                      ],
                    );
                  },
                ),
              ),
              const SizedBox(width: 8),
              const Icon(Icons.mic, size: 16),
            ],
          ),
          if (transcript != null && transcript.isNotEmpty) ...[
            InkWell(
              onTap: () => setState(() => _showTranscript = !_showTranscript),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Text(
                  _showTranscript ? t.voiceHideTranscript : t.voiceShowTranscript,
                  style: Theme.of(context)
                      .textTheme
                      .bodySmall
                      ?.copyWith(color: scheme.primary),
                ),
              ),
            ),
            if (_showTranscript)
              Text(transcript, style: Theme.of(context).textTheme.bodySmall),
          ],
        ],
      ),
    );
  }

  static String _fmt(Duration d) {
    final mm = d.inMinutes.toString();
    final ss = (d.inSeconds % 60).toString().padLeft(2, '0');
    return '$mm:$ss';
  }
}

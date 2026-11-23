import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../ui/data/relative_time.dart';
import '../../ui/tokens.dart';
import '../live/admin_feed.dart';

/// Live · Syncing · Reconnecting · Offline — always a word, never only a dot.
class SyncIndicator extends ConsumerStatefulWidget {
  const SyncIndicator({super.key, this.compact = false});
  final bool compact;

  @override
  ConsumerState<SyncIndicator> createState() => _SyncIndicatorState();
}

class _SyncIndicatorState extends ConsumerState<SyncIndicator> {
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    _tick = Timer.periodic(const Duration(seconds: 20), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _tick?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final feed = ref.watch(adminFeedProvider);
    if (feed.status == SyncStatus.idle) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    final s = StatusColors.of(context);
    final last = feed.lastSyncAt;
    final (label, sub, color, dot) = switch (feed.status) {
      SyncStatus.live => ('Live', 'synced ${formatRelative(last).toLowerCase()}', s.onSuccessContainer, s.success),
      SyncStatus.connecting => ('Connecting…', null, scheme.onSurfaceVariant, scheme.outline),
      SyncStatus.reconnecting => ('Reconnecting', 'refreshing every 30 s', s.onWarningContainer, s.warning),
      SyncStatus.offline => ('Offline', last == null ? null : 'data from ${TimeOfDay.fromDateTime(last).format(context)}', scheme.onSurfaceVariant, null),
      SyncStatus.idle => ('', null, scheme.onSurfaceVariant, null),
    };
    final tooltip = switch (feed.status) {
      SyncStatus.live => 'Changes appear automatically. Click to refresh everything now.',
      SyncStatus.reconnecting => 'Live updates paused; lists refresh on focus and every 30 s. Click to retry now.',
      SyncStatus.offline => 'No network. Saving is paused; lists refresh when you’re back online.',
      _ => 'Click to refresh now.',
    };
    return Tooltip(
      message: tooltip,
      child: TextButton(
        onPressed: () => ref.read(adminFeedProvider.notifier).refreshNow(),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: dot,
              border: dot == null ? Border.all(color: scheme.outline, width: 2) : null,
            ),
          ),
          const SizedBox(width: 8),
          Text(label, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: color)),
          if (sub != null && !widget.compact) ...[
            const SizedBox(width: 6),
            Text('· $sub', style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
          ],
        ]),
      ),
    );
  }
}

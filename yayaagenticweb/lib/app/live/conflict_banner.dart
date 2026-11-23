import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../ui/form/y_form_layout.dart';
import 'live_resource.dart';
import 'resource_kind.dart';

/// Shown in an editor when the record it's editing gets a newer version (or
/// is deleted) while the operator is working. Their edits stay put; they
/// choose to look at the new version or publish on top of it.
class LiveConflictBanner extends ConsumerWidget {
  const LiveConflictBanner({
    super.key,
    required this.kind,
    required this.id,
    required this.baseVersion,
    required this.dirty,
    this.onReview,
    this.onKeepMine,
    this.noun = 'This record',
  });

  final AdminResourceKind kind;
  final String id;
  final int? baseVersion;
  final bool dirty;
  final VoidCallback? onReview;

  /// Accept the newer version as the base; the next save publishes on top of it.
  final void Function(int newBase)? onKeepMine;
  final String noun;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final change = ref.watch(recentChangeProvider((kind: kind, id: id)));
    if (change == null || baseVersion == null) return const SizedBox.shrink();
    if (change.op == AdminChangeOp.deleted) {
      return YBanner(tone: YBannerTone.error, title: '$noun was deleted.', message: 'Saving will create it again.');
    }
    final v = change.version;
    if (v == null || v <= baseVersion!) return const SizedBox.shrink();
    if (!dirty) {
      return YBanner(
        tone: YBannerTone.info,
        title: 'v$v was published.',
        message: 'You haven’t changed anything — reload to edit the latest version.',
        actions: [if (onReview != null) OutlinedButton(onPressed: onReview, child: Text('Load v$v'))],
      );
    }
    return YBanner(
      tone: YBannerTone.warning,
      title: 'v$v was published while you were editing.',
      message: 'Your changes are still here. Review what changed, or keep yours — saving then publishes v${v + 1} on top of it.',
      actions: [
        if (onReview != null) OutlinedButton(onPressed: onReview, child: Text('Review v$v')),
        if (onKeepMine != null) FilledButton(onPressed: () => onKeepMine!(v), child: const Text('Keep my changes')),
      ],
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/forum.dart';
import '../chats/chats_admin_screen.dart' show chatEntryPointsProvider;
import '../shared/admin_shared.dart';

final forumSettingsProvider = FutureProvider.family<ForumSettingsResponse,
    ({String tenant, String entryPoint})>(
  (ref, key) async {
    final api = await ref.watch(adminApiProvider.future);
    return api.getForumSettings(key.tenant, key.entryPoint);
  },
);

final forumPostsProvider = FutureProvider.family<List<AdminForumPostView>,
    ({String tenant, String entryPoint})>(
  (ref, key) async {
    final api = await ref.watch(adminApiProvider.future);
    return api.listForumPosts(key.tenant, key.entryPoint);
  },
);

final forumReportsProvider =
    FutureProvider.family<List<ForumReportView>, String>(
  (ref, tenant) async {
    final api = await ref.watch(adminApiProvider.future);
    return api.listForumReports(tenant);
  },
);

/// Forum moderation console: per-entry-point community feed toggle, the post
/// stream with pin/hide/delete moderation, a post-as-brand composer, and the
/// tenant's open-reports queue. Hiding or unhiding a post resolves its open
/// reports server-side, so both lists refresh after every moderation call.
class ForumScreen extends ConsumerStatefulWidget {
  const ForumScreen({super.key});

  @override
  ConsumerState<ForumScreen> createState() => _ForumScreenState();
}

class _ForumScreenState extends ConsumerState<ForumScreen> {
  final _bodyCtrl = TextEditingController();
  final _displayNameCtrl = TextEditingController();
  bool _pinNewPost = false;
  String? _entryPointId;
  bool _posting = false;
  bool _togglingEnabled = false;

  @override
  void dispose() {
    _bodyCtrl.dispose();
    _displayNameCtrl.dispose();
    super.dispose();
  }

  ({String tenant, String entryPoint})? _key() {
    final tenant = ref.read(currentTenantOrNull);
    final ep = _entryPointId;
    if (tenant == null || ep == null) return null;
    return (tenant: tenant, entryPoint: ep);
  }

  void _refreshPostsAndReports() {
    final key = _key();
    if (key == null) return;
    ref.invalidate(forumPostsProvider(key));
    ref.invalidate(forumReportsProvider(key.tenant));
  }

  Future<void> _setEnabled(bool enabled) async {
    final key = _key();
    if (key == null) return;
    setState(() => _togglingEnabled = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.updateForumSettings(key.tenant, key.entryPoint,
          enabled: enabled);
      ref.invalidate(forumSettingsProvider(key));
      if (mounted) {
        showSnack(context, enabled ? 'Forum enabled' : 'Forum disabled');
      }
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _togglingEnabled = false);
    }
  }

  Future<void> _postAsBrand() async {
    final key = _key();
    if (key == null) return;
    if (_bodyCtrl.text.trim().isEmpty) {
      showSnack(context, 'Post body is required.', error: true);
      return;
    }
    setState(() => _posting = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.createForumPost(
        key.tenant,
        key.entryPoint,
        AdminForumPostRequest(
          body: _bodyCtrl.text.trim(),
          displayName: _displayNameCtrl.text.trim().isEmpty
              ? null
              : _displayNameCtrl.text.trim(),
          pinned: _pinNewPost ? true : null,
        ),
      );
      ref.invalidate(forumPostsProvider(key));
      if (!mounted) return;
      setState(() {
        _bodyCtrl.clear();
        _displayNameCtrl.clear();
        _pinNewPost = false;
      });
      showSnack(context, 'Posted');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _posting = false);
    }
  }

  Future<void> _moderate(String postId,
      {bool? hidden, bool? pinned, bool? delete}) async {
    final key = _key();
    if (key == null) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.moderateForumPost(key.tenant, postId,
          ForumModerationRequest(hidden: hidden, pinned: pinned, delete: delete));
      _refreshPostsAndReports();
      if (mounted) showSnack(context, 'Post updated');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  Future<void> _delete(AdminForumPostView post) async {
    final confirmed = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('Delete this post?'),
            content: Text(
                'The post by ${post.authorDisplay.isEmpty ? 'this author' : post.authorDisplay} '
                'will be soft-deleted and disappear from the feed'
                '${post.replyCount > 0 ? ', along with its ${post.replyCount} '
                    'repl${post.replyCount == 1 ? 'y' : 'ies'}' : ''}.'),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Cancel')),
              FilledButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: const Text('Delete')),
            ],
          ),
        ) ??
        false;
    if (!confirmed) return;
    await _moderate(post.id, delete: true);
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Forums');
    }
    final entryPoints = ref.watch(chatEntryPointsProvider(tenant));

    return AdminTwoPane(
      title: 'Forum',
      subtitle:
          'Community feed attached to a chat entry point. Toggle it per entry '
          'point, moderate the stream (pin, hide, delete), post as the brand, '
          'and work the reports queue — hiding a post resolves its open reports.',
      list: entryPoints.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e),
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (epList) {
          final eligible = epList.where((e) => !e.isDeactivated).toList();
          if (eligible.isEmpty) {
            return const Center(
                child: Text(
                    'Create a chat entry point first; each forum lives on one.'));
          }
          if (_entryPointId == null ||
              !eligible.any((e) => e.id == _entryPointId)) {
            _entryPointId = eligible.first.id;
          }
          final key = (tenant: tenant, entryPoint: _entryPointId!);
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              DropdownButtonFormField<String>(
                initialValue: _entryPointId,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Entry point'),
                items: eligible
                    .map((e) => DropdownMenuItem(
                          value: e.id,
                          child: Text('${e.displayName} (@${e.handle})'),
                        ))
                    .toList(),
                onChanged: (v) => setState(() => _entryPointId = v),
              ),
              const SizedBox(height: 8),
              _enabledSwitch(key),
              const SizedBox(height: 8),
              Expanded(child: _postList(key)),
            ],
          );
        },
      ),
      form: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            FormCard(
              title: 'Post as brand',
              actions: [
                FilledButton(
                  onPressed: _posting ? null : _postAsBrand,
                  child: _posting
                      ? const SizedBox.square(
                          dimension: 16,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('Post'),
                ),
              ],
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  TextField(
                    controller: _bodyCtrl,
                    maxLines: 6,
                    minLines: 3,
                    decoration: const InputDecoration(
                      labelText: 'Body',
                      alignLabelWithHint: true,
                      helperText:
                          'Published to the selected entry point\'s forum as an '
                          'OPERATOR post.',
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _displayNameCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Display name (optional)',
                      helperText:
                          'Overrides the default brand byline for this post.',
                    ),
                  ),
                  CheckboxListTile(
                    value: _pinNewPost,
                    onChanged: (v) =>
                        setState(() => _pinNewPost = v ?? false),
                    title: const Text('Pin this post'),
                    controlAffinity: ListTileControlAffinity.leading,
                    contentPadding: EdgeInsets.zero,
                    dense: true,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            _reportsPanel(tenant),
          ],
        ),
      ),
    );
  }

  Widget _enabledSwitch(({String tenant, String entryPoint}) key) {
    final settings = ref.watch(forumSettingsProvider(key));
    return Card(
      child: settings.when(
        loading: () => const ListTile(
          title: Text('Forum enabled'),
          trailing: SizedBox.square(
              dimension: 16, child: CircularProgressIndicator(strokeWidth: 2)),
        ),
        error: (e, _) => ListTile(
          title: const Text('Forum enabled'),
          subtitle: Text(formatError(e),
              style: TextStyle(color: Theme.of(context).colorScheme.error)),
        ),
        data: (s) => SwitchListTile(
          value: s.enabled,
          onChanged: _togglingEnabled ? null : _setEnabled,
          title: const Text('Forum enabled'),
          subtitle: Text(s.enabled
              ? 'Chat users of this entry point can read and post.'
              : 'The forum is hidden from chat users of this entry point.'),
        ),
      ),
    );
  }

  Widget _postList(({String tenant, String entryPoint}) key) {
    final posts = ref.watch(forumPostsProvider(key));
    return posts.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Text(formatError(e),
          style: TextStyle(color: Theme.of(context).colorScheme.error)),
      data: (list) {
        if (list.isEmpty) {
          return const Center(child: Text('No posts yet.'));
        }
        return ListView.separated(
          itemBuilder: (_, i) => _PostTile(
            post: list[i],
            onPin: () => _moderate(list[i].id, pinned: !list[i].pinned),
            onHide: () => _moderate(list[i].id, hidden: !list[i].hidden),
            onDelete: () => _delete(list[i]),
          ),
          separatorBuilder: (_, i) => const SizedBox(height: 8),
          itemCount: list.length,
        );
      },
    );
  }

  Widget _reportsPanel(String tenant) {
    final reports = ref.watch(forumReportsProvider(tenant));
    final posts = _entryPointId == null
        ? const <AdminForumPostView>[]
        : ref
                .watch(forumPostsProvider(
                    (tenant: tenant, entryPoint: _entryPointId!)))
                .valueOrNull ??
            const <AdminForumPostView>[];
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Open reports', style: theme.textTheme.titleMedium),
            const SizedBox(height: 4),
            Text(
              'User reports across all of this tenant\'s forums. Hiding (or '
              'unhiding) the reported post resolves its reports.',
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.outline),
            ),
            const SizedBox(height: 12),
            reports.when(
              loading: () => const Center(
                  child: Padding(
                      padding: EdgeInsets.all(16),
                      child: CircularProgressIndicator())),
              error: (e, _) => Text(formatError(e),
                  style: TextStyle(color: theme.colorScheme.error)),
              data: (list) {
                if (list.isEmpty) {
                  return Padding(
                    padding: const EdgeInsets.all(8),
                    child: Text('No open reports.',
                        style:
                            TextStyle(color: theme.colorScheme.outline)),
                  );
                }
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    for (final report in list) ...[
                      _ReportTile(
                        report: report,
                        post: posts
                            .where((p) => p.id == report.postId)
                            .firstOrNull,
                        onHide: (hidden) =>
                            _moderate(report.postId, hidden: hidden),
                      ),
                      if (report != list.last) const SizedBox(height: 8),
                    ],
                  ],
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}

class _PostTile extends StatelessWidget {
  const _PostTile({
    required this.post,
    required this.onPin,
    required this.onHide,
    required this.onDelete,
  });

  final AdminForumPostView post;
  final VoidCallback onPin;
  final VoidCallback onHide;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 8, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                _AuthorBadge(kind: post.authorKind),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    post.authorDisplay.isEmpty
                        ? '(anonymous)'
                        : post.authorDisplay,
                    style: theme.textTheme.titleSmall,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                if (post.createdAt != null)
                  Text(post.createdAt!,
                      style: theme.textTheme.labelSmall
                          ?.copyWith(color: theme.colorScheme.outline)),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              post.body,
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: post.hidden ? theme.colorScheme.outline : null,
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: Wrap(
                    spacing: 6,
                    runSpacing: 4,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      if (post.pinned)
                        const _MiniChip(icon: Icons.push_pin, label: 'Pinned'),
                      if (post.hidden)
                        const _MiniChip(
                            icon: Icons.visibility_off, label: 'Hidden'),
                      if (post.replyTo != null)
                        _MiniChip(
                            icon: Icons.reply, label: 'reply to ${post.replyTo}'),
                      _MiniChip(
                          icon: Icons.forum_outlined,
                          label:
                              '${post.replyCount} repl${post.replyCount == 1 ? 'y' : 'ies'}'),
                      if (post.reportCount > 0)
                        _MiniChip(
                          icon: Icons.flag,
                          label:
                              '${post.reportCount} report${post.reportCount == 1 ? '' : 's'}',
                          background: theme.colorScheme.errorContainer,
                          foreground: theme.colorScheme.onErrorContainer,
                        ),
                    ],
                  ),
                ),
                IconButton(
                  tooltip: post.pinned ? 'Unpin' : 'Pin',
                  icon: Icon(
                      post.pinned ? Icons.push_pin : Icons.push_pin_outlined,
                      size: 18),
                  onPressed: onPin,
                ),
                IconButton(
                  tooltip: post.hidden ? 'Unhide' : 'Hide',
                  icon: Icon(
                      post.hidden
                          ? Icons.visibility_outlined
                          : Icons.visibility_off_outlined,
                      size: 18),
                  onPressed: onHide,
                ),
                IconButton(
                  tooltip: 'Delete',
                  icon: const Icon(Icons.delete_outline, size: 18),
                  color: theme.colorScheme.error,
                  onPressed: onDelete,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _ReportTile extends StatelessWidget {
  const _ReportTile({
    required this.report,
    required this.post,
    required this.onHide,
  });

  final ForumReportView report;

  /// The reported post when it belongs to the currently selected entry
  /// point's loaded feed; null otherwise (the moderation PATCH still works
  /// by post id).
  final AdminForumPostView? post;
  final void Function(bool hidden) onHide;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final hidden = post?.hidden ?? false;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.flag, size: 16, color: theme.colorScheme.error),
              const SizedBox(width: 6),
              Expanded(
                child: Text(report.reason,
                    style: theme.textTheme.titleSmall,
                    overflow: TextOverflow.ellipsis),
              ),
              if (report.createdAt != null)
                Text(report.createdAt!,
                    style: theme.textTheme.labelSmall
                        ?.copyWith(color: theme.colorScheme.outline)),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            post != null
                ? '${post!.authorDisplay.isEmpty ? '(anonymous)' : post!.authorDisplay}: ${post!.body}'
                : 'Post ${report.postId} (not in the selected entry point\'s feed)',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: theme.textTheme.bodySmall,
          ),
          const SizedBox(height: 4),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton.icon(
              onPressed: () => onHide(!hidden),
              icon: Icon(
                  hidden
                      ? Icons.visibility_outlined
                      : Icons.visibility_off_outlined,
                  size: 16),
              label: Text(hidden ? 'Unhide post' : 'Hide post'),
            ),
          ),
        ],
      ),
    );
  }
}

class _AuthorBadge extends StatelessWidget {
  const _AuthorBadge({required this.kind});
  final String kind; // USER | SYSTEM | OPERATOR

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final (bg, fg) = switch (kind) {
      'OPERATOR' => (scheme.primaryContainer, scheme.onPrimaryContainer),
      'SYSTEM' => (scheme.tertiaryContainer, scheme.onTertiaryContainer),
      _ => (scheme.surfaceContainerHighest, scheme.onSurfaceVariant),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Text(kind,
          style: TextStyle(
              fontSize: 10, fontWeight: FontWeight.w600, color: fg)),
    );
  }
}

class _MiniChip extends StatelessWidget {
  const _MiniChip({
    required this.icon,
    required this.label,
    this.background,
    this.foreground,
  });

  final IconData icon;
  final String label;
  final Color? background;
  final Color? foreground;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final bg = background ?? scheme.surfaceContainerHighest;
    final fg = foreground ?? scheme.onSurfaceVariant;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 12, color: fg),
          const SizedBox(width: 4),
          Text(label, style: TextStyle(fontSize: 11, color: fg)),
        ],
      ),
    );
  }
}

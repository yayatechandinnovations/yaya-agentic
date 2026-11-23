import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/chat_entry_point.dart';
import '../../../models/admin/forum.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/form/y_resource_picker.dart';
import '../../../ui/tokens.dart';
import '../chats/chats_admin_screen.dart' show chatEntryPointsProvider;
import '../shared/admin_shared.dart';

typedef ForumKey = ({String tenant, String entryPoint});

final forumSettingsProvider = FutureProvider.family<ForumSettingsResponse, ForumKey>(
  (ref, key) async {
    liveResource(ref, AdminResourceKind.forum);
    final api = await ref.watch(adminApiProvider.future);
    return api.getForumSettings(key.tenant, key.entryPoint);
  },
);

final forumPostsProvider = FutureProvider.family<List<AdminForumPostView>, ForumKey>(
  (ref, key) async {
    liveResource(ref, AdminResourceKind.forum);
    final api = await ref.watch(adminApiProvider.future);
    return api.listForumPosts(key.tenant, key.entryPoint);
  },
);

final forumReportsProvider = FutureProvider.family<List<ForumReportView>, String>(
  (ref, tenant) async {
    liveResource(ref, AdminResourceKind.forum);
    final api = await ref.watch(adminApiProvider.future);
    return api.listForumReports(tenant);
  },
);

/// Mirrors ForumService.validateBody.
const forumMaxBodyChars = 2000;
const forumMaxLinks = 3;

String? validateForumBody(String body) {
  final clean = body.trim();
  if (clean.isEmpty) return 'Write the post first.';
  if (clean.length > forumMaxBodyChars) {
    return 'Posts are limited to $forumMaxBodyChars characters — this one has ${clean.length}.';
  }
  final links = RegExp('https?://', caseSensitive: false).allMatches(clean).length;
  if (links > forumMaxLinks) return 'Posts can contain at most $forumMaxLinks links — this one has $links.';
  return null;
}

String _authorName(AdminForumPostView p) => p.authorDisplay.isEmpty ? 'Anonymous' : p.authorDisplay;

String _authorKind(String kind) => switch (kind) {
      'OPERATOR' => 'Brand post',
      'SYSTEM' => 'System',
      _ => 'Member',
    };

/// Forum moderation: pick a chat, then work its post stream (pin, hide,
/// delete), post as the brand, clear the reports queue, and turn the forum
/// on or off. Hiding or unhiding a post resolves its open reports
/// server-side, so both lists refetch after every moderation call.
class ForumScreen extends ConsumerStatefulWidget {
  const ForumScreen({super.key});

  @override
  ConsumerState<ForumScreen> createState() => _ForumScreenState();
}

class _ForumScreenState extends ConsumerState<ForumScreen> {
  String? _entryPointId;

  /// Optimistic moderation state, dropped once the refetch lands (or the call fails).
  final Map<String, ({bool pinned, bool hidden})> _overrides = {};
  final Set<String> _deleted = {};
  final Set<String> _busy = {};

  AdminForumPostView _apply(AdminForumPostView p) {
    final o = _overrides[p.id];
    if (o == null) return p;
    return AdminForumPostView(
      id: p.id,
      authorKind: p.authorKind,
      authorDisplay: p.authorDisplay,
      body: p.body,
      replyTo: p.replyTo,
      pinned: o.pinned,
      hidden: o.hidden,
      replyCount: p.replyCount,
      reportCount: o.hidden != p.hidden ? 0 : p.reportCount,
      createdAt: p.createdAt,
    );
  }

  Future<void> _moderate(
    ForumKey key,
    String postId, {
    required bool currentPinned,
    required bool currentHidden,
    bool? pinned,
    bool? hidden,
    bool delete = false,
    required String done,
    VoidCallback? undo,
  }) async {
    setState(() {
      _busy.add(postId);
      if (delete) {
        _deleted.add(postId);
      } else {
        _overrides[postId] = (pinned: pinned ?? currentPinned, hidden: hidden ?? currentHidden);
      }
    });
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.moderateForumPost(key.tenant, postId, ForumModerationRequest(hidden: hidden, pinned: pinned, delete: delete ? true : null));
      ref.invalidate(forumReportsProvider(key.tenant));
      ref.invalidate(forumPostsProvider(key));
      if (mounted) showToast(context, done, actionLabel: undo == null ? null : 'Undo', onAction: undo);
      try {
        await ref.read(forumPostsProvider(key).future);
      } catch (_) {}
    } catch (e) {
      if (mounted) showToast(context, 'Couldn’t update the post: ${ApiErrorInfo.from(e).message}', error: true);
    } finally {
      if (mounted) {
        setState(() {
          _busy.remove(postId);
          _overrides.remove(postId);
          _deleted.remove(postId);
        });
      }
    }
  }

  void _togglePin(ForumKey key, AdminForumPostView p) {
    final next = !p.pinned;
    _moderate(key, p.id,
        currentPinned: p.pinned,
        currentHidden: p.hidden,
        pinned: next,
        done: next ? 'Pinned to the top of the forum' : 'Unpinned',
        undo: () => _moderate(key, p.id, currentPinned: next, currentHidden: p.hidden, pinned: !next, done: next ? 'Unpinned' : 'Pinned again'));
  }

  void _toggleHidden(ForumKey key, String postId, {required bool pinned, required bool hidden}) {
    final next = !hidden;
    _moderate(key, postId,
        currentPinned: pinned,
        currentHidden: hidden,
        hidden: next,
        done: next ? 'Post hidden from members · its reports are resolved' : 'Post visible to members again',
        undo: () => _moderate(key, postId, currentPinned: pinned, currentHidden: next, hidden: !next, done: next ? 'Post visible again' : 'Post hidden again'));
  }

  Future<void> _delete(ForumKey key, AdminForumPostView p) async {
    final replies = p.replyCount > 0 ? ', along with its ${p.replyCount} ${p.replyCount == 1 ? 'reply' : 'replies'}' : '';
    final ok = await confirmAction(
      context,
      title: 'Delete this post by ${_authorName(p)}?',
      message: 'It disappears from the forum for everyone$replies. Deleted posts can’t be restored from the console — '
          'hide it instead if you might want it back.',
      confirmLabel: 'Delete post',
      destructive: true,
    );
    if (!ok || !mounted) return;
    await _moderate(key, p.id, currentPinned: p.pinned, currentHidden: p.hidden, delete: true, done: 'Post deleted');
  }

  Future<void> _compose(String tenant, ChatEntryPointResponse ep) async {
    await showYSheet<void>(context, label: 'Post as brand', builder: (_) => BrandPostSheet(tenant: tenant, entryPoint: ep));
  }

  Future<void> _openPost(ForumKey key, AdminForumPostView p) async {
    await showYSheet<void>(
      context,
      label: 'Forum post',
      width: 560,
      builder: (_) => _PostDetailSheet(
        post: p,
        onPin: () => _togglePin(key, p),
        onHide: () => _toggleHidden(key, p.id, pinned: p.pinned, hidden: p.hidden),
        onDelete: () => _delete(key, p),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) return const TenantScopedEmptyState(resourceLabel: 'Forums');
    final entryPoints = ref.watch(chatEntryPointsProvider(tenant));
    final eligible = (entryPoints.valueOrNull ?? const <ChatEntryPointResponse>[]).where((e) => !e.isDeactivated).toList();
    if (_entryPointId == null || !eligible.any((e) => e.id == _entryPointId)) {
      _entryPointId = eligible.firstOrNull?.id;
    }
    final ep = eligible.where((e) => e.id == _entryPointId).firstOrNull;

    return YPage(
      title: 'Forum',
      subtitle: 'The community feed inside a chat. Moderate posts, post as the brand, and work the reports queue — '
          'hiding a post resolves its reports.',
      actions: [
        if (ep != null)
          FilledButton.icon(
            onPressed: () => _compose(tenant, ep),
            icon: const Icon(Icons.edit_outlined, size: 18),
            label: const Text('Post as brand'),
          ),
      ],
      toolbar: eligible.isEmpty
          ? null
          : ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 480),
              child: YResourcePicker(
                label: 'Chat',
                wireKey: 'entryPointId',
                helper: 'Each chat entry point has its own forum.',
                value: _entryPointId,
                resources: [
                  for (final e in eligible) YResource(id: e.id, name: e.displayName, meta: e.status.toLowerCase()),
                ],
                onChanged: (v) => setState(() => _entryPointId = v ?? _entryPointId),
              ),
            ),
      child: LiveAsyncView<List<ChatEntryPointResponse>>(
        value: entryPoints,
        what: 'chats',
        onRetry: () => ref.invalidate(chatEntryPointsProvider(tenant)),
        data: (context, _) {
          if (ep == null) {
            return YEmptyState(
              icon: Icons.forum_outlined,
              title: 'No chat to attach a forum to',
              message: 'A forum lives inside a chat entry point. Create a chat first, then come back to turn its forum on.',
              action: FilledButton.icon(
                onPressed: () => context.go('/admin/chats?new=1'),
                icon: const Icon(Icons.add, size: 18),
                label: const Text('Create a chat'),
              ),
            );
          }
          final key = (tenant: tenant, entryPoint: ep.id);
          return _ForumTabs(
            key: ValueKey(ep.id),
            forumKey: key,
            entryPoint: ep,
            apply: _apply,
            deleted: _deleted,
            busy: _busy,
            onOpen: (p) => _openPost(key, p),
            onPin: (p) => _togglePin(key, p),
            onHide: (id, pinned, hidden) => _toggleHidden(key, id, pinned: pinned, hidden: hidden),
            onDelete: (p) => _delete(key, p),
            onCompose: () => _compose(tenant, ep),
          );
        },
      ),
    );
  }
}

class _ForumTabs extends ConsumerWidget {
  const _ForumTabs({
    super.key,
    required this.forumKey,
    required this.entryPoint,
    required this.apply,
    required this.deleted,
    required this.busy,
    required this.onOpen,
    required this.onPin,
    required this.onHide,
    required this.onDelete,
    required this.onCompose,
  });

  final ForumKey forumKey;
  final ChatEntryPointResponse entryPoint;
  final AdminForumPostView Function(AdminForumPostView) apply;
  final Set<String> deleted;
  final Set<String> busy;
  final ValueChanged<AdminForumPostView> onOpen;
  final ValueChanged<AdminForumPostView> onPin;
  final void Function(String postId, bool pinned, bool hidden) onHide;
  final ValueChanged<AdminForumPostView> onDelete;
  final VoidCallback onCompose;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final postsAsync = ref.watch(forumPostsProvider(forumKey));
    final reportsAsync = ref.watch(forumReportsProvider(forumKey.tenant));
    final settings = ref.watch(forumSettingsProvider(forumKey));
    final posts = postsAsync.whenData((l) => [for (final p in l) if (!deleted.contains(p.id)) apply(p)]);
    final postCount = posts.valueOrNull?.length;
    final reportCount = reportsAsync.valueOrNull?.length;

    return DefaultTabController(
      length: 3,
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        if (settings.valueOrNull?.enabled == false)
          Padding(
            padding: const EdgeInsets.only(bottom: Gap.md),
            child: YBanner(
              tone: YBannerTone.warning,
              message: 'The forum is off for ${entryPoint.displayName}: members can’t see it. Turn it on under Settings.',
            ),
          ),
        TabBar(
          isScrollable: true,
          tabAlignment: TabAlignment.start,
          tabs: [
            Tab(text: postCount == null ? 'Posts' : 'Posts ($postCount)'),
            Tab(text: reportCount == null ? 'Reports' : 'Open reports ($reportCount)'),
            const Tab(text: 'Settings'),
          ],
        ),
        const SizedBox(height: Gap.lg),
        Expanded(
          child: TabBarView(children: [
            LiveAsyncView<List<AdminForumPostView>>(
              value: posts,
              what: 'posts',
              onRetry: () => ref.invalidate(forumPostsProvider(forumKey)),
              data: (context, list) => _postsTable(context, list),
            ),
            LiveAsyncView<List<ForumReportView>>(
              value: reportsAsync,
              what: 'reports',
              onRetry: () => ref.invalidate(forumReportsProvider(forumKey.tenant)),
              data: (context, list) => _reportsTable(context, list, posts.valueOrNull ?? const []),
            ),
            LiveAsyncView<ForumSettingsResponse>(
              value: settings,
              what: 'forum settings',
              onRetry: () => ref.invalidate(forumSettingsProvider(forumKey)),
              data: (context, s) => SingleChildScrollView(
                padding: const EdgeInsets.only(bottom: Gap.xxl),
                child: Align(
                  alignment: Alignment.topLeft,
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 720),
                    child: ForumSettingsForm(forumKey: forumKey, entryPoint: entryPoint, settings: s),
                  ),
                ),
              ),
            ),
          ]),
        ),
      ]),
    );
  }

  Widget _postsTable(BuildContext context, List<AdminForumPostView> list) {
    final scheme = Theme.of(context).colorScheme;
    return YTable<AdminForumPostView>(
      rows: list,
      noun: 'posts',
      idOf: (p) => p.id,
      searchHint: 'Search posts and authors',
      searchTextOf: (p) => '${p.authorDisplay} ${p.body} ${_authorKind(p.authorKind)}',
      onRowTap: onOpen,
      filters: [
        YFilter('Reported', (p) => p.reportCount > 0),
        YFilter('Hidden', (p) => p.hidden),
        YFilter('Pinned', (p) => p.pinned),
        YFilter('Brand posts', (p) => p.authorKind == 'OPERATOR'),
        YFilter('Replies', (p) => p.replyTo != null),
      ],
      empty: YEmptyState(
        icon: Icons.forum_outlined,
        title: 'No posts in ${entryPoint.displayName}’s forum yet',
        message: 'Members’ posts show up here for moderation. Start the conversation with a post from the brand.',
        action: FilledButton.icon(onPressed: onCompose, icon: const Icon(Icons.edit_outlined, size: 18), label: const Text('Post as brand')),
      ),
      columns: [
        YColumn(
          label: 'Author',
          flex: 2,
          sortKey: (p) => _authorName(p).toLowerCase(),
          cell: (context, p) => YCellStack(primary: _authorName(p), secondary: _authorKind(p.authorKind)),
        ),
        YColumn(
          label: 'Post',
          flex: 5,
          cell: (context, p) => Text(
            p.replyTo == null ? p.body : 'Reply · ${p.body}',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(fontSize: 14, height: 1.4, color: p.hidden ? scheme.onSurfaceVariant : scheme.onSurface),
          ),
        ),
        YColumn(
          label: 'Status',
          flex: 2,
          sortKey: (p) => p.reportCount * 4 + (p.hidden ? 2 : 0) + (p.pinned ? 1 : 0),
          cell: (context, p) => Wrap(spacing: 4, runSpacing: 4, children: [
            if (p.pinned) const YBadge('Pinned', tone: YTone.brand, icon: Icons.push_pin_outlined),
            if (p.hidden) const YBadge('Hidden', icon: Icons.visibility_off_outlined),
            if (p.reportCount > 0)
              YBadge('${p.reportCount} ${p.reportCount == 1 ? 'report' : 'reports'}', tone: YTone.danger, icon: Icons.flag_outlined),
            if (!p.pinned && !p.hidden && p.reportCount == 0) const YBadge('Visible', tone: YTone.success),
          ]),
        ),
        YColumn(
          label: 'Replies',
          width: 110,
          sortKey: (p) => p.replyCount,
          cell: (context, p) => Text('${p.replyCount}'),
        ),
        YColumn(
          label: 'Posted',
          width: 110,
          sortKey: (p) => p.createdAt ?? '',
          cell: (context, p) => Tooltip(message: p.createdAt ?? '', child: Text(formatRelativeIso(p.createdAt))),
        ),
        YColumn(
          label: '',
          width: 160,
          alignEnd: true,
          cell: (context, p) => busy.contains(p.id)
              ? const Padding(
                  padding: EdgeInsets.only(right: 12),
                  child: SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2)),
                )
              : Row(mainAxisSize: MainAxisSize.min, children: [
                  IconButton(
                    tooltip: p.pinned ? 'Unpin' : 'Pin to top',
                    icon: Icon(p.pinned ? Icons.push_pin : Icons.push_pin_outlined, size: 18),
                    onPressed: () => onPin(p),
                  ),
                  IconButton(
                    tooltip: p.hidden ? 'Show to members' : 'Hide from members',
                    icon: Icon(p.hidden ? Icons.visibility_outlined : Icons.visibility_off_outlined, size: 18),
                    onPressed: () => onHide(p.id, p.pinned, p.hidden),
                  ),
                  IconButton(
                    tooltip: 'Delete post',
                    icon: const Icon(Icons.delete_outline, size: 18),
                    color: scheme.error,
                    onPressed: () => onDelete(p),
                  ),
                ]),
        ),
      ],
    );
  }

  Widget _reportsTable(BuildContext context, List<ForumReportView> list, List<AdminForumPostView> posts) {
    final scheme = Theme.of(context).colorScheme;
    final byId = {for (final p in posts) p.id: p};
    return YTable<ForumReportView>(
      rows: list,
      noun: 'reports',
      idOf: (r) => r.id,
      searchHint: 'Search reasons and posts',
      searchTextOf: (r) => '${r.reason} ${r.postId} ${byId[r.postId]?.body ?? ''} ${byId[r.postId]?.authorDisplay ?? ''}',
      onRowTap: (r) {
        final p = byId[r.postId];
        if (p != null) onOpen(p);
      },
      filters: [
        YFilter('In this chat', (r) => byId.containsKey(r.postId)),
        YFilter('Other chats', (r) => !byId.containsKey(r.postId)),
      ],
      empty: const YEmptyState(
        icon: Icons.flag_outlined,
        title: 'No open reports',
        message: 'When members report a post, it lands here. Hiding the post resolves its reports.',
      ),
      columns: [
        YColumn(
          label: 'Reason',
          flex: 3,
          sortKey: (r) => r.reason.toLowerCase(),
          cell: (context, r) => Text(r.reason.isEmpty ? 'No reason given' : r.reason,
              maxLines: 2, overflow: TextOverflow.ellipsis, style: TextStyle(color: r.reason.isEmpty ? scheme.onSurfaceVariant : null)),
        ),
        YColumn(
          label: 'Reported post',
          flex: 5,
          cell: (context, r) {
            final p = byId[r.postId];
            if (p == null) {
              return YCellStack(primary: 'A post in another chat’s forum', secondary: 'Post ${r.postId}');
            }
            return Text('${_authorName(p)}: ${p.body}', maxLines: 2, overflow: TextOverflow.ellipsis);
          },
        ),
        YColumn(
          label: 'Post status',
          width: 120,
          cell: (context, r) {
            final p = byId[r.postId];
            if (p == null) return const YBadge('Unknown');
            return p.hidden ? const YBadge('Hidden', icon: Icons.visibility_off_outlined) : const YBadge('Visible', tone: YTone.warning);
          },
        ),
        YColumn(
          label: 'Reported',
          width: 130,
          sortKey: (r) => r.createdAt ?? '',
          cell: (context, r) => Tooltip(message: r.createdAt ?? '', child: Text(formatRelativeIso(r.createdAt))),
        ),
        YColumn(
          label: '',
          width: 150,
          alignEnd: true,
          cell: (context, r) {
            final p = byId[r.postId];
            final hidden = p?.hidden ?? false;
            if (busy.contains(r.postId)) {
              return const Padding(
                padding: EdgeInsets.only(right: 12),
                child: SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2)),
              );
            }
            return TextButton.icon(
              onPressed: () => onHide(r.postId, p?.pinned ?? false, hidden),
              icon: Icon(hidden ? Icons.visibility_outlined : Icons.visibility_off_outlined, size: 18),
              label: Text(hidden ? 'Unhide post' : 'Hide post'),
            );
          },
        ),
      ],
    );
  }
}

/// Read the whole post and act on it.
class _PostDetailSheet extends StatelessWidget {
  const _PostDetailSheet({required this.post, required this.onPin, required this.onHide, required this.onDelete});
  final AdminForumPostView post;
  final VoidCallback onPin;
  final VoidCallback onHide;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    void then(VoidCallback action) {
      Navigator.of(context).pop();
      action();
    }

    return YSheetScaffold(
      title: _authorName(post),
      subtitle: '${_authorKind(post.authorKind)} · ${formatRelativeIso(post.createdAt)}',
      body: ListView(padding: const EdgeInsets.fromLTRB(28, 20, 28, 28), children: [
        Wrap(spacing: 6, runSpacing: 6, children: [
          if (post.pinned) const YBadge('Pinned', tone: YTone.brand, icon: Icons.push_pin_outlined),
          if (post.hidden) const YBadge('Hidden from members', icon: Icons.visibility_off_outlined),
          if (post.reportCount > 0)
            YBadge('${post.reportCount} open ${post.reportCount == 1 ? 'report' : 'reports'}', tone: YTone.danger, icon: Icons.flag_outlined),
          if (post.replyTo != null) const YBadge('Reply', icon: Icons.reply),
          YBadge('${post.replyCount} ${post.replyCount == 1 ? 'reply' : 'replies'}'),
        ]),
        const SizedBox(height: Gap.lg),
        SelectableText(post.body, style: TextStyle(fontSize: 15, height: 1.55, color: scheme.onSurface)),
        const SizedBox(height: Gap.xl),
        Text('Post ID', style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
        SelectableText(post.id, style: monoStyle(context, size: 12.5)),
        if (post.replyTo != null) ...[
          const SizedBox(height: Gap.sm),
          Text('In reply to', style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
          SelectableText(post.replyTo!, style: monoStyle(context, size: 12.5)),
        ],
      ]),
      footer: Container(
        padding: const EdgeInsets.fromLTRB(28, 12, 28, 16),
        decoration: BoxDecoration(border: Border(top: BorderSide(color: scheme.outlineVariant))),
        child: Wrap(alignment: WrapAlignment.end, spacing: 8, runSpacing: 8, children: [
          TextButton.icon(
            onPressed: () => then(onDelete),
            icon: const Icon(Icons.delete_outline, size: 18),
            label: const Text('Delete'),
            style: TextButton.styleFrom(foregroundColor: scheme.error),
          ),
          OutlinedButton.icon(
            onPressed: () => then(onPin),
            icon: Icon(post.pinned ? Icons.push_pin : Icons.push_pin_outlined, size: 18),
            label: Text(post.pinned ? 'Unpin' : 'Pin to top'),
          ),
          FilledButton.icon(
            onPressed: () => then(onHide),
            icon: Icon(post.hidden ? Icons.visibility_outlined : Icons.visibility_off_outlined, size: 18),
            label: Text(post.hidden ? 'Show to members' : 'Hide from members'),
          ),
        ]),
      ),
    );
  }
}

/// The forum switch for one chat.
class ForumSettingsForm extends ConsumerStatefulWidget {
  const ForumSettingsForm({super.key, required this.forumKey, required this.entryPoint, required this.settings});
  final ForumKey forumKey;
  final ChatEntryPointResponse entryPoint;
  final ForumSettingsResponse settings;

  @override
  ConsumerState<ForumSettingsForm> createState() => _ForumSettingsFormState();
}

class _ForumSettingsFormState extends ConsumerState<ForumSettingsForm> {
  late bool _enabled = widget.settings.enabled;
  late final YFormController _form;

  @override
  void initState() {
    super.initState();
    _form = YFormController(
      snapshot: () => {'enabled': _enabled},
      validate: () => const {},
      labels: const {'enabled': 'Forum'},
    );
    _form.addListener(_rebuild);
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void didUpdateWidget(ForumSettingsForm old) {
    super.didUpdateWidget(old);
    // Someone else flipped it: follow along unless this operator has an unsaved change.
    if (old.settings.enabled != widget.settings.enabled && !_form.isDirty) {
      _enabled = widget.settings.enabled;
      _form.markClean();
    }
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    if (!_enabled && widget.settings.enabled) {
      final ok = await confirmAction(
        context,
        title: 'Turn off the forum for ${widget.entryPoint.displayName}?',
        message: 'Members stop seeing the forum and can’t post. Posts and reports are kept and come back when you turn it on again.',
        confirmLabel: 'Turn off forum',
      );
      if (!ok || !mounted) return;
    }
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final saved = await api.updateForumSettings(widget.forumKey.tenant, widget.forumKey.entryPoint, enabled: _enabled);
      _enabled = saved.enabled;
      _form.saving = false;
      _form.markClean();
      ref.invalidate(forumSettingsProvider(widget.forumKey));
      if (mounted) showToast(context, saved.enabled ? 'Forum is on for ${widget.entryPoint.displayName}' : 'Forum is off for ${widget.entryPoint.displayName}');
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final primary = !_form.isDirty ? 'Save settings' : (_enabled ? 'Turn on forum' : 'Turn off forum');
    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          if (_form.summary.isNotEmpty || _form.formError != null) ...[
            YErrorSummary(form: _form, action: 'saving the forum settings'),
            const SizedBox(height: Gap.lg),
          ],
          YFormSection(
            title: 'Community forum',
            description: 'A feed where people using ${widget.entryPoint.displayName} can read, post and reply. '
                'You moderate it from the Posts and Reports tabs.',
            children: [
              YSwitchField(
                title: 'Show the forum in this chat',
                wireKey: 'enabled',
                description: _enabled
                    ? 'Members can read and post. Posts with 5 or more reports are hidden automatically.'
                    : 'The forum is hidden from members. Existing posts are kept.',
                value: _enabled,
                onChanged: (v) {
                  setState(() => _enabled = v);
                  _form.changed('enabled');
                },
              ),
            ],
          ),
          const SizedBox(height: Gap.md),
          YActionBar(
            form: _form,
            primaryLabel: primary,
            onPrimary: _save,
            cancelLabel: 'Discard',
            onCancel: _form.isDirty
                ? () {
                    setState(() => _enabled = widget.settings.enabled);
                    _form.markClean();
                  }
                : null,
          ),
        ]),
      ),
    );
  }
}

/// Publish a post in a chat's forum under the brand's name.
class BrandPostSheet extends ConsumerStatefulWidget {
  const BrandPostSheet({super.key, required this.tenant, required this.entryPoint});
  final String tenant;
  final ChatEntryPointResponse entryPoint;

  @override
  ConsumerState<BrandPostSheet> createState() => _BrandPostSheetState();
}

class _BrandPostSheetState extends ConsumerState<BrandPostSheet> {
  final _body = TextEditingController();
  final _byline = TextEditingController();
  bool _pinned = false;
  late final YFormController _form;

  @override
  void initState() {
    super.initState();
    _form = YFormController(
      snapshot: () => {'body': _body.text, 'displayName': _byline.text.trim(), 'pinned': _pinned},
      validate: () => {'body': validateForumBody(_body.text)},
      labels: const {'body': 'Post', 'displayName': 'Byline', 'pinned': 'Pin'},
      draftKey: 'yaya.draft.forumPost.${widget.tenant}.${widget.entryPoint.id}',
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      _body.text = (d.values['body'] as String?) ?? '';
      _byline.text = (d.values['displayName'] as String?) ?? '';
      _pinned = d.values['pinned'] == true;
      _form.refresh();
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    _body.dispose();
    _byline.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.createForumPost(
        widget.tenant,
        widget.entryPoint.id,
        AdminForumPostRequest(
          body: _body.text.trim(),
          displayName: _byline.text.trim().isEmpty ? null : _byline.text.trim(),
          pinned: _pinned ? true : null,
        ),
      );
      ref.invalidate(forumPostsProvider((tenant: widget.tenant, entryPoint: widget.entryPoint.id)));
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, _pinned ? 'Posted and pinned in ${widget.entryPoint.displayName}' : 'Posted in ${widget.entryPoint.displayName}');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final enabled = ref.watch(forumSettingsProvider((tenant: widget.tenant, entryPoint: widget.entryPoint.id))).valueOrNull?.enabled;
    final length = _body.text.trim().length;
    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: 'Post as brand',
          subtitle: 'Published right away in ${widget.entryPoint.displayName}’s forum, marked as a brand post.',
          body: ListView(padding: const EdgeInsets.fromLTRB(28, 20, 28, 28), children: [
            if (enabled == false) ...[
              const YBanner(
                tone: YBannerTone.warning,
                message: 'The forum is off for this chat — members won’t see this post until you turn the forum on.',
              ),
              const SizedBox(height: Gap.lg),
            ],
            if (_form.summary.isNotEmpty || _form.formError != null) ...[
              YErrorSummary(form: _form, action: 'posting'),
              const SizedBox(height: Gap.lg),
            ],
            YTextField(
              label: 'Post',
              wireKey: 'body',
              controller: _body,
              form: _form,
              name: 'body',
              required: true,
              autofocus: true,
              minLines: 5,
              maxLines: 12,
              hint: 'e.g. We’re open late this Friday — come say hi!',
              helper: 'Up to $forumMaxBodyChars characters and $forumMaxLinks links.',
              onChanged: (_) => setState(() {}),
            ),
            Align(
              alignment: Alignment.centerRight,
              child: Text('$length / $forumMaxBodyChars',
                  style: TextStyle(fontSize: 12, color: length > forumMaxBodyChars ? scheme.error : scheme.onSurfaceVariant)),
            ),
            const SizedBox(height: Gap.fieldGap),
            YTextField(
              label: 'Byline',
              wireKey: 'displayName',
              controller: _byline,
              form: _form,
              name: 'displayName',
              optional: true,
              hint: widget.entryPoint.displayName,
              helper: 'The name shown on the post. Leave empty to use the chat’s name.',
            ),
            const SizedBox(height: Gap.fieldGap),
            YSwitchField(
              title: 'Pin to the top of the forum',
              wireKey: 'pinned',
              description: 'Pinned posts stay above newer ones until you unpin them.',
              value: _pinned,
              onChanged: (v) {
                setState(() => _pinned = v);
                _form.changed('pinned');
              },
            ),
          ]),
          footer: YActionBar(
            form: _form,
            primaryLabel: _pinned ? 'Publish and pin' : 'Publish post',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/chat_api.dart';
import '../../data/providers.dart';
import '../../l10n/app_localizations.dart';
import '../../models/forum.dart';
import '../../models/tile.dart';

/// The community forum tab (chat-experience-design.md §8.3): pick one of your
/// added chats, read its feed, post/react/reply/report under your verified
/// account name. A chat whose tenant hasn't enabled the forum shows a zero
/// state — the tab never errors on it.
class ForumScreen extends ConsumerStatefulWidget {
  const ForumScreen({super.key});

  @override
  ConsumerState<ForumScreen> createState() => _ForumScreenState();
}

class _ForumScreenState extends ConsumerState<ForumScreen> {
  String? _handle;
  bool _loading = false;
  bool _enabled = false;
  List<ForumPost> _posts = [];
  String? _cursor;
  bool _hasMore = false;
  String? _error;
  final _composer = TextEditingController();
  bool _posting = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final tiles = ref.read(tilesProvider);
      if (tiles.isNotEmpty) _select(tiles.first.handle);
    });
  }

  @override
  void dispose() {
    _composer.dispose();
    super.dispose();
  }

  Future<void> _select(String handle) async {
    setState(() {
      _handle = handle;
      _loading = true;
      _error = null;
      _posts = [];
    });
    await _load();
  }

  Future<void> _load({bool more = false}) async {
    final handle = _handle;
    if (handle == null) return;
    try {
      final page = await ref
          .read(chatApiProvider)
          .forumFeed(handle, cursor: more ? _cursor : null);
      if (!mounted || _handle != handle) return;
      setState(() {
        _enabled = page.enabled;
        _posts = more ? [..._posts, ...page.posts] : page.posts;
        _cursor = page.nextCursor;
        _hasMore = page.nextCursor != null;
        _loading = false;
        _error = null;
      });
    } on ChatApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = e.message;
      });
    }
  }

  Future<void> _post() async {
    final handle = _handle;
    final body = _composer.text.trim();
    if (handle == null || body.isEmpty || _posting) return;
    setState(() => _posting = true);
    final messenger = ScaffoldMessenger.of(context);
    try {
      final created = await ref.read(chatApiProvider).forumCreate(handle, body);
      if (!mounted) return;
      _composer.clear();
      setState(() {
        _posting = false;
        _posts = [created, ..._posts];
      });
    } on ChatApiException catch (e) {
      if (!mounted) return;
      setState(() => _posting = false);
      messenger.showSnackBar(SnackBar(content: Text(e.message)));
    }
  }

  void _replacePost(ForumPost updated) {
    setState(() {
      _posts = [for (final p in _posts) p.id == updated.id ? updated : p];
    });
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    final tiles = ref.watch(tilesProvider);

    final verified = ref.watch(verificationProvider.select((s) => s.verified));
    return Scaffold(
      appBar: AppBar(
        title: Brand.gradientText(t.forumTitle, fontSize: 20),
        actions: [
          if (verified && _handle != null && _enabled)
            IconButton(
              icon: const Icon(Icons.block_outlined),
              tooltip: t.forumBlockedMembers,
              onPressed: () => _showBlocked(_handle!),
            ),
        ],
        bottom: tiles.length > 1
            ? PreferredSize(
                preferredSize: const Size.fromHeight(52),
                child: SizedBox(
                  height: 48,
                  child: ListView.separated(
                    scrollDirection: Axis.horizontal,
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    itemCount: tiles.length,
                    separatorBuilder: (_, _) => const SizedBox(width: 8),
                    itemBuilder: (context, i) {
                      final tile = tiles[i];
                      return ChoiceChip(
                        label: Text(tile.displayName),
                        selected: tile.handle == _handle,
                        onSelected: (_) => _select(tile.handle),
                      );
                    },
                  ),
                ),
              )
            : null,
      ),
      body: _body(t, tiles),
    );
  }

  Future<void> _showBlocked(String handle) async {
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (_) => _BlockedMembersSheet(handle: handle),
    );
    // Unblocking may have restored posts — refresh the feed.
    if (mounted) _load();
  }

  Widget _body(AppL10n t, List<Tile> tiles) {
    if (tiles.isEmpty) {
      return _zero(t, Icons.forum_outlined, t.forumNoChats);
    }
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) {
      return _zero(t, Icons.cloud_off_outlined, _error!);
    }
    if (!_enabled) {
      return _zero(t, Icons.forum_outlined, t.forumDisabled);
    }
    return Column(
      children: [
        Expanded(
          child: RefreshIndicator(
            onRefresh: () => _load(),
            child: _posts.isEmpty
                ? ListView(children: [
                    const SizedBox(height: 120),
                    _zero(t, Icons.forum_outlined, t.forumEmpty),
                  ])
                : NotificationListener<ScrollNotification>(
                    onNotification: (n) {
                      if (_hasMore &&
                          n.metrics.pixels > n.metrics.maxScrollExtent - 300) {
                        _load(more: true);
                      }
                      return false;
                    },
                    child: ListView.builder(
                      padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
                      itemCount: _posts.length,
                      itemBuilder: (context, i) => _PostCard(
                        handle: _handle!,
                        post: _posts[i],
                        onChanged: _replacePost,
                        onDeleted: (id) => setState(() {
                          _posts = _posts.where((p) => p.id != id).toList();
                        }),
                      ),
                    ),
                  ),
          ),
        ),
        SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(8, 4, 8, 8),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _composer,
                    enabled: !_posting,
                    minLines: 1,
                    maxLines: 4,
                    maxLength: 2000,
                    textInputAction: TextInputAction.send,
                    onSubmitted: (_) => _post(),
                    decoration: InputDecoration(
                      counterText: '',
                      hintText: t.forumComposerHint,
                      border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(24)),
                      contentPadding: const EdgeInsets.symmetric(
                          horizontal: 16, vertical: 10),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  onPressed: _posting ? null : _post,
                  icon: _posting
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.send),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _zero(AppL10n t, IconData icon, String message) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 56, color: Theme.of(context).colorScheme.outline),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}

/* ------------------------------- post card -------------------------------- */

class _PostCard extends ConsumerWidget {
  const _PostCard({
    required this.handle,
    required this.post,
    required this.onChanged,
    required this.onDeleted,
    this.inThread = false,
  });

  final String handle;
  final ForumPost post;
  final ValueChanged<ForumPost> onChanged;
  final ValueChanged<String> onDeleted;
  final bool inThread;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppL10n.of(context);
    final scheme = Theme.of(context).colorScheme;
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 10, 8, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                if (post.pinned) ...[
                  Icon(Icons.push_pin, size: 14, color: scheme.primary),
                  const SizedBox(width: 4),
                ],
                if (post.isSystem) ...[
                  Icon(Icons.verified, size: 14, color: scheme.primary),
                  const SizedBox(width: 4),
                ],
                Expanded(
                  child: Text(
                    post.authorDisplay,
                    style: Theme.of(context).textTheme.labelLarge?.copyWith(
                        color: post.isSystem ? scheme.primary : null,
                        fontWeight: FontWeight.w600),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                Text(_ago(t, post.createdAt),
                    style: Theme.of(context).textTheme.bodySmall),
                _menu(context, ref, t),
              ],
            ),
            const SizedBox(height: 4),
            Text(post.body),
            if (post.edited)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Text(t.forumEdited,
                    style: Theme.of(context)
                        .textTheme
                        .bodySmall
                        ?.copyWith(fontStyle: FontStyle.italic)),
              ),
            const SizedBox(height: 6),
            Row(
              children: [
                Expanded(child: _reactionsBar(context, ref)),
                if (!inThread)
                  TextButton.icon(
                    onPressed: () => _openThread(context, ref),
                    icon: const Icon(Icons.chat_bubble_outline, size: 16),
                    label: Text(post.replyCount == 0
                        ? t.forumReply
                        : '${post.replyCount}'),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _reactionsBar(BuildContext context, WidgetRef ref) {
    return Wrap(
      spacing: 4,
      children: [
        for (final emoji in forumReactions)
          if ((post.reactions[emoji] ?? 0) > 0) _reactionChip(context, ref, emoji),
        _addReaction(context, ref),
      ],
    );
  }

  Widget _reactionChip(BuildContext context, WidgetRef ref, String emoji) {
    final count = post.reactions[emoji] ?? 0;
    return ActionChip(
      visualDensity: VisualDensity.compact,
      label: Text('$emoji $count'),
      onPressed: () => _toggle(context, ref, emoji, add: true),
    );
  }

  Widget _addReaction(BuildContext context, WidgetRef ref) {
    return IconButton(
      visualDensity: VisualDensity.compact,
      icon: const Icon(Icons.add_reaction_outlined, size: 18),
      onPressed: () async {
        final emoji = await showModalBottomSheet<String>(
          context: context,
          builder: (ctx) => SafeArea(
            child: Wrap(
              alignment: WrapAlignment.center,
              children: [
                for (final e in forumReactions)
                  TextButton(
                    onPressed: () => Navigator.pop(ctx, e),
                    child: Text(e, style: const TextStyle(fontSize: 28)),
                  ),
              ],
            ),
          ),
        );
        if (emoji != null && context.mounted) {
          await _toggle(context, ref, emoji, add: true);
        }
      },
    );
  }

  Future<void> _toggle(BuildContext context, WidgetRef ref, String emoji,
      {required bool add}) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      final updated =
          await ref.read(chatApiProvider).forumReact(handle, post.id, emoji, add: add);
      onChanged(updated);
    } on ChatApiException catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e.message)));
    }
  }

  Widget _menu(BuildContext context, WidgetRef ref, AppL10n t) {
    return PopupMenuButton<String>(
      iconSize: 18,
      onSelected: (choice) async {
        final api = ref.read(chatApiProvider);
        final messenger = ScaffoldMessenger.of(context);
        try {
          switch (choice) {
            case 'edit':
              final body = await _prompt(context, t.forumEditTitle, post.body);
              if (body != null && body.trim().isNotEmpty) {
                onChanged(await api.forumEdit(handle, post.id, body.trim()));
              }
            case 'delete':
              await api.forumDelete(handle, post.id);
              onDeleted(post.id);
            case 'report':
              final reason = await _prompt(context, t.forumReportTitle, '');
              if (reason != null) {
                await api.forumReport(handle, post.id, reason.trim());
                messenger
                    .showSnackBar(SnackBar(content: Text(t.forumReported)));
              }
            case 'block':
              final ok = await showDialog<bool>(
                context: context,
                builder: (ctx) => AlertDialog(
                  title: Text(t.forumBlockTitle),
                  content: Text(t.forumBlockBody),
                  actions: [
                    TextButton(
                        onPressed: () => Navigator.pop(ctx, false),
                        child: Text(t.cancel)),
                    FilledButton(
                        onPressed: () => Navigator.pop(ctx, true),
                        child: Text(t.forumBlock)),
                  ],
                ),
              );
              if (ok == true) {
                await api.forumBlockAuthor(handle, post.id);
                onDeleted(post.id);
                messenger.showSnackBar(SnackBar(content: Text(t.forumBlocked)));
              }
          }
        } on ChatApiException catch (e) {
          messenger.showSnackBar(SnackBar(content: Text(e.message)));
        }
      },
      itemBuilder: (ctx) => [
        if (post.mine) PopupMenuItem(value: 'edit', child: Text(t.forumEdit)),
        if (post.mine) PopupMenuItem(value: 'delete', child: Text(t.forumDelete)),
        if (!post.mine) PopupMenuItem(value: 'report', child: Text(t.forumReport)),
        if (!post.mine && !post.isSystem)
          PopupMenuItem(value: 'block', child: Text(t.forumBlock)),
      ],
    );
  }

  Future<String?> _prompt(BuildContext context, String title, String initial) {
    final controller = TextEditingController(text: initial);
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: controller,
          autofocus: true,
          maxLines: 4,
          minLines: 1,
          maxLength: 2000,
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: Text(AppL10n.of(ctx).cancel)),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, controller.text),
              child: Text(AppL10n.of(ctx).ok)),
        ],
      ),
    );
  }

  void _openThread(BuildContext context, WidgetRef ref) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _ThreadSheet(handle: handle, parent: post, onChanged: onChanged),
    );
  }
}

/* ------------------------------ thread sheet ------------------------------ */

class _ThreadSheet extends ConsumerStatefulWidget {
  const _ThreadSheet(
      {required this.handle, required this.parent, required this.onChanged});

  final String handle;
  final ForumPost parent;
  final ValueChanged<ForumPost> onChanged;

  @override
  ConsumerState<_ThreadSheet> createState() => _ThreadSheetState();
}

class _ThreadSheetState extends ConsumerState<_ThreadSheet> {
  List<ForumPost>? _replies;
  final _reply = TextEditingController();
  bool _posting = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _reply.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final replies =
          await ref.read(chatApiProvider).forumReplies(widget.handle, widget.parent.id);
      if (mounted) setState(() => _replies = replies);
    } on ChatApiException catch (_) {
      if (mounted) setState(() => _replies = []);
    }
  }

  Future<void> _send() async {
    final body = _reply.text.trim();
    if (body.isEmpty || _posting) return;
    setState(() => _posting = true);
    final messenger = ScaffoldMessenger.of(context);
    try {
      final created = await ref
          .read(chatApiProvider)
          .forumCreate(widget.handle, body, replyTo: widget.parent.id);
      if (!mounted) return;
      _reply.clear();
      setState(() {
        _posting = false;
        _replies = [...?_replies, created];
      });
    } on ChatApiException catch (e) {
      if (!mounted) return;
      setState(() => _posting = false);
      messenger.showSnackBar(SnackBar(content: Text(e.message)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.75,
      maxChildSize: 0.95,
      builder: (context, controller) => Padding(
        padding:
            EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
        child: Column(
          children: [
            Expanded(
              child: ListView(
                controller: controller,
                padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
                children: [
                  _PostCard(
                    handle: widget.handle,
                    post: widget.parent,
                    onChanged: widget.onChanged,
                    onDeleted: (_) => Navigator.pop(context),
                    inThread: true,
                  ),
                  const Divider(),
                  if (_replies == null)
                    const Center(
                        child: Padding(
                            padding: EdgeInsets.all(16),
                            child: CircularProgressIndicator()))
                  else if (_replies!.isEmpty)
                    Padding(
                      padding: const EdgeInsets.all(16),
                      child: Text(t.forumNoReplies, textAlign: TextAlign.center),
                    )
                  else
                    for (final reply in _replies!)
                      Padding(
                        padding: const EdgeInsets.only(left: 24),
                        child: _PostCard(
                          handle: widget.handle,
                          post: reply,
                          onChanged: (updated) => setState(() {
                            _replies = [
                              for (final r in _replies!)
                                r.id == updated.id ? updated : r
                            ];
                          }),
                          onDeleted: (id) => setState(() {
                            _replies =
                                _replies!.where((r) => r.id != id).toList();
                          }),
                          inThread: true,
                        ),
                      ),
                ],
              ),
            ),
            SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(8, 4, 8, 8),
                child: Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _reply,
                        enabled: !_posting,
                        minLines: 1,
                        maxLines: 3,
                        maxLength: 2000,
                        onSubmitted: (_) => _send(),
                        decoration: InputDecoration(
                          counterText: '',
                          hintText: t.forumReplyHint,
                          border: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(24)),
                          contentPadding: const EdgeInsets.symmetric(
                              horizontal: 16, vertical: 10),
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    IconButton.filled(
                      onPressed: _posting ? null : _send,
                      icon: const Icon(Icons.send),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

String _ago(AppL10n t, String? iso) {
  final dt = iso == null ? null : DateTime.tryParse(iso);
  if (dt == null) return '';
  final diff = DateTime.now().difference(dt.toLocal());
  if (diff.inMinutes < 1) return t.forumJustNow;
  if (diff.inHours < 1) return '${diff.inMinutes}m';
  if (diff.inDays < 1) return '${diff.inHours}h';
  return '${diff.inDays}d';
}

/// Manage-blocks sheet (Apple 1.2): lists the members you've blocked in this
/// forum and lets you unblock them. Loads on open; unblock is optimistic.
class _BlockedMembersSheet extends ConsumerStatefulWidget {
  const _BlockedMembersSheet({required this.handle});

  final String handle;

  @override
  ConsumerState<_BlockedMembersSheet> createState() => _BlockedMembersSheetState();
}

class _BlockedMembersSheetState extends ConsumerState<_BlockedMembersSheet> {
  bool _loading = true;
  String? _error;
  List<({String blockId, String display})> _blocked = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final rows = await ref.read(chatApiProvider).forumBlocks(widget.handle);
      if (!mounted) return;
      setState(() {
        _blocked = rows;
        _loading = false;
      });
    } on ChatApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.message;
        _loading = false;
      });
    }
  }

  Future<void> _unblock(String blockId) async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _blocked = _blocked.where((b) => b.blockId != blockId).toList());
    try {
      await ref.read(chatApiProvider).forumUnblock(widget.handle, blockId);
    } on ChatApiException catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e.message)));
      _load(); // reconcile on failure
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(t.forumBlockedMembers,
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            if (_loading)
              const Padding(
                padding: EdgeInsets.all(24),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (_error != null)
              Padding(padding: const EdgeInsets.all(16), child: Text(_error!))
            else if (_blocked.isEmpty)
              Padding(
                padding: const EdgeInsets.all(16),
                child: Text(t.forumNoBlocked),
              )
            else
              ..._blocked.map((b) => ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.person_off_outlined),
                    title: Text(b.display),
                    trailing: TextButton(
                      onPressed: () => _unblock(b.blockId),
                      child: Text(t.forumUnblock),
                    ),
                  )),
          ],
        ),
      ),
    );
  }
}

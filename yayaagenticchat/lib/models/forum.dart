/// Community forum models (chat-experience-design.md §8).
class ForumPost {
  final String id;
  final String authorKind; // USER | SYSTEM | OPERATOR
  final String authorDisplay;
  final String body;
  final String? replyTo;
  final bool pinned;
  final int replyCount;
  final Map<String, int> reactions;
  final bool edited;
  final String? createdAt;
  final bool mine;

  const ForumPost({
    required this.id,
    required this.authorKind,
    required this.authorDisplay,
    required this.body,
    this.replyTo,
    this.pinned = false,
    this.replyCount = 0,
    this.reactions = const {},
    this.edited = false,
    this.createdAt,
    this.mine = false,
  });

  bool get isSystem => authorKind == 'SYSTEM' || authorKind == 'OPERATOR';

  factory ForumPost.fromJson(Map<String, dynamic> j) => ForumPost(
        id: (j['id'] ?? '') as String,
        authorKind: (j['authorKind'] ?? 'USER') as String,
        authorDisplay: (j['authorDisplay'] ?? '') as String,
        body: (j['body'] ?? '') as String,
        replyTo: j['replyTo'] as String?,
        pinned: (j['pinned'] ?? false) as bool,
        replyCount: (j['replyCount'] as num?)?.toInt() ?? 0,
        reactions: {
          for (final e in (j['reactions'] as Map? ?? const {}).entries)
            e.key.toString(): (e.value as num).toInt(),
        },
        edited: (j['edited'] ?? false) as bool,
        createdAt: j['createdAt'] as String?,
        mine: (j['mine'] ?? false) as bool,
      );
}

/// The fixed reaction set (§8.1) — must match the server's allowlist.
const forumReactions = ['👍', '❤️', '🎉', '😂', '😮', '🙏'];

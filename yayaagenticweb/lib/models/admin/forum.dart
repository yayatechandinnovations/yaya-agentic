/// Wire models for the entry-point Forum admin surface
/// (`/v1/admin/tenants/{tenantId}/forum/*`). Hand-rolled to match the
/// existing admin-model pattern (see `chat_entry_point.dart`).
library;

class ForumSettingsResponse {
  ForumSettingsResponse({required this.enabled});

  final bool enabled;

  factory ForumSettingsResponse.fromJson(Map<String, dynamic> json) =>
      ForumSettingsResponse(enabled: json['enabled'] == true);
}

class AdminForumPostView {
  AdminForumPostView({
    required this.id,
    required this.authorKind,
    required this.authorDisplay,
    required this.body,
    this.replyTo,
    required this.pinned,
    required this.hidden,
    required this.replyCount,
    required this.reportCount,
    this.createdAt,
  });

  final String id;
  final String authorKind; // USER | SYSTEM | OPERATOR
  final String authorDisplay;
  final String body;
  final String? replyTo;
  final bool pinned;
  final bool hidden;
  final int replyCount;
  final int reportCount;
  final String? createdAt;

  factory AdminForumPostView.fromJson(Map<String, dynamic> json) =>
      AdminForumPostView(
        id: (json['id'] ?? '').toString(),
        authorKind: (json['authorKind'] ?? 'USER') as String,
        authorDisplay: (json['authorDisplay'] ?? '') as String,
        body: (json['body'] ?? '') as String,
        replyTo: json['replyTo']?.toString(),
        pinned: json['pinned'] == true,
        hidden: json['hidden'] == true,
        replyCount: (json['replyCount'] as num?)?.toInt() ?? 0,
        reportCount: (json['reportCount'] as num?)?.toInt() ?? 0,
        createdAt: json['createdAt']?.toString(),
      );
}

class AdminForumPostRequest {
  AdminForumPostRequest({
    required this.body,
    this.displayName,
    this.pinned,
  });

  final String body;
  final String? displayName;
  final bool? pinned;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'body': body,
        if (displayName != null && displayName!.isNotEmpty)
          'displayName': displayName,
        if (pinned != null) 'pinned': pinned,
      };
}

/// PATCH body for the moderation endpoint. Only the fields the operator is
/// changing are sent; hide/unhide resolves the post's open reports
/// server-side and delete soft-deletes.
class ForumModerationRequest {
  ForumModerationRequest({this.hidden, this.pinned, this.delete});

  final bool? hidden;
  final bool? pinned;
  final bool? delete;

  Map<String, dynamic> toJson() => <String, dynamic>{
        if (hidden != null) 'hidden': hidden,
        if (pinned != null) 'pinned': pinned,
        if (delete != null) 'delete': delete,
      };
}

class ForumReportView {
  ForumReportView({
    required this.id,
    required this.postId,
    required this.reason,
    this.createdAt,
  });

  final String id;
  final String postId;
  final String reason;
  final String? createdAt;

  factory ForumReportView.fromJson(Map<String, dynamic> json) =>
      ForumReportView(
        id: (json['id'] ?? '').toString(),
        postId: (json['postId'] ?? '').toString(),
        reason: (json['reason'] ?? '') as String,
        createdAt: json['createdAt']?.toString(),
      );
}

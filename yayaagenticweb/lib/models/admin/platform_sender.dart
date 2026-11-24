/// Wire models for shared platform senders (whatsapp-shared-sender-design.md
/// §17): `/v1/admin/platform-senders/*` (platform admins) and the
/// workspace-side member / keyword routes on an assigned platform sender.
/// Hand-rolled like the sibling `whatsapp_sender.dart`.
library;

import 'whatsapp_sender.dart';

List<String> _strings(Object? v) => v is List ? v.map((e) => e.toString()).toList() : const [];
int _count(Object? v) => v is num ? v.toInt() : 0;
Map<String, dynamic> _map(Object? v) => v is Map ? Map<String, dynamic>.from(v) : const <String, dynamic>{};

/// `PlatformSenderResponse` = `SenderResponse` plus [unroutedReply] and
/// [assignmentCount] (§17.3).
class PlatformSenderResponse {
  const PlatformSenderResponse({
    required this.sender,
    this.unroutedReply,
    this.assignmentCount = 0,
  });

  final WhatsAppSenderResponse sender;

  /// What a phone that no workspace can claim is told (§8.3).
  final String? unroutedReply;

  /// Live (ACTIVE or PAUSED) assignments.
  final int assignmentCount;

  String get id => sender.id;

  /// The approved name when WhatsApp has one, otherwise the requested one.
  String get title => sender.approvedDisplayName?.isNotEmpty == true ? sender.approvedDisplayName! : sender.displayName;

  factory PlatformSenderResponse.fromJson(Map<String, dynamic> json) => PlatformSenderResponse(
        sender: WhatsAppSenderResponse.fromJson({'owner': 'PLATFORM', ...json}),
        unroutedReply: json['unroutedReply'] as String?,
        assignmentCount: _count(json['assignmentCount']),
      );
}

/// `POST /v1/admin/platform-senders` and `PUT /{s}` (the PUT drops the phone).
class PlatformSenderRequest {
  const PlatformSenderRequest({
    required this.phoneE164,
    required this.displayName,
    required this.businessProfile,
    this.defaultLocale,
    this.unroutedReply,
  });

  final String phoneE164;
  final String displayName;
  final BusinessProfileFields businessProfile;
  final String? defaultLocale;
  final String? unroutedReply;

  Map<String, dynamic> toJson({bool includePhone = true}) => {
        if (includePhone) 'phoneE164': phoneE164,
        'displayName': displayName,
        'businessProfile': businessProfile.toJson(),
        if (defaultLocale != null && defaultLocale!.isNotEmpty) 'defaultLocale': defaultLocale,
        // Always sent: empty clears it.
        'unroutedReply': unroutedReply ?? '',
      };

  static PlatformSenderRequest of(WhatsAppSenderResponse s, {String? unroutedReply}) => PlatformSenderRequest(
        phoneE164: s.phoneE164,
        displayName: s.displayName,
        businessProfile: s.businessProfile,
        defaultLocale: s.defaultLocale,
        unroutedReply: unroutedReply,
      );
}

class AssignmentStats {
  const AssignmentStats({this.templateSends24h = 0, this.optOuts7d = 0, this.members = 0});

  /// Business-initiated sends in the last rolling 24 h (what the cap counts).
  final int templateSends24h;

  /// STOPs attributed to this workspace in the last 7 days.
  final int optOuts7d;
  final int members;

  factory AssignmentStats.fromJson(Map<String, dynamic> json) => AssignmentStats(
        templateSends24h: _count(json['templateSends24h']),
        optOuts7d: _count(json['optOuts7d']),
        members: _count(json['members']),
      );
}

/// One `(platform sender, workspace)` assignment, as platform admins see it.
class PlatformSenderAssignment {
  const PlatformSenderAssignment({
    required this.id,
    required this.senderId,
    required this.tenantId,
    required this.label,
    required this.status,
    this.dailyOutboundCap = 1000,
    this.nonMemberReply,
    this.assignedBy,
    this.assignedAt,
    this.endedAt,
    this.stats = const AssignmentStats(),
  });

  final String id;
  final String senderId;
  final String tenantId;
  final String label;

  /// `ACTIVE`, `PAUSED` or `ENDED`.
  final String status;
  final int dailyOutboundCap;
  final String? nonMemberReply;
  final String? assignedBy;
  final String? assignedAt;
  final String? endedAt;
  final AssignmentStats stats;

  bool get isLive => status == 'ACTIVE' || status == 'PAUSED';

  /// At or over the cap: further template sends fall back.
  bool get atCap => stats.templateSends24h >= dailyOutboundCap;

  factory PlatformSenderAssignment.fromJson(Map<String, dynamic> json) => PlatformSenderAssignment(
        id: (json['id'] ?? '') as String,
        senderId: (json['senderId'] ?? '') as String,
        tenantId: (json['tenantId'] ?? '') as String,
        label: (json['label'] ?? '') as String,
        status: (json['status'] ?? 'ACTIVE') as String,
        dailyOutboundCap: (json['dailyOutboundCap'] as num?)?.toInt() ?? 1000,
        nonMemberReply: json['nonMemberReply'] as String?,
        assignedBy: json['assignedBy'] as String?,
        assignedAt: json['assignedAt']?.toString(),
        endedAt: json['endedAt']?.toString(),
        stats: AssignmentStats.fromJson(_map(json['stats'])),
      );
}

/// `PUT /v1/admin/platform-senders/{s}/assignments/{tenantId}`.
class PlatformSenderAssignmentRequest {
  const PlatformSenderAssignmentRequest({required this.label, this.dailyOutboundCap});

  final String label;
  final int? dailyOutboundCap;

  Map<String, dynamic> toJson() => {
        'label': label,
        if (dailyOutboundCap != null) 'dailyOutboundCap': dailyOutboundCap,
      };
}

/// What ending an assignment did — or, on a dry run, would do (§11).
class AssignmentEndEffects {
  const AssignmentEndEffects({
    this.systemSendersUnlinked = const [],
    this.bindingsDeactivated = 0,
    this.keywordActionsDeleted = 0,
    this.membersDeleted = 0,
    this.sessionsClosed = 0,
    this.parkedOutboundDropped = 0,
    this.routesCleared = 0,
  });

  final List<String> systemSendersUnlinked;
  final int bindingsDeactivated;
  final int keywordActionsDeleted;
  final int membersDeleted;
  final int sessionsClosed;
  final int parkedOutboundDropped;
  final int routesCleared;

  factory AssignmentEndEffects.fromJson(Map<String, dynamic> json) {
    // Names, ids or `{id, name}` objects — whichever the server sends.
    List<String> senders(Object? v) => v is List
        ? v.map((e) => e is Map ? '${e['name'] ?? e['id'] ?? e}' : e.toString()).toList()
        : const [];
    return AssignmentEndEffects(
      systemSendersUnlinked: senders(json['systemSendersUnlinked']),
      bindingsDeactivated: _count(json['bindingsDeactivated']),
      keywordActionsDeleted: _count(json['keywordActionsDeleted']),
      membersDeleted: _count(json['membersDeleted']),
      sessionsClosed: _count(json['sessionsClosed']),
      parkedOutboundDropped: _count(json['parkedOutboundDropped']),
      routesCleared: _count(json['routesCleared']),
    );
  }

  /// One plain line per effect. Opt-outs always stay: they belong to the number.
  List<String> describe() {
    String n(int count, String one, String many) => '$count ${count == 1 ? one : many}';
    return [
      if (systemSendersUnlinked.isNotEmpty)
        'Unlinks ${n(systemSendersUnlinked.length, 'system sender', 'system senders')}: ${systemSendersUnlinked.join(', ')}',
      if (bindingsDeactivated > 0) 'Switches off ${n(bindingsDeactivated, 'route', 'routes')} on this number',
      if (keywordActionsDeleted > 0) 'Deletes ${n(keywordActionsDeleted, 'keyword action', 'keyword actions')}',
      if (membersDeleted > 0) 'Removes ${n(membersDeleted, 'registered member', 'registered members')}',
      if (sessionsClosed > 0) 'Closes ${n(sessionsClosed, 'open conversation', 'open conversations')}',
      if (parkedOutboundDropped > 0) 'Drops ${n(parkedOutboundDropped, 'queued message', 'queued messages')}',
      if (routesCleared > 0) 'Clears ${n(routesCleared, 'phone pinned', 'phones pinned')} to this workspace',
      'Opt-outs stay — they belong to the number',
    ];
  }
}

/// `DELETE /v1/admin/platform-senders/{s}/assignments/{tenantId}[?dryRun=true]`.
class AssignmentEndResult {
  const AssignmentEndResult({required this.dryRun, required this.effects});

  final bool dryRun;
  final AssignmentEndEffects effects;

  factory AssignmentEndResult.fromJson(Map<String, dynamic> json) => AssignmentEndResult(
        dryRun: json['dryRun'] == true,
        effects: AssignmentEndEffects.fromJson(_map(json['effects'])),
      );
}

/// An inbound on a platform sender that matched no workspace (§7.4).
class UnroutedInbound {
  const UnroutedInbound({
    required this.messageSid,
    required this.phoneMasked,
    this.receivedAt,
    this.reason,
    this.body,
  });

  final String messageSid;
  final String phoneMasked;
  final String? receivedAt;
  final String? reason;
  final String? body;

  factory UnroutedInbound.fromJson(Map<String, dynamic> json) => UnroutedInbound(
        messageSid: (json['messageSid'] ?? '') as String,
        phoneMasked: (json['phoneMasked'] ?? '') as String,
        receivedAt: json['receivedAt']?.toString(),
        reason: json['reason'] as String?,
        body: json['body'] as String?,
      );
}

/// `GET|PUT …/whatsapp-senders/{s}/members` — the calling workspace's staff
/// phones on a platform sender.
class SenderMembers {
  const SenderMembers({this.phones = const [], this.count = 0});

  final List<String> phones;
  final int count;

  factory SenderMembers.fromJson(Map<String, dynamic> json) {
    final phones = _strings(json['phones']);
    return SenderMembers(phones: phones, count: json['count'] is num ? _count(json['count']) : phones.length);
  }
}

/// How a keyword action matches on a sender (§8.2).
abstract final class KeywordScope {
  /// Anyone may send it; on a platform sender it routes the message to the
  /// workspace that owns the prefix, so the prefix is unique on the number.
  static const routed = 'ROUTED';

  /// Matches only once the message already belongs to the workspace.
  static const reply = 'REPLY';
}

/// Words a platform sender keeps for itself (§17.1 `reserved_keyword`).
const kPlatformReservedKeywords = {'CAMBIAR', 'CAMBIAR NEGOCIO', '#NEGOCIO', 'STOP', 'START'};

/// One keyword action: text starting with [prefix] is forwarded to [forwardUrl].
class KeywordActionItem {
  const KeywordActionItem({required this.prefix, required this.forwardUrl, this.scope = KeywordScope.routed});

  final String prefix;
  final String forwardUrl;
  final String scope;

  factory KeywordActionItem.fromJson(Map<String, dynamic> json) => KeywordActionItem(
        prefix: (json['prefix'] ?? '') as String,
        forwardUrl: (json['forwardUrl'] ?? '') as String,
        scope: (json['scope'] as String?)?.toUpperCase() == KeywordScope.reply ? KeywordScope.reply : KeywordScope.routed,
      );

  Map<String, dynamic> toJson() => {'prefix': prefix, 'forwardUrl': forwardUrl, 'scope': scope};

  /// GET response / PUT body: `{actions: [...]}`.
  static List<KeywordActionItem> listFromJson(Object? json) {
    final raw = json is Map ? json['actions'] : json;
    return raw is List ? raw.map((e) => KeywordActionItem.fromJson(_map(e))).toList() : const [];
  }
}

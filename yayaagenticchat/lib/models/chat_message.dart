import 'commerce/cart.dart';
import 'commerce/catalog_payload.dart';
import 'commerce/checkout_result.dart';
import 'commerce/product.dart';
import 'data_view.dart';
import 'receipts.dart';

/// One bubble in the conversation. Assistant bubbles accrete streamed tokens and
/// may carry engine-emitted quick replies + citations (chat-channel-design.md §9.1),
/// plus — for commerce profiles — one rich attachment the client renders as
/// chrome instead of prose: a product carousel, an expanded product card, or a
/// checkout result. `cartSnapshot` is the inline "what just changed" confirmation
/// after a cart mutation (ecommerce-chat-design.md §10.1, §10.3).
/// One tappable suggestion chip under the latest assistant bubble. The legacy
/// `quick_replies` payload carries bare labels (label == send); the v2
/// `actions` form separates the visible [label] from the [send] text the tap
/// submits (owner-ai-experience v2 §5).
class QuickReply {
  final String label;
  final String send;

  /// The option's id in the canonical `options` shape (assistant-channels
  /// design §2.2); null for the legacy `items` / `actions` shapes. When set,
  /// a tap also sends `{"kind":"option","id":…,"send":…}` as the selection.
  final String? id;

  /// `primary` / `danger` / `neutral`, or null for the default chip look.
  final String? style;

  const QuickReply({required this.label, required this.send, this.id, this.style});

  /// Legacy label-only chip: what you see is what gets sent.
  const QuickReply.text(String value)
      : label = value,
        send = value,
        id = null,
        style = null;

  /// Parses one `options` entry (`{id,label,send,style}`) or a legacy
  /// `actions` entry (`{"label":"En PDF","send":"Dame ese …"}`). Either side
  /// falls back to the other, so a lopsided entry still works.
  factory QuickReply.fromJson(Map<String, dynamic> j) {
    final label = (j['label'] ?? j['send'] ?? '').toString();
    final send = (j['send'] ?? j['label'] ?? '').toString();
    final id = j['id']?.toString();
    final style = j['style']?.toString();
    return QuickReply(
        label: label,
        send: send,
        id: id == null || id.isEmpty ? null : id,
        style: style == null || style.isEmpty ? null : style);
  }

  bool get isOption => id != null;

  /// The structured selection a tap on this chip rides with (null for legacy chips).
  Map<String, Object?>? get selection =>
      id == null ? null : {'kind': 'option', 'id': id, 'send': send};
}

/// An action button that BELONGS to a message (owner-ai v3 §D3): approve/deny
/// under an undo-request, etc. Unlike a [QuickReply], these are not stripped on
/// later turns — they stay with their bubble. [style] picks the affordance
/// (primary/danger/neutral).
///
/// A tap submits [send] through the normal send path — unless the platform
/// registered the action and handed back an [actionId] (WhatsApp ops §14.3):
/// then the tap sends the [label] as the legible transcript plus the
/// structured selection `{"kind":"action","actionId":…}`, and the platform runs
/// the bound tool itself. A bound TOOL action carries no `send` at all.
class MessageAction {
  final String label;
  final String send;
  final String style; // primary | danger | neutral

  /// The platform's capability id for this action (§14.3); null for a legacy
  /// text-only action, which keeps sending [send].
  final String? actionId;

  const MessageAction(
      {required this.label, required this.send, this.style = 'neutral', this.actionId});

  /// Whether a tap goes out as the bound `{kind:"action"}` selection.
  bool get isBound => actionId != null;

  /// Parses one `actions` entry: `{"label":"Aprobar","send":"…","style":"primary"}`
  /// or a bound one `{"label":"Aprobar","style":"primary","actionId":"…"}`.
  factory MessageAction.fromJson(Map<String, dynamic> j) {
    final label = (j['label'] ?? j['send'] ?? '').toString();
    final send = (j['send'] ?? j['label'] ?? '').toString();
    final style = (j['style'] ?? 'neutral').toString();
    final rawId = j['actionId']?.toString().trim();
    return MessageAction(
      label: label,
      send: send,
      style: style,
      actionId: rawId == null || rawId.isEmpty ? null : rawId,
    );
  }

  static List<MessageAction> listFrom(Object? raw) => raw is List
      ? [
          for (final a in raw)
            if (a is Map) MessageAction.fromJson(Map<String, dynamic>.from(a)),
        ]
      : const [];
}

class ChatMessage {
  final String role; // 'user' | 'assistant'
  final String text;
  final List<QuickReply> quickReplies;
  final List<Citation> citations;
  final bool streaming;

  // Commerce attachments (at most one primary attachment per bubble).
  final CatalogPayload? catalog;
  final Product? productCard;
  final CheckoutResult? checkoutResult;
  final Cart? cartSnapshot;

  // Receipt/ticket preview (chat-experience-design.md §7): the `receipt`
  // UiHint payload rendered as printed paper.
  final ReceiptInstance? receipt;

  // System-participant messages (system-messaging-design.md §14): an attributed
  // header ("Cash Register #3") + document/image attachments.
  final String? senderDisplayName;
  final List<ChatAttachment> attachments;

  // Action buttons that belong to this message (owner-ai v3 §D3): rendered as a
  // row under the bubble, NOT stripped on later turns.
  final List<MessageAction> actions;

  // Styled data views (owner-ai v3 §D4): a two-column card or a scrollable table
  // synthesized from a tool result. At most one of each per bubble.
  final DataTablePayload? dataTable;
  final DataCardPayload? dataCard;

  const ChatMessage({
    required this.role,
    required this.text,
    this.quickReplies = const [],
    this.citations = const [],
    this.streaming = false,
    this.catalog,
    this.productCard,
    this.checkoutResult,
    this.cartSnapshot,
    this.receipt,
    this.senderDisplayName,
    this.attachments = const [],
    this.actions = const [],
    this.dataTable,
    this.dataCard,
  });

  /// The default attributed sender label when a system message carries no
  /// explicit name — rendered localized (owner-ai v3 §D7).
  static const systemSenderFallback = 'System';

  bool get isUser => role == 'user';

  bool get isSystem => senderDisplayName != null;

  bool get hasAttachments => attachments.isNotEmpty;

  bool get hasCommerce =>
      catalog != null ||
      productCard != null ||
      checkoutResult != null ||
      cartSnapshot != null;

  ChatMessage copyWith({
    String? text,
    List<QuickReply>? quickReplies,
    List<Citation>? citations,
    bool? streaming,
    CatalogPayload? catalog,
    Product? productCard,
    CheckoutResult? checkoutResult,
    Cart? cartSnapshot,
    ReceiptInstance? receipt,
    String? senderDisplayName,
    List<ChatAttachment>? attachments,
    List<MessageAction>? actions,
    DataTablePayload? dataTable,
    DataCardPayload? dataCard,
  }) =>
      ChatMessage(
        role: role,
        text: text ?? this.text,
        quickReplies: quickReplies ?? this.quickReplies,
        citations: citations ?? this.citations,
        streaming: streaming ?? this.streaming,
        catalog: catalog ?? this.catalog,
        productCard: productCard ?? this.productCard,
        checkoutResult: checkoutResult ?? this.checkoutResult,
        cartSnapshot: cartSnapshot ?? this.cartSnapshot,
        receipt: receipt ?? this.receipt,
        senderDisplayName: senderDisplayName ?? this.senderDisplayName,
        attachments: attachments ?? this.attachments,
        actions: actions ?? this.actions,
        dataTable: dataTable ?? this.dataTable,
        dataCard: dataCard ?? this.dataCard,
      );
}

class Citation {
  final String title;
  final String? url;
  const Citation({required this.title, this.url});
}

/// A media attachment on a message — an image, voice note, or document served
/// from a short-lived signed URL. Inbound on system messages
/// (system-messaging-design.md §10) and outbound on the user's own sends
/// (chat-experience-design.md §6). `transcript` is present on voice notes the
/// server transcribed.
class ChatAttachment {
  final String assetId;
  final String contentType;
  final String? filename;
  final int sizeBytes;
  final String url; // signed, short-lived
  final String? transcript;

  const ChatAttachment({
    required this.assetId,
    required this.contentType,
    this.filename,
    this.sizeBytes = 0,
    required this.url,
    this.transcript,
  });

  bool get isImage => contentType.startsWith('image/');

  /// WebM voice notes sniff as video/webm server-side; in chat, webm can only
  /// be a voice note (videos aren't uploadable), so it renders as audio.
  bool get isAudio =>
      contentType.startsWith('audio/') ||
      contentType == 'application/ogg' ||
      contentType == 'video/webm';

  factory ChatAttachment.fromJson(Map<String, dynamic> json) => ChatAttachment(
        // Outbound chat uploads say `id`; inbox system messages say `assetId`.
        assetId: (json['assetId'] ?? json['id'] ?? '') as String,
        contentType: (json['contentType'] ?? 'application/octet-stream') as String,
        filename: json['filename'] as String?,
        sizeBytes: (json['sizeBytes'] as num?)?.toInt() ?? 0,
        url: (json['url'] ?? '') as String,
        transcript: json['transcript'] as String?,
      );
}

/// One drained inbox message (system-messaging-design.md §11).
class InboxMessage {
  final String sessionId;
  final String? senderDisplay;
  final String? subject;
  final String body;
  final List<ChatAttachment> attachments;

  /// Action buttons carried on the message (owner-ai v3 §D3).
  final List<MessageAction> actions;

  const InboxMessage({
    required this.sessionId,
    this.senderDisplay,
    this.subject,
    required this.body,
    this.attachments = const [],
    this.actions = const [],
  });

  factory InboxMessage.fromJson(Map<String, dynamic> json) => InboxMessage(
        sessionId: (json['sessionId'] ?? '') as String,
        senderDisplay: json['senderDisplay'] as String?,
        subject: json['subject'] as String?,
        body: (json['body'] ?? '') as String,
        attachments: (json['attachments'] as List? ?? const [])
            .map((e) => ChatAttachment.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
        actions: MessageAction.listFrom(json['actions']),
      );
}

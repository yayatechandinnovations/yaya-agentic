import 'commerce/cart.dart';
import 'commerce/catalog_payload.dart';
import 'commerce/checkout_result.dart';
import 'commerce/product.dart';
import 'receipts.dart';

/// One bubble in the conversation. Assistant bubbles accrete streamed tokens and
/// may carry engine-emitted quick replies + citations (chat-channel-design.md §9.1),
/// plus — for commerce profiles — one rich attachment the client renders as
/// chrome instead of prose: a product carousel, an expanded product card, or a
/// checkout result. `cartSnapshot` is the inline "what just changed" confirmation
/// after a cart mutation (ecommerce-chat-design.md §10.1, §10.3).
class ChatMessage {
  final String role; // 'user' | 'assistant'
  final String text;
  final List<String> quickReplies;
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
  });

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
    List<String>? quickReplies,
    List<Citation>? citations,
    bool? streaming,
    CatalogPayload? catalog,
    Product? productCard,
    CheckoutResult? checkoutResult,
    Cart? cartSnapshot,
    ReceiptInstance? receipt,
    String? senderDisplayName,
    List<ChatAttachment>? attachments,
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
  bool get isAudio => contentType.startsWith('audio/') || contentType == 'application/ogg';

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

  const InboxMessage({
    required this.sessionId,
    this.senderDisplay,
    this.subject,
    required this.body,
    this.attachments = const [],
  });

  factory InboxMessage.fromJson(Map<String, dynamic> json) => InboxMessage(
        sessionId: (json['sessionId'] ?? '') as String,
        senderDisplay: json['senderDisplay'] as String?,
        subject: json['subject'] as String?,
        body: (json['body'] ?? '') as String,
        attachments: (json['attachments'] as List? ?? const [])
            .map((e) => ChatAttachment.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
      );
}

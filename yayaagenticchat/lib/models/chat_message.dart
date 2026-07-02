import 'commerce/cart.dart';
import 'commerce/catalog_payload.dart';
import 'commerce/checkout_result.dart';
import 'commerce/product.dart';

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
  });

  bool get isUser => role == 'user';

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
      );
}

class Citation {
  final String title;
  final String? url;
  const Citation({required this.title, this.url});
}

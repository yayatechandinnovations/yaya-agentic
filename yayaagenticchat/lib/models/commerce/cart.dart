import 'commerce_json.dart';
import 'money.dart';

/// One physical line in the cart. `lineTotal` is the merchant's derived number,
/// never recomputed client-side (non-neg #1).
class CartLine {
  final String lineId;
  final String? productId;
  final String? variantId;
  final String title;
  final String? image;
  final int qty;
  final Money? unitPrice;
  final Money? lineTotal;

  const CartLine({
    required this.lineId,
    this.productId,
    this.variantId,
    required this.title,
    this.image,
    required this.qty,
    this.unitPrice,
    this.lineTotal,
  });

  factory CartLine.fromJson(Map<String, dynamic> j) => CartLine(
        lineId: idStr(j['lineId']) ?? '',
        productId: idStr(j['productId']),
        variantId: idStr(j['variantId']),
        title: (j['title'] ?? '').toString(),
        image: asStr(j['image']),
        qty: asInt(j['qty']),
        unitPrice: Money.tryFrom(j['unitPrice']),
        lineTotal: Money.tryFrom(j['lineTotal']),
      );
}

/// A session's cart snapshot — the shape rendered by the `cart` UiHint. The
/// merchant backend (or the managed fallback) is the source of truth; every
/// `Money` here is authoritative (ecommerce-chat-design.md §7.3).
class Cart {
  final String? cartHandle;
  final String currency;
  final List<CartLine> lines;
  final Money? subtotal;
  final String? updatedAt;

  const Cart({
    this.cartHandle,
    required this.currency,
    this.lines = const [],
    this.subtotal,
    this.updatedAt,
  });

  bool get isEmpty => lines.isEmpty;

  /// `itemCount` is `@JsonIgnore` on the server, so derive it here.
  int get itemCount => lines.fold(0, (sum, l) => sum + l.qty);

  factory Cart.fromJson(Map<String, dynamic> j) => Cart(
        cartHandle: asStr(j['cartHandle']),
        currency: (j['currency'] ?? 'USD').toString(),
        lines: asList(j['lines']).map((e) => CartLine.fromJson(asMap(e))).toList(),
        subtotal: Money.tryFrom(j['subtotal']),
        updatedAt: asStr(j['updatedAt']),
      );
}

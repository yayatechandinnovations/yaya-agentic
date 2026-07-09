import 'commerce_json.dart';

/// The commerce trait the client reads from `StartResult` to render cart chrome
/// the moment a store chat opens (ecommerce-chat-design.md §3). Mirrors the
/// backend `CommerceBinding` since the yaya-sales extraction: the server sends
/// `{storeId, cartEnabled, checkoutTool}` — currency now arrives with each
/// priced payload (cart/checkout), never at session start. `null` from the
/// server = not a store.
class CommerceConfig {
  /// The yaya-sales store this profile sells for. Null on pre-extraction
  /// servers; informational client-side (tools carry it server-side).
  final String? storeId;

  /// Fallback for pre-extraction servers that still send a session currency;
  /// priced payloads are authoritative.
  final String currency;
  final bool cartEnabled;

  /// The tool id a "Checkout" tap invokes on the action channel. The seeded
  /// store uses `checkout`; fall back to that if the server omits it.
  final String checkoutTool;

  const CommerceConfig({
    this.storeId,
    required this.currency,
    required this.cartEnabled,
    this.checkoutTool = 'checkout',
  });

  bool get isStore => cartEnabled;

  factory CommerceConfig.fromJson(Map<String, dynamic> j) => CommerceConfig(
        storeId: idStr(j['storeId']),
        currency: (j['currency'] ?? 'USD').toString(),
        cartEnabled: asBool(j['cartEnabled']),
        checkoutTool: (idStr(j['checkoutTool']) ?? 'checkout'),
      );
}

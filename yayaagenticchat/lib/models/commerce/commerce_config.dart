import 'commerce_json.dart';

/// The commerce trait the client reads from `StartResult` to render cart chrome
/// the moment a store chat opens (ecommerce-chat-design.md §3). Mirrors the
/// backend `CommerceInfo` DTO. `null` from the server = not a store.
class CommerceConfig {
  final String currency;
  final bool cartEnabled;

  /// The tool id a "Checkout" tap invokes on the action channel. The seeded
  /// store uses `checkout`; fall back to that if the server omits it.
  final String checkoutTool;

  const CommerceConfig({
    required this.currency,
    required this.cartEnabled,
    this.checkoutTool = 'checkout',
  });

  bool get isStore => cartEnabled;

  factory CommerceConfig.fromJson(Map<String, dynamic> j) => CommerceConfig(
        currency: (j['currency'] ?? 'USD').toString(),
        cartEnabled: asBool(j['cartEnabled']),
        checkoutTool: (idStr(j['checkoutTool']) ?? 'checkout'),
      );
}

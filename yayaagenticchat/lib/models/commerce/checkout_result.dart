import 'commerce_json.dart';
import 'money.dart';

/// How the client completes payment — the `checkout_result` UiHint payload.
/// Carries the order ref + authoritative amount and exactly one of: a hosted
/// page `url`, a PSP drop-in (`clientSecret` + `publishableKey`), or a terminal
/// `status`. Never any card data (ecommerce-chat-design.md §11, non-neg #1).
enum CheckoutMode { hostedUrl, paymentIntent, orderPlaced, unknown }

class CheckoutResult {
  final CheckoutMode mode;
  final String? orderRef;
  final int amountMinor;
  final String currency;

  // hosted_url
  final String? url;

  // payment_intent
  final String? clientSecret;
  final String? publishableKey;

  // order_placed
  final String? status;

  const CheckoutResult({
    required this.mode,
    this.orderRef,
    this.amountMinor = 0,
    this.currency = 'USD',
    this.url,
    this.clientSecret,
    this.publishableKey,
    this.status,
  });

  Money get amount => Money(amountMinor, currency);

  static CheckoutMode _mode(dynamic v) {
    switch (v?.toString()) {
      case 'hosted_url':
        return CheckoutMode.hostedUrl;
      case 'payment_intent':
        return CheckoutMode.paymentIntent;
      case 'order_placed':
        return CheckoutMode.orderPlaced;
      default:
        return CheckoutMode.unknown;
    }
  }

  factory CheckoutResult.fromJson(Map<String, dynamic> j) => CheckoutResult(
        mode: _mode(j['mode']),
        orderRef: asStr(j['orderRef']),
        amountMinor: asInt(j['amountMinor']),
        currency: (j['currency'] ?? 'USD').toString(),
        url: asStr(j['url']),
        clientSecret: asStr(j['clientSecret']),
        publishableKey: asStr(j['publishableKey']),
        status: asStr(j['status']),
      );
}

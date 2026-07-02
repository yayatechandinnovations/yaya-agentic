import 'package:intl/intl.dart';

import 'commerce_json.dart';

/// A monetary amount in **integer minor units** (e.g. cents) with an explicit
/// ISO-4217 currency — the mirror of the backend `Money` record. The model
/// never touches money; this is only for *rendering* the merchant-authoritative
/// number, never for computing one (ecommerce-chat-design.md §4, non-neg #1).
class Money {
  final int amountMinor;
  final String currency;

  const Money(this.amountMinor, this.currency);

  static Money zero(String currency) => Money(0, currency);

  factory Money.fromJson(Map<String, dynamic> j) =>
      Money(asInt(j['amountMinor']), (j['currency'] ?? 'USD').toString());

  /// Tolerant parse for a nullable/typed field.
  static Money? tryFrom(dynamic v) =>
      v is Map ? Money.fromJson(Map<String, dynamic>.from(v)) : null;

  /// Minor units → a localized currency string (e.g. 2598 USD → "$25.98").
  /// Decimal places come from the currency, not a hardcoded 2.
  String format({String? locale}) {
    final decimals = _decimalsFor(currency);
    final major = amountMinor / _pow10(decimals);
    final fmt = NumberFormat.simpleCurrency(
        locale: locale, name: currency, decimalDigits: decimals);
    return fmt.format(major);
  }

  @override
  String toString() => format();

  static int _pow10(int n) {
    var r = 1;
    for (var i = 0; i < n; i++) {
      r *= 10;
    }
    return r;
  }

  /// Zero-decimal currencies (JPY, CLP, …) must not be divided by 100.
  static int _decimalsFor(String currency) {
    const zeroDecimal = {
      'JPY', 'KRW', 'CLP', 'VND', 'XAF', 'XOF', 'PYG', 'RWF', 'UGX', 'BIF',
      'DJF', 'GNF', 'KMF', 'VUV', 'XPF',
    };
    const threeDecimal = {'BHD', 'IQD', 'JOD', 'KWD', 'OMR', 'TND', 'LYD'};
    final c = currency.toUpperCase();
    if (zeroDecimal.contains(c)) return 0;
    if (threeDecimal.contains(c)) return 3;
    return 2;
  }
}

import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticchat/models/commerce/money.dart';

/// Money is integer minor units with an explicit currency; formatting must
/// respect the currency's decimal places (never a hardcoded ÷100).
void main() {
  test('two-decimal currency divides by 100', () {
    expect(Money(2598, 'USD').format(locale: 'en_US'), contains('25.98'));
  });

  test('zero-decimal currency is not divided', () {
    final s = Money(500, 'JPY').format(locale: 'ja_JP');
    expect(s, contains('500'));
    expect(s.contains('5.00'), false);
  });

  test('zero helper', () {
    expect(Money.zero('USD').amountMinor, 0);
  });

  test('tryFrom tolerates null and maps', () {
    expect(Money.tryFrom(null), isNull);
    expect(Money.tryFrom({'amountMinor': 100, 'currency': 'eur'})!.currency, 'eur');
  });
}

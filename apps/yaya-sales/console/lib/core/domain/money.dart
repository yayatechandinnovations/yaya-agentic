import 'package:equatable/equatable.dart';

/// Exact money as it crosses the wire: integer minor units + ISO-4217 code.
/// The console only ever RENDERS money (÷100, 2dp) — it never computes it;
/// every amount shown comes from the server as minor units.
class Money extends Equatable {
  const Money({required this.amountMinor, required this.currency});

  final int amountMinor;
  final String currency;

  @override
  List<Object?> get props => [amountMinor, currency];
}

/// Display-only formatting of server-provided minor units.
String formatMinor(int amountMinor, String currency) =>
    '$currency ${(amountMinor / 100).toStringAsFixed(2)}';

String formatMoney(Money? money) =>
    money == null ? '—' : formatMinor(money.amountMinor, money.currency);

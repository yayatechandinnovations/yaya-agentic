import '../domain/money.dart';

/// Wire-shape helpers. yaya-sales serializes typed ids as `{"value": "…"}`
/// records and money as `{"amountMinor": N, "currency": "XXX"}` — these keep
/// every model tolerant of both the nested record shape and a plain string.
String idOf(dynamic v) => v is Map ? '${v['value']}' : '$v';

String? idOrNull(dynamic v) => v == null ? null : idOf(v);

Money? moneyOf(dynamic v) {
  if (v is! Map) return null;
  final amount = v['amountMinor'];
  final currency = v['currency'];
  if (amount is! num || currency == null) return null;
  return Money(amountMinor: amount.toInt(), currency: '$currency');
}

int intOf(dynamic v, [int fallback = 0]) => v is num ? v.toInt() : fallback;

DateTime? dateOf(dynamic v) => v == null ? null : DateTime.tryParse('$v');

Map<String, dynamic> mapOf(dynamic v) =>
    v is Map ? v.map((k, dynamic val) => MapEntry('$k', val)) : <String, dynamic>{};

List<Map<String, dynamic>> listOfMaps(dynamic v) =>
    v is List ? v.map(mapOf).toList() : const [];

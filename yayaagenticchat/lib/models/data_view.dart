import 'package:intl/intl.dart';

/// Structured data-view payloads for the `data_table` / `data_card` ui_hints
/// (owner-ai-experience v3 §D4). The engine synthesizes these from tool results
/// (catalog-driven projections), so the client renders a styled two-column card
/// or a scrollable table instead of a wall of LLM-narrated prose.

/// Cell/column value semantics that drive client-side formatting. Unknown types
/// degrade to [text] so a newer server never breaks an older client.
enum DataValueType { text, money, count, percent, datetime }

DataValueType dataValueTypeFrom(Object? raw) {
  switch (raw?.toString()) {
    case 'money':
      return DataValueType.money;
    case 'count':
      return DataValueType.count;
    case 'percent':
      return DataValueType.percent;
    case 'datetime':
      return DataValueType.datetime;
    default:
      return DataValueType.text;
  }
}

/// One column of a [DataTablePayload]. [key] indexes the value in each row map.
class DataColumnSpec {
  final String key;
  final String label;
  final DataValueType type;

  const DataColumnSpec({required this.key, required this.label, required this.type});

  factory DataColumnSpec.fromJson(Map<String, dynamic> j) => DataColumnSpec(
        key: (j['key'] ?? '').toString(),
        label: (j['label'] ?? j['key'] ?? '').toString(),
        type: dataValueTypeFrom(j['type']),
      );
}

/// `data_table` payload: `{title?, columns:[{key,label,type}], rows:[{key:value}],
/// totals?:{key:value}}`.
class DataTablePayload {
  final String? title;
  final List<DataColumnSpec> columns;
  final List<Map<String, dynamic>> rows;
  final Map<String, dynamic>? totals;

  const DataTablePayload({
    this.title,
    this.columns = const [],
    this.rows = const [],
    this.totals,
  });

  bool get hasTotals => totals != null && totals!.isNotEmpty;

  factory DataTablePayload.fromJson(Map<String, dynamic> j) => DataTablePayload(
        title: (j['title'] as Object?)?.toString(),
        columns: [
          for (final c in (j['columns'] as List? ?? const []))
            if (c is Map) DataColumnSpec.fromJson(Map<String, dynamic>.from(c)),
        ],
        rows: [
          for (final r in (j['rows'] as List? ?? const []))
            if (r is Map) Map<String, dynamic>.from(r),
        ],
        totals: j['totals'] is Map
            ? Map<String, dynamic>.from(j['totals'] as Map)
            : null,
      );
}

/// One `{label, value, type}` row of a [DataCardPayload].
class DataCardRow {
  final String label;
  final Object? value;
  final DataValueType type;

  const DataCardRow({required this.label, this.value, required this.type});

  factory DataCardRow.fromJson(Map<String, dynamic> j) => DataCardRow(
        label: (j['label'] ?? '').toString(),
        value: j['value'],
        type: dataValueTypeFrom(j['type']),
      );
}

/// `data_card` payload: `{title?, rows:[{label,value,type}]}`.
class DataCardPayload {
  final String? title;
  final List<DataCardRow> rows;

  const DataCardPayload({this.title, this.rows = const []});

  factory DataCardPayload.fromJson(Map<String, dynamic> j) => DataCardPayload(
        title: (j['title'] as Object?)?.toString(),
        rows: [
          for (final r in (j['rows'] as List? ?? const []))
            if (r is Map) DataCardRow.fromJson(Map<String, dynamic>.from(r)),
        ],
      );
}

/// Format one value by its [DataValueType]. Values arrive as num or String; be
/// defensive so a malformed cell degrades to its raw text rather than crashing.
/// Money renders as Dominican peso (e.g. "RD\$1,234.56").
String formatDataValue(Object? value, DataValueType type, {String locale = 'es'}) {
  if (value == null) return '';
  final raw = value.toString();
  switch (type) {
    case DataValueType.money:
      final n = _asNum(value);
      if (n == null) return raw;
      return NumberFormat.currency(locale: locale, symbol: r'RD$', decimalDigits: 2)
          .format(n);
    case DataValueType.count:
      final n = _asNum(value);
      if (n == null) return raw;
      return NumberFormat.decimalPattern(locale).format(n.round());
    case DataValueType.percent:
      final n = _asNum(value);
      if (n == null) return raw;
      // Values already expressed in percent points (12.5 → "12.5%").
      final s = n == n.roundToDouble()
          ? n.toStringAsFixed(0)
          : n.toString();
      return '$s%';
    case DataValueType.datetime:
      final dt = DateTime.tryParse(raw);
      if (dt == null) return raw;
      return DateFormat.yMMMd(locale).add_Hm().format(dt.toLocal());
    case DataValueType.text:
      return raw;
  }
}

num? _asNum(Object? value) {
  if (value is num) return value;
  return num.tryParse(value.toString().replaceAll(',', '').trim());
}

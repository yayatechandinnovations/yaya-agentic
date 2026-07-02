/// Tolerant JSON coercion helpers for the commerce wire payloads.
///
/// The backend serializes typed ids (`ProductId`, `ToolId`) as single-field
/// records, so an id can arrive either as a bare string or as `{"value": "…"}`
/// depending on Jackson config. `Money` is always `{amountMinor, currency}` but
/// numbers may deserialize as `int` or `double`. These helpers keep every
/// `fromJson` defensive so a shape drift on the server never crashes a render.
library;

/// Extract a string id whether it came as `"p_44"` or `{"value":"p_44"}`.
String? idStr(dynamic v) {
  if (v == null) return null;
  if (v is String) return v;
  if (v is Map && v['value'] != null) return v['value'].toString();
  return v.toString();
}

String? asStr(dynamic v) => v?.toString();

int asInt(dynamic v, [int fallback = 0]) {
  if (v is int) return v;
  if (v is num) return v.toInt();
  if (v is String) return int.tryParse(v) ?? fallback;
  return fallback;
}

int? asIntOrNull(dynamic v) {
  if (v == null) return null;
  if (v is int) return v;
  if (v is num) return v.toInt();
  if (v is String) return int.tryParse(v);
  return null;
}

bool asBool(dynamic v, [bool fallback = false]) {
  if (v is bool) return v;
  if (v is String) return v.toLowerCase() == 'true';
  return fallback;
}

Map<String, dynamic> asMap(dynamic v) =>
    v is Map ? Map<String, dynamic>.from(v) : const {};

List<dynamic> asList(dynamic v) => v is List ? v : const [];

List<String> asStrList(dynamic v) =>
    asList(v).map((e) => e.toString()).toList(growable: false);

/// Receipt/ticket previews (chat-experience-design.md §7): the domain-neutral
/// template model the `chat.receipts` trait delivers at session start, and the
/// per-instance payload a `receipt` UiHint carries. The renderer interprets
/// only the closed element vocabulary; every domain word lives in `data`.
class ReceiptsConfig {
  final List<ReceiptTemplate> templates;

  /// Fallback: tool id → template id, for tools that emit bare data.
  final Map<String, String> bindings;

  const ReceiptsConfig({this.templates = const [], this.bindings = const {}});

  bool get isEmpty => templates.isEmpty;

  ReceiptTemplate? byId(String? id) {
    if (id == null) return null;
    for (final t in templates) {
      if (t.id == id) return t;
    }
    return null;
  }

  factory ReceiptsConfig.fromJson(Map<String, dynamic> j) => ReceiptsConfig(
        templates: [
          for (final t in (j['templates'] as List? ?? const []))
            ReceiptTemplate.fromJson(Map<String, dynamic>.from(t as Map)),
        ],
        bindings: {
          for (final e in (j['bindings'] as Map? ?? const {}).entries)
            e.key.toString(): e.value.toString(),
        },
      );
}

class ReceiptTemplate {
  final String id;
  final String name;
  final String? kindLabel;
  final int paperCols;
  final String paperProfile; // 58mm | 80mm
  final List<Map<String, dynamic>> elements;
  final Map<String, dynamic> brand;

  const ReceiptTemplate({
    required this.id,
    required this.name,
    this.kindLabel,
    this.paperCols = 32,
    this.paperProfile = '58mm',
    this.elements = const [],
    this.brand = const {},
  });

  factory ReceiptTemplate.fromJson(Map<String, dynamic> j) {
    final paper = j['paper'] is Map ? Map<String, dynamic>.from(j['paper'] as Map) : const <String, dynamic>{};
    return ReceiptTemplate(
      id: (j['id'] ?? '') as String,
      name: (j['name'] ?? 'Receipt') as String,
      kindLabel: j['kindLabel'] as String?,
      paperCols: (paper['cols'] as num?)?.toInt() ?? 32,
      paperProfile: (paper['profile'] ?? '58mm') as String,
      elements: [
        for (final e in (j['elements'] as List? ?? const []))
          Map<String, dynamic>.from(e as Map),
      ],
      brand: j['brand'] is Map ? Map<String, dynamic>.from(j['brand'] as Map) : const {},
    );
  }
}

/// One document: a `receipt` UiHint payload or a wallet row. A `draft` receipt
/// is a work-in-progress (a ticket being built play-by-play, §7.8): it renders
/// as a live pinned preview that updates in place and is never collected into
/// the wallet; the final (non-draft) receipt is the issued document.
class ReceiptInstance {
  final String? receiptId;
  final String? templateId;
  final Map<String, dynamic> data;
  final bool draft;

  /// Optional action buttons; each routes through /actions like a cart tap.
  final List<ReceiptAction> actions;

  /// Wallet rows carry the layout snapshotted at issuance.
  final ReceiptTemplate? snapshot;

  final String? createdAt;
  final String? handle;

  const ReceiptInstance({
    this.receiptId,
    this.templateId,
    this.data = const {},
    this.draft = false,
    this.actions = const [],
    this.snapshot,
    this.createdAt,
    this.handle,
  });

  factory ReceiptInstance.fromHint(Map<String, dynamic> j) => ReceiptInstance(
        receiptId: j['receiptId'] as String?,
        templateId: j['templateId'] as String?,
        data: j['data'] is Map ? Map<String, dynamic>.from(j['data'] as Map) : const {},
        draft: (j['draft'] ?? false) as bool,
        actions: [
          for (final a in (j['actions'] as List? ?? const []))
            ReceiptAction.fromJson(Map<String, dynamic>.from(a as Map)),
        ],
      );

  factory ReceiptInstance.fromWallet(Map<String, dynamic> j) => ReceiptInstance(
        receiptId: j['id'] as String?,
        templateId: j['templateId'] as String?,
        data: j['data'] is Map ? Map<String, dynamic>.from(j['data'] as Map) : const {},
        snapshot: j['template'] is Map
            ? ReceiptTemplate.fromJson(Map<String, dynamic>.from(j['template'] as Map))
            : null,
        createdAt: j['createdAt'] as String?,
        handle: j['handle'] as String?,
      );
}

class ReceiptAction {
  final String label;
  final String tool;
  final Map<String, dynamic> args;

  const ReceiptAction({required this.label, required this.tool, this.args = const {}});

  factory ReceiptAction.fromJson(Map<String, dynamic> j) => ReceiptAction(
        label: (j['label'] ?? '') as String,
        tool: (j['tool'] ?? '') as String,
        args: j['args'] is Map ? Map<String, dynamic>.from(j['args'] as Map) : const {},
      );
}

/// Resolve a `{{path}}` binding with optional lookup-only formatters
/// (`{{total | money}}`, `{{phone | maskTail:4}}`, `{{serial | group:3}}`,
/// `{{numbers | chunk:2}}`) against the instance data. Never evaluates
/// expressions — binding is lookup + formatting, nothing else (§7.4).
String bindReceiptText(String? template, Map<String, dynamic> data) {
  if (template == null || template.isEmpty) return '';
  final pattern = RegExp(r'\{\{\s*([^}|]+?)\s*(?:\|\s*([^}]+?)\s*)?\}\}');
  return template.replaceAllMapped(pattern, (m) {
    final value = _lookup(data, m.group(1)!.trim());
    final formatted = _format(value, m.group(2)?.trim());
    return formatted;
  });
}

/// Look up a dotted path (`store.name`) in the instance data.
Object? _lookup(Map<String, dynamic> data, String path) {
  Object? cur = data;
  for (final part in path.split('.')) {
    if (cur is Map) {
      cur = cur[part];
    } else {
      return null;
    }
  }
  return cur;
}

/// Resolve a repeat binding to a list of row maps.
List<Map<String, dynamic>> bindReceiptRows(String? repeatPath, Map<String, dynamic> data) {
  if (repeatPath == null || repeatPath.isEmpty) return const [];
  final v = _lookup(data, repeatPath);
  if (v is! List) return const [];
  return [
    for (final row in v)
      if (row is Map) Map<String, dynamic>.from(row),
  ];
}

/// A repeat binding whose rows are scalars (`cells` with `bind`).
List<String> bindReceiptScalars(String? repeatPath, String? bind, Map<String, dynamic> data) {
  if (repeatPath == null) return const [];
  final v = _lookup(data, repeatPath);
  if (v is! List) return const [];
  return [
    for (final row in v)
      if (row is Map && bind != null)
        (row[bind] ?? '').toString()
      else if (row is! Map)
        row.toString(),
  ];
}

String _format(Object? value, String? formatter) {
  if (value == null) return '';
  final s = value.toString();
  if (formatter == null || formatter.isEmpty) return s;
  final parts = formatter.split(':');
  final name = parts[0].trim();
  final arg = parts.length > 1 ? int.tryParse(parts[1].trim()) : null;
  switch (name) {
    case 'money':
      final n = value is num ? value : num.tryParse(s);
      return n == null ? s : n.toStringAsFixed(2);
    case 'maskTail':
      final keep = arg ?? 4;
      final digits = s.replaceAll(RegExp(r'[^0-9]'), '');
      if (digits.length <= keep) return digits;
      return '*** *** ${digits.substring(digits.length - keep)}';
    case 'group':
      final size = arg ?? 3;
      final clean = s.replaceAll(' ', '');
      final sb = StringBuffer();
      for (var i = 0; i < clean.length; i++) {
        if (i > 0 && i % size == 0) sb.write(' ');
        sb.write(clean[i]);
      }
      return sb.toString();
    case 'chunk':
      final size = arg ?? 2;
      final clean = s.replaceAll(RegExp(r'\s+'), '');
      final sb = StringBuffer();
      for (var i = 0; i < clean.length; i += size) {
        if (i > 0) sb.write('-');
        sb.write(clean.substring(i, i + size > clean.length ? clean.length : i + size));
      }
      return sb.toString();
    default:
      return s;
  }
}

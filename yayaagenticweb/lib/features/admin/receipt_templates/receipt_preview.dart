import 'package:flutter/material.dart';

/// Built-in sample data the console preview binds templates against. Mirrors
/// the runtime contract (chat-experience-design.md §7.4): every domain word a
/// receipt shows — labels included — arrives in the hint's `data`, so the
/// sample carries labels too. Covers both starter templates.
const Map<String, dynamic> kReceiptSampleData = <String, dynamic>{
  'store': {
    'name': 'DEMO STORE #12',
    'legal': 'Demo Retail Group SRL',
  },
  'statusStamp': 'REPRINT',
  'issuedAt': '2026-08-06 14:32',
  'serial': '000123456789',
  'docTitle': 'SALE RECEIPT',
  'drawLabel': 'Valid for',
  'drawDate': 'Aug 06 · 8:55 PM',
  'lines': [
    {'label': 'Item alpha', 'qty': '2', 'amount': 150},
    {'label': 'Item beta', 'qty': '1', 'amount': 425.5},
    {'label': 'Item gamma', 'qty': '3', 'amount': 75},
  ],
  'sections': [
    {
      'title': 'EVENING DRAW',
      'subtitle': 'Aug 06 · 8:55 PM',
      'rows': [
        {'label': '12-34', 'qty': '2', 'amount': 100},
        {'label': '05-23', 'qty': '1', 'amount': 50},
      ],
    },
    {
      'title': 'NIGHT DRAW',
      'subtitle': 'Aug 06 · 10:55 PM',
      'rows': [
        {'label': '44-08', 'qty': '4', 'amount': 200},
      ],
    },
  ],
  'picks': [
    {'value': '05'},
    {'value': '12'},
    {'value': '23'},
    {'value': '34'},
    {'value': '44'},
  ],
  'totalLabel': 'TOTAL',
  'totalAmount': 650.5,
  'codeLabel': 'PAYMENT CODE',
  'paymentCode': '482913650274',
  'verifyUrl': 'https://example.com/v/000123456789',
  'customerLabel': 'Customer',
  'customer': {'phone': '18095551234'},
  'disclaimer1': 'Keep this receipt to claim.',
  'disclaimer2': 'Valid for 30 days from issue.',
};

/// Lightweight console-side renderer for the §7.4 receipt vocabulary:
/// white paper card, monospace "thermal ink" text, dashed dividers,
/// placeholder boxes for barcode/QR (no barcode dependency in the console —
/// the chat app owns the real Code-128/QR rendering). Interprets `{{path}}`
/// bindings with the lookup-only formatters money / maskTail:n / group:n /
/// chunk:n against [data]. Unknown element types are skipped (forward
/// compatibility), matching the runtime contract.
class ReceiptPreview extends StatelessWidget {
  const ReceiptPreview({
    super.key,
    required this.elements,
    required this.paperCols,
    this.data = kReceiptSampleData,
  });

  final List<dynamic> elements;
  final int paperCols;
  final Map<String, dynamic> data;

  static const _ink = Color(0xFF262626);
  static const _mutedInk = Color(0xFF5A5A5A);

  @override
  Widget build(BuildContext context) {
    final width = (paperCols * 7.2 + 32).clamp(200.0, 460.0);
    return Container(
      padding: const EdgeInsets.all(16),
      alignment: Alignment.topCenter,
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Container(
        width: width,
        padding: const EdgeInsets.fromLTRB(16, 20, 16, 20),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(2),
          boxShadow: const [
            BoxShadow(color: Colors.black26, blurRadius: 6, offset: Offset(0, 2)),
          ],
        ),
        child: DefaultTextStyle(
          style: const TextStyle(
            fontFamily: 'monospace',
            fontSize: 12,
            height: 1.35,
            color: _ink,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: _renderList(elements, data),
          ),
        ),
      ),
    );
  }

  // ---- element dispatch ------------------------------------------------

  List<Widget> _renderList(List<dynamic> els, Map<String, dynamic> scope) {
    final out = <Widget>[];
    var i = 0;
    while (i < els.length) {
      final raw = els[i];
      if (raw is! Map) {
        i++;
        continue;
      }
      final el = Map<String, dynamic>.from(raw);
      // Two-up grid: consecutive `span: half` elements share a row.
      final next = i + 1 < els.length && els[i + 1] is Map
          ? Map<String, dynamic>.from(els[i + 1] as Map)
          : null;
      if (el['span'] == 'half' && next != null && next['span'] == 'half') {
        final left = _render(el, scope);
        final right = _render(next, scope);
        out.add(Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(child: left ?? const SizedBox.shrink()),
            const SizedBox(width: 8),
            Expanded(child: right ?? const SizedBox.shrink()),
          ],
        ));
        i += 2;
        continue;
      }
      final w = _render(el, scope);
      if (w != null) out.add(w);
      i++;
    }
    return out;
  }

  Widget? _render(Map<String, dynamic> el, Map<String, dynamic> scope) {
    switch (el['type']) {
      case 'header':
        return _header(el, scope);
      case 'status':
        return _status(el, scope);
      case 'meta':
        return _meta(el, scope);
      case 'divider':
        return _divider(el);
      case 'title':
        return _title(el, scope);
      case 'text':
        return _text(el, scope);
      case 'kv':
        return _kv(el, scope);
      case 'section':
        return _section(el, scope);
      case 'table':
        return _table(el, scope);
      case 'cells':
        return _cells(el, scope);
      case 'total':
        return _total(el, scope);
      case 'code':
        return _code(el, scope);
      case 'barcode':
        return _barcode(el, scope);
      case 'qr':
        return _qr(el, scope);
      case 'footer':
        return _footer(el, scope);
      case 'spacer':
        return SizedBox(height: (el['size'] as num?)?.toDouble() ?? 8);
      default:
        return null; // unknown types are skipped — forward compatibility
    }
  }

  // ---- element renderers -----------------------------------------------

  Widget _header(Map<String, dynamic> el, Map<String, dynamic> scope) {
    final title = _bind(el['title'], scope);
    final subtitle = _bind(el['subtitle'], scope);
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Column(
        children: [
          if (el['logo'] == true)
            Container(
              width: 48,
              height: 48,
              margin: const EdgeInsets.only(bottom: 6),
              decoration: BoxDecoration(
                border: Border.all(color: _mutedInk),
              ),
              alignment: Alignment.center,
              child: const Text('LOGO',
                  style: TextStyle(fontSize: 9, color: _mutedInk)),
            ),
          if (title.isNotEmpty)
            Text(title,
                textAlign: TextAlign.center,
                style: _styled(el,
                    base: const TextStyle(
                        fontSize: 14, fontWeight: FontWeight.w700))),
          if (subtitle.isNotEmpty)
            Text(subtitle,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 10, color: _mutedInk)),
        ],
      ),
    );
  }

  Widget? _status(Map<String, dynamic> el, Map<String, dynamic> scope) {
    final text = _bind(el['text'], scope);
    if (text.isEmpty) return null; // skipped when unbound, per the design
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 4),
      padding: const EdgeInsets.symmetric(vertical: 4),
      decoration: BoxDecoration(border: Border.all(color: _ink, width: 1.5)),
      alignment: Alignment.center,
      child: Text(text,
          style: const TextStyle(
              fontSize: 13, fontWeight: FontWeight.w700, letterSpacing: 3)),
    );
  }

  Widget _meta(Map<String, dynamic> el, Map<String, dynamic> scope) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Flexible(
            child: Text(_bind(el['left'], scope),
                style: const TextStyle(fontSize: 11))),
        Flexible(
            child: Text(_bind(el['right'], scope),
                textAlign: TextAlign.right,
                style: const TextStyle(fontSize: 11))),
      ],
    );
  }

  Widget _divider(Map<String, dynamic> el) {
    if (el['style'] == 'solid') {
      return Container(
          height: 1, margin: const EdgeInsets.symmetric(vertical: 6), color: _ink);
    }
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Text('-' * 128,
          maxLines: 1,
          overflow: TextOverflow.clip,
          softWrap: false,
          style: const TextStyle(fontSize: 12, color: _mutedInk)),
    );
  }

  Widget _title(Map<String, dynamic> el, Map<String, dynamic> scope) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Text(
        _bind(el['text'], scope),
        textAlign: _align(el['align'], fallback: TextAlign.center),
        style: _styled(el,
            base: const TextStyle(
                fontSize: 14, fontWeight: FontWeight.w700, letterSpacing: 1.5)),
      ),
    );
  }

  Widget _text(Map<String, dynamic> el, Map<String, dynamic> scope) {
    return Text(
      _bind(el['text'], scope),
      textAlign: _align(el['align'], fallback: TextAlign.left),
      style: _styled(el, base: const TextStyle(fontSize: 12)),
    );
  }

  Widget _kv(Map<String, dynamic> el, Map<String, dynamic> scope) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Flexible(child: Text(_bind(el['label'], scope))),
        const SizedBox(width: 8),
        Flexible(
            child: Text(_bind(el['value'], scope),
                textAlign: TextAlign.right,
                style: _styled(el, base: const TextStyle(fontSize: 12)))),
      ],
    );
  }

  Widget _section(Map<String, dynamic> el, Map<String, dynamic> scope) {
    final children = <Widget>[];
    void renderOne(Map<String, dynamic> itemScope) {
      final title = _bind(el['title'], itemScope);
      final subtitle = _bind(el['subtitle'], itemScope);
      if (title.isNotEmpty) {
        children.add(Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Text(title,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700)),
        ));
      }
      if (subtitle.isNotEmpty) {
        children.add(Text(subtitle,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 10, color: _mutedInk)));
      }
      final inner = el['elements'];
      if (inner is List) {
        children.addAll(_renderList(inner, itemScope));
      }
    }

    final repeat = el['repeat'];
    if (repeat is String) {
      final items = _lookup(repeat, scope);
      if (items is List) {
        for (final item in items) {
          renderOne(item is Map ? _childScope(item, scope) : scope);
        }
      }
    } else {
      renderOne(scope);
    }
    return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch, children: children);
  }

  Widget _table(Map<String, dynamic> el, Map<String, dynamic> scope) {
    final cols = (el['cols'] as List? ?? const [])
        .whereType<Map>()
        .map((c) => Map<String, dynamic>.from(c))
        .toList();
    if (cols.isEmpty) return const SizedBox.shrink();

    List<dynamic> items = const [];
    final repeat = el['repeat'];
    if (repeat is String) {
      final resolved = _lookup(repeat, scope);
      if (resolved is List) items = resolved;
    }

    TableRow row(Map<String, dynamic> rowScope) => TableRow(
          children: [
            for (var c = 0; c < cols.length; c++)
              Padding(
                padding: EdgeInsets.only(left: c == 0 ? 0 : 8),
                child: Text(
                  _formatCell(cols[c], rowScope),
                  textAlign: _align(cols[c]['align'], fallback: TextAlign.left),
                  style: const TextStyle(fontSize: 12),
                ),
              ),
          ],
        );

    return Table(
      columnWidths: {
        0: const FlexColumnWidth(),
        for (var c = 1; c < cols.length; c++) c: const IntrinsicColumnWidth(),
      },
      defaultVerticalAlignment: TableCellVerticalAlignment.top,
      children: [
        for (final item in items)
          row(item is Map ? _childScope(item, scope) : scope),
      ],
    );
  }

  String _formatCell(Map<String, dynamic> col, Map<String, dynamic> rowScope) {
    final bind = col['bind'];
    if (bind is! String) return '';
    var v = _lookup(bind, rowScope);
    if (v == null) return '';
    final format = col['format'];
    if (format is String && format.isNotEmpty) {
      v = _applyFormatter(v, format);
    }
    return _stringify(v);
  }

  Widget _cells(Map<String, dynamic> el, Map<String, dynamic> scope) {
    List<dynamic> items = const [];
    final repeat = el['repeat'];
    if (repeat is String) {
      final resolved = _lookup(repeat, scope);
      if (resolved is List) items = resolved;
    }
    final bind = el['bind'];
    final emphasis = el['emphasis'] == true;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Wrap(
        alignment: WrapAlignment.center,
        spacing: 6,
        runSpacing: 6,
        children: [
          for (final item in items)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
              decoration: BoxDecoration(
                border: Border.all(color: _ink, width: emphasis ? 1.5 : 1),
              ),
              child: Text(
                bind is String && item is Map
                    ? _stringify(
                        _lookup(bind, Map<String, dynamic>.from(item)) ?? '')
                    : _stringify(item),
                style: TextStyle(
                  fontSize: emphasis ? 15 : 12,
                  fontWeight: emphasis ? FontWeight.w700 : FontWeight.w400,
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _total(Map<String, dynamic> el, Map<String, dynamic> scope) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(_bind(el['label'], scope),
              style:
                  const TextStyle(fontSize: 13, fontWeight: FontWeight.w700)),
          Text(_bind(el['value'], scope),
              style:
                  const TextStyle(fontSize: 13, fontWeight: FontWeight.w700)),
        ],
      ),
    );
  }

  Widget _code(Map<String, dynamic> el, Map<String, dynamic> scope) {
    final label = _bind(el['label'], scope);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        children: [
          if (label.isNotEmpty)
            Text(label,
                style: const TextStyle(fontSize: 10, color: _mutedInk)),
          Text(_bind(el['value'], scope),
              textAlign: TextAlign.center,
              style: const TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 1.5)),
        ],
      ),
    );
  }

  Widget _barcode(Map<String, dynamic> el, Map<String, dynamic> scope) {
    final symbology = (el['symbology'] as String? ?? 'code128').toUpperCase();
    final value = _bind(el['value'], scope);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        children: [
          Container(
            height: 44,
            width: double.infinity,
            decoration: BoxDecoration(border: Border.all(color: _mutedInk)),
            alignment: Alignment.center,
            child: Text(symbology,
                style: const TextStyle(
                    fontSize: 11, letterSpacing: 4, color: _mutedInk)),
          ),
          if (value.isNotEmpty)
            Text(value,
                style: const TextStyle(fontSize: 10, letterSpacing: 2)),
        ],
      ),
    );
  }

  Widget _qr(Map<String, dynamic> el, Map<String, dynamic> scope) {
    final value = _bind(el['value'], scope);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        children: [
          Container(
            height: 84,
            width: 84,
            decoration: BoxDecoration(border: Border.all(color: _mutedInk)),
            alignment: Alignment.center,
            child: const Text('QR',
                style: TextStyle(fontSize: 12, letterSpacing: 2, color: _mutedInk)),
          ),
          if (value.isNotEmpty)
            Text(value,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 9, color: _mutedInk)),
        ],
      ),
    );
  }

  Widget _footer(Map<String, dynamic> el, Map<String, dynamic> scope) {
    final lines = (el['lines'] as List? ?? const [])
        .map((l) => _bind(l, scope))
        .where((l) => l.isNotEmpty)
        .toList();
    return Padding(
      padding: const EdgeInsets.only(top: 6),
      child: Column(
        children: [
          for (final line in lines)
            Text(line,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 10, color: _mutedInk)),
        ],
      ),
    );
  }

  // ---- binding + formatters (lookup-only, no expressions) ---------------

  /// Repeat scopes see the current item first, then the root sample data —
  /// so `{{title}}` inside a section binds the section, while
  /// `{{store.name}}` still reaches the document root.
  Map<String, dynamic> _childScope(Map item, Map<String, dynamic> parent) => {
        ...data,
        ...parent,
        ...Map<String, dynamic>.from(item),
      };

  String _bind(dynamic template, Map<String, dynamic> scope) {
    if (template is! String) return '';
    return template.replaceAllMapped(RegExp(r'\{\{([^}]+)\}\}'), (m) {
      final segments = m.group(1)!.split('|');
      final path = segments.first.trim();
      dynamic v = _lookup(path, scope);
      if (v == null) return '';
      for (final f in segments.skip(1)) {
        v = _applyFormatter(v, f.trim());
      }
      return _stringify(v);
    }).trim();
  }

  dynamic _lookup(String path, Map<String, dynamic> scope) {
    dynamic current = scope;
    for (final part in path.split('.')) {
      if (current is Map) {
        current = current[part];
      } else {
        return null;
      }
    }
    return current;
  }

  dynamic _applyFormatter(dynamic v, String spec) {
    final parts = spec.split(':');
    final name = parts.first.trim();
    final arg = parts.length > 1 ? int.tryParse(parts[1].trim()) ?? 0 : 0;
    switch (name) {
      case 'money':
        return _money(v);
      case 'maskTail':
        return _maskTail(_stringify(v), arg);
      case 'group':
      case 'chunk':
        return _grouped(_stringify(v), arg);
      default:
        return v;
    }
  }

  String _stringify(dynamic v) {
    if (v is List) return v.map(_stringify).join(' ');
    return v?.toString() ?? '';
  }

  String _money(dynamic v) {
    final n = v is num ? v : num.tryParse(v.toString());
    if (n == null) return v.toString();
    final fixed = n.toStringAsFixed(2);
    final dot = fixed.indexOf('.');
    final intPart = fixed.substring(0, dot);
    final buf = StringBuffer();
    final negative = intPart.startsWith('-');
    final digits = negative ? intPart.substring(1) : intPart;
    for (var i = 0; i < digits.length; i++) {
      if (i > 0 && (digits.length - i) % 3 == 0) buf.write(',');
      buf.write(digits[i]);
    }
    return '${negative ? '-' : ''}$buf${fixed.substring(dot)}';
  }

  String _maskTail(String s, int keep) {
    if (keep <= 0 || s.length <= keep) return s;
    return '${'•' * (s.length - keep)}${s.substring(s.length - keep)}';
  }

  String _grouped(String s, int size) {
    if (size <= 0) return s;
    final compact = s.replaceAll(' ', '');
    final buf = StringBuffer();
    for (var i = 0; i < compact.length; i++) {
      if (i > 0 && i % size == 0) buf.write(' ');
      buf.write(compact[i]);
    }
    return buf.toString();
  }

  // ---- style knobs ------------------------------------------------------

  TextStyle _styled(Map<String, dynamic> el, {required TextStyle base}) {
    var style = base;
    final size = el['size'];
    if (size is num) {
      style = style.copyWith(fontSize: (10 + size * 2).toDouble());
    } else if (size == 'small') {
      style = style.copyWith(fontSize: 10);
    } else if (size == 'large') {
      style = style.copyWith(fontSize: 16);
    }
    if (el['bold'] == true) style = style.copyWith(fontWeight: FontWeight.w700);
    if (el['bold'] == false) style = style.copyWith(fontWeight: FontWeight.w400);
    return style;
  }

  TextAlign _align(dynamic v, {required TextAlign fallback}) {
    switch (v) {
      case 'left':
        return TextAlign.left;
      case 'center':
        return TextAlign.center;
      case 'right':
        return TextAlign.right;
      default:
        return fallback;
    }
  }
}

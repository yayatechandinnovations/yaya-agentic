import 'package:barcode_widget/barcode_widget.dart';
import 'package:flutter/material.dart';

import '../../../models/receipts.dart';

/// The printed-receipt renderer (chat-experience-design.md §7.6): a white
/// thermal-paper card with torn edges, monospace "ink", dashed dividers, and
/// real Code-128/QR marks — rendered from the closed element vocabulary bound
/// against instance data. Unknown element types are skipped (forward compat).
class ReceiptPaper extends StatelessWidget {
  const ReceiptPaper({
    super.key,
    required this.template,
    required this.data,
    this.width = 300,
  });

  final ReceiptTemplate template;
  final Map<String, dynamic> data;
  final double width;

  static const _paper = Color(0xFFFDFCF5);
  static const _ink = Color(0xFF262626);
  static const _faded = Color(0xFF5A5A5A);

  @override
  Widget build(BuildContext context) {
    final base = TextStyle(
      fontFamily: 'monospace',
      fontFamilyFallback: const ['Courier New', 'Courier', 'Menlo'],
      color: _ink,
      fontSize: template.paperProfile == '80mm' ? 12 : 11,
      height: 1.35,
    );
    return ClipPath(
      clipper: _TornEdgeClipper(),
      child: Container(
        width: width,
        color: _paper,
        padding: const EdgeInsets.fromLTRB(14, 18, 14, 22),
        child: DefaultTextStyle(
          style: base,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (final el in template.elements) ..._render(el, data),
            ],
          ),
        ),
      ),
    );
  }

  List<Widget> _render(Map<String, dynamic> el, Map<String, dynamic> scope) {
    final type = el['type'];
    switch (type) {
      case 'header':
        return [
          if (bindReceiptText(el['title'] as String?, scope).isNotEmpty)
            Text(bindReceiptText(el['title'] as String?, scope),
                textAlign: TextAlign.center,
                style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14)),
          if (bindReceiptText(el['subtitle'] as String?, scope).isNotEmpty)
            Text(bindReceiptText(el['subtitle'] as String?, scope),
                textAlign: TextAlign.center,
                style: const TextStyle(color: _faded, fontSize: 10)),
          const SizedBox(height: 6),
        ];
      case 'status':
        final text = bindReceiptText(el['text'] as String?, scope);
        if (text.isEmpty) return const [];
        return [
          Container(
            margin: const EdgeInsets.symmetric(vertical: 6),
            padding: const EdgeInsets.symmetric(vertical: 4),
            decoration: BoxDecoration(border: Border.all(color: _ink, width: 2)),
            child: Text(text.toUpperCase(),
                textAlign: TextAlign.center,
                style: const TextStyle(fontWeight: FontWeight.w700, letterSpacing: 4)),
          ),
        ];
      case 'meta':
        return [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Flexible(child: Text(bindReceiptText(el['left'] as String?, scope))),
              Flexible(
                  child: Text(bindReceiptText(el['right'] as String?, scope),
                      textAlign: TextAlign.right)),
            ],
          ),
        ];
      case 'divider':
        return [
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: LayoutBuilder(
              builder: (context, constraints) {
                final n = (constraints.maxWidth / 7).floor();
                return Text('-' * n,
                    maxLines: 1, overflow: TextOverflow.clip,
                    style: const TextStyle(color: _faded));
              },
            ),
          ),
        ];
      case 'title':
        final align = el['align'] == 'left'
            ? TextAlign.left
            : el['align'] == 'right'
                ? TextAlign.right
                : TextAlign.center;
        return [
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Text(bindReceiptText(el['text'] as String?, scope),
                textAlign: align,
                style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
          ),
        ];
      case 'text':
        final text = bindReceiptText(el['text'] as String?, scope);
        // Unbound text (a hash on a plain sale, an optional note) prints nothing.
        return text.isEmpty ? const [] : [Text(text)];
      case 'kv':
        final value = bindReceiptText(el['value'] as String?, scope);
        // A key/value line whose value is unbound prints nothing — the label
        // never leaks on its own (matches the printer omitting the whole line).
        if (value.isEmpty) return const [];
        return [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(bindReceiptText(el['label'] as String?, scope),
                  style: const TextStyle(color: _faded)),
              Flexible(
                  child: Text(value,
                      textAlign: TextAlign.right,
                      style: const TextStyle(fontWeight: FontWeight.w600))),
            ],
          ),
        ];
      case 'section':
        final rows = bindReceiptRows(el['repeat'] as String?, scope);
        final out = <Widget>[];
        for (final row in rows) {
          final title = bindReceiptText(el['title'] as String?, row);
          final subtitle = bindReceiptText(el['subtitle'] as String?, row);
          if (title.isNotEmpty) {
            out.add(Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(title,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontWeight: FontWeight.w700)),
            ));
          }
          if (subtitle.isNotEmpty) {
            out.add(Text(subtitle,
                textAlign: TextAlign.center,
                style: const TextStyle(color: _faded, fontSize: 10)));
          }
          for (final child in (el['elements'] as List? ?? const [])) {
            if (child is Map) {
              out.addAll(_render(Map<String, dynamic>.from(child), row));
            }
          }
        }
        return out;
      case 'table':
        final rows = bindReceiptRows(el['repeat'] as String?, scope);
        final cols = [
          for (final c in (el['cols'] as List? ?? const []))
            if (c is Map) Map<String, dynamic>.from(c),
        ];
        if (rows.isEmpty || cols.isEmpty) return const [];
        return [
          for (final row in rows)
            Row(
              children: [
                for (var i = 0; i < cols.length; i++)
                  Expanded(
                    flex: i == 0 ? 3 : 2,
                    child: Text(
                      _cellText(cols[i], row),
                      textAlign: switch (cols[i]['align']) {
                        'right' => TextAlign.right,
                        'center' => TextAlign.center,
                        _ => TextAlign.left,
                      },
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
              ],
            ),
        ];
      case 'cells':
        final values = bindReceiptScalars(
            el['repeat'] as String?, el['bind'] as String?, scope);
        if (values.isEmpty) return const [];
        return [
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Wrap(
              alignment: WrapAlignment.center,
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final v in values)
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
                    decoration: BoxDecoration(
                        border: Border.all(color: _ink, width: 1.5),
                        borderRadius: BorderRadius.circular(4)),
                    child: Text(v,
                        style: const TextStyle(
                            fontWeight: FontWeight.w700, fontSize: 14)),
                  ),
              ],
            ),
          ),
        ];
      case 'total':
        return [
          const SizedBox(height: 4),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(bindReceiptText(el['label'] as String?, scope),
                  style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
              Text(bindReceiptText(el['value'] as String?, scope),
                  style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
            ],
          ),
        ];
      case 'code':
        final value = bindReceiptText(el['value'] as String?, scope);
        if (value.isEmpty) return const [];
        return [
          const SizedBox(height: 6),
          Text(bindReceiptText(el['label'] as String?, scope),
              textAlign: TextAlign.center, style: const TextStyle(color: _faded)),
          Text(value,
              textAlign: TextAlign.center,
              style: const TextStyle(
                  fontWeight: FontWeight.w800, fontSize: 18, letterSpacing: 2)),
        ];
      case 'barcode':
        final value = bindReceiptText(el['value'] as String?, scope);
        if (value.isEmpty) return const [];
        return [
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: BarcodeWidget(
              barcode: Barcode.code128(),
              data: value,
              color: _ink,
              backgroundColor: Colors.transparent,
              height: 46,
              drawText: true,
              style: const TextStyle(
                  fontFamily: 'monospace', fontSize: 9, color: _ink),
            ),
          ),
        ];
      case 'qr':
        final value = bindReceiptText(el['value'] as String?, scope);
        if (value.isEmpty) return const [];
        return [
          Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: BarcodeWidget(
                barcode: Barcode.qrCode(),
                data: value,
                color: _ink,
                backgroundColor: Colors.transparent,
                width: 96,
                height: 96,
              ),
            ),
          ),
        ];
      case 'footer':
        return [
          const SizedBox(height: 6),
          for (final line in (el['lines'] as List? ?? const []))
            if (bindReceiptText(line?.toString(), scope).isNotEmpty)
              Text(bindReceiptText(line?.toString(), scope),
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: _faded, fontSize: 10)),
        ];
      case 'spacer':
        return [const SizedBox(height: 10)];
      default:
        return const []; // unknown types are skipped — forward compatibility
    }
  }

  static String _cellText(Map<String, dynamic> col, Map<String, dynamic> row) {
    final bind = col['bind'];
    if (bind == null) return '';
    final value = row[bind];
    // Reuse the shared formatter so a table 'money' column trims trailing
    // zeros identically to a {{x | money}} binding (matches formatTicketMoney).
    return applyReceiptFormat(value, col['format'] as String?);
  }
}

/// Zigzag torn top + bottom edges — the paper was ripped off a roll.
class _TornEdgeClipper extends CustomClipper<Path> {
  static const _tooth = 8.0;
  static const _depth = 6.0;

  @override
  Path getClip(Size size) {
    final path = Path()..moveTo(0, _depth);
    var x = 0.0;
    var up = true;
    while (x < size.width) {
      x += _tooth;
      path.lineTo(x.clamp(0, size.width), up ? 0 : _depth);
      up = !up;
    }
    path.lineTo(size.width, size.height - _depth);
    up = true;
    while (x > 0) {
      x -= _tooth;
      path.lineTo(x.clamp(0, size.width), up ? size.height : size.height - _depth);
      up = !up;
    }
    path.close();
    return path;
  }

  @override
  bool shouldReclip(covariant CustomClipper<Path> oldClipper) => false;
}

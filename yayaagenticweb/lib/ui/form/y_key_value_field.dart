import 'package:flutter/material.dart';

import '../tokens.dart';
import 'y_fields.dart';
import 'y_form_controller.dart';

/// Header-style key/value rows: add, remove, duplicate-key and empty-key
/// errors per row. Emits the map on every edit.
class YKeyValueField extends StatefulWidget {
  const YKeyValueField({
    super.key,
    required this.label,
    required this.value,
    required this.onChanged,
    this.form,
    this.name,
    this.optional = true,
    this.wireKey,
    this.helper,
    this.keyHint = 'Name',
    this.valueHint = 'Value',
    this.addLabel = 'Add row',
    this.caseInsensitiveKeys = false,
  });

  final String label;
  final Map<String, String> value;
  final ValueChanged<Map<String, String>> onChanged;
  final YFormController? form;
  final String? name;
  final bool optional;
  final String? wireKey;
  final String? helper;
  final String keyHint;
  final String valueHint;
  final String addLabel;

  /// HTTP header names collide regardless of case.
  final bool caseInsensitiveKeys;

  /// Duplicate or blank-with-value keys in [rows] — the same rule the field shows.
  static String? validate(Iterable<MapEntry<String, String>> rows, {bool caseInsensitive = false}) {
    final seen = <String>{};
    for (final r in rows) {
      final k = caseInsensitive ? r.key.trim().toLowerCase() : r.key.trim();
      if (k.isEmpty && r.value.trim().isNotEmpty) return 'Every row with a value needs a name.';
      if (k.isEmpty) continue;
      if (!seen.add(k)) return 'Two rows share the name “${r.key.trim()}”.';
    }
    return null;
  }

  @override
  State<YKeyValueField> createState() => _YKeyValueFieldState();
}

class _Row {
  _Row(String k, String v)
      : key = TextEditingController(text: k),
        value = TextEditingController(text: v);
  final TextEditingController key;
  final TextEditingController value;
  void dispose() {
    key.dispose();
    value.dispose();
  }
}

class _YKeyValueFieldState extends State<YKeyValueField> {
  final List<_Row> _rows = [];

  @override
  void initState() {
    super.initState();
    _load(widget.value);
  }

  @override
  void didUpdateWidget(YKeyValueField old) {
    super.didUpdateWidget(old);
    if (!_sameAsRows(widget.value)) _load(widget.value);
  }

  bool _sameAsRows(Map<String, String> m) {
    final current = _emit();
    if (current.length != m.length) return false;
    for (final e in m.entries) {
      if (current[e.key] != e.value) return false;
    }
    return true;
  }

  void _load(Map<String, String> m) {
    for (final r in _rows) {
      r.dispose();
    }
    _rows
      ..clear()
      ..addAll(m.entries.map((e) => _Row(e.key, e.value)));
  }

  Map<String, String> _emit() => {
        for (final r in _rows)
          if (r.key.text.trim().isNotEmpty) r.key.text.trim(): r.value.text,
      };

  void _changed() {
    if (widget.form != null && widget.name != null) widget.form!.changed(widget.name!);
    widget.onChanged(_emit());
    setState(() {});
  }

  @override
  void dispose() {
    for (final r in _rows) {
      r.dispose();
    }
    super.dispose();
  }

  String _norm(String k) => widget.caseInsensitiveKeys ? k.trim().toLowerCase() : k.trim();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final counts = <String, int>{};
    for (final r in _rows) {
      final k = _norm(r.key.text);
      if (k.isNotEmpty) counts[k] = (counts[k] ?? 0) + 1;
    }
    final formErr = widget.form != null && widget.name != null ? widget.form!.errorFor(widget.name!) : null;
    return YFieldShell(
      label: widget.label,
      form: widget.form,
      name: widget.name,
      optional: widget.optional,
      wireKey: widget.wireKey,
      helper: widget.helper,
      error: formErr,
      child: Container(
        decoration: BoxDecoration(
          border: Border.all(color: scheme.outlineVariant),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          for (var i = 0; i < _rows.length; i++)
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 8, 4, 0),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Expanded(
                  flex: 2,
                  child: TextField(
                    controller: _rows[i].key,
                    style: monoStyle(context, size: 13),
                    onChanged: (_) => _changed(),
                    decoration: InputDecoration(
                      hintText: widget.keyHint,
                      errorText: (counts[_norm(_rows[i].key.text)] ?? 0) > 1 ? 'Duplicate name' : null,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  flex: 3,
                  child: TextField(
                    controller: _rows[i].value,
                    style: monoStyle(context, size: 13),
                    onChanged: (_) => _changed(),
                    decoration: InputDecoration(hintText: widget.valueHint),
                  ),
                ),
                IconButton(
                  tooltip: 'Remove row',
                  icon: const Icon(Icons.close, size: 18),
                  onPressed: () {
                    _rows.removeAt(i).dispose();
                    _changed();
                  },
                ),
              ]),
            ),
          Align(
            alignment: Alignment.centerLeft,
            child: Padding(
              padding: const EdgeInsets.all(4),
              child: TextButton.icon(
                onPressed: () {
                  _rows.add(_Row('', ''));
                  setState(() {});
                },
                icon: const Icon(Icons.add, size: 18),
                label: Text(widget.addLabel),
              ),
            ),
          ),
        ]),
      ),
    );
  }
}

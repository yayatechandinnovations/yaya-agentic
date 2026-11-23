import 'dart:convert';

import 'package:flutter/material.dart';

import '../tokens.dart';
import 'y_fields.dart';
import 'y_form_controller.dart';

/// JSON editor: monospace, live parse status, Format. With [schemaAware] it
/// also checks the value looks like a JSON Schema (has `type`) and reports
/// its property names via [onProperties] so other fields can offer them.
class YJsonField extends StatefulWidget {
  const YJsonField({
    super.key,
    required this.label,
    required this.controller,
    this.form,
    this.name,
    this.required = false,
    this.optional = false,
    this.wireKey,
    this.helper,
    this.schemaAware = false,
    this.minLines = 6,
    this.onProperties,
    this.onChanged,
  });

  final String label;
  final TextEditingController controller;
  final YFormController? form;
  final String? name;
  final bool required;
  final bool optional;
  final String? wireKey;
  final String? helper;
  final bool schemaAware;
  final int minLines;
  final ValueChanged<List<String>>? onProperties;
  final VoidCallback? onChanged;

  @override
  State<YJsonField> createState() => _YJsonFieldState();
}

class _YJsonFieldState extends State<YJsonField> {
  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onText);
  }

  @override
  void didUpdateWidget(YJsonField old) {
    super.didUpdateWidget(old);
    if (old.controller != widget.controller) {
      old.controller.removeListener(_onText);
      widget.controller.addListener(_onText);
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onText);
    super.dispose();
  }

  void _onText() => setState(() {});

  ({String text, bool ok, Map<String, dynamic>? value}) _status() {
    final raw = widget.controller.text;
    if (raw.trim().isEmpty) {
      return (text: widget.optional ? 'Empty' : 'Enter a JSON object', ok: widget.optional, value: null);
    }
    final (err, value) = Validators.jsonObject(raw);
    if (err != null) return (text: err, ok: false, value: null);
    if (!widget.schemaAware) return (text: 'Valid JSON', ok: true, value: value);
    final props = value!['properties'];
    final n = props is Map ? props.length : 0;
    if (value['type'] == null) {
      return (text: 'Valid JSON · add "type": "object" so the model knows the shape', ok: true, value: value);
    }
    return (text: 'Valid JSON Schema · $n ${n == 1 ? 'property' : 'properties'}', ok: true, value: value);
  }

  void _format() {
    final (err, value) = Validators.jsonObject(widget.controller.text);
    if (err != null || value == null) {
      if (widget.form != null && widget.name != null) widget.form!.touch(widget.name!);
      return;
    }
    widget.controller.text = const JsonEncoder.withIndent('  ').convert(value);
    if (widget.form != null && widget.name != null) widget.form!.changed(widget.name!);
    widget.onChanged?.call();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    final st = _status();
    final err = widget.form != null && widget.name != null ? widget.form!.errorFor(widget.name!) : null;
    if (widget.onProperties != null) {
      final props = st.value?['properties'];
      final names = props is Map ? props.keys.map((k) => '$k').toList() : <String>[];
      WidgetsBinding.instance.addPostFrameCallback((_) => widget.onProperties?.call(names));
    }
    final borderColor = err != null ? scheme.error : status.inputBorder;
    return YFieldShell(
      label: widget.label,
      form: widget.form,
      name: widget.name,
      required: widget.required,
      optional: widget.optional,
      wireKey: widget.wireKey,
      helper: widget.helper,
      error: err,
      child: Container(
        decoration: BoxDecoration(
          color: scheme.surface,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: borderColor, width: err != null ? 2 : 1),
        ),
        clipBehavior: Clip.antiAlias,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Container(
            color: scheme.surfaceContainerLow,
            padding: const EdgeInsets.fromLTRB(12, 2, 4, 2),
            child: Row(children: [
              Icon(st.ok ? Icons.check_circle_outline : Icons.error_outline,
                  size: 16, color: st.ok ? status.onSuccessContainer : scheme.error),
              const SizedBox(width: 6),
              Expanded(
                child: Text(st.text,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w500,
                        color: st.ok ? status.onSuccessContainer : scheme.error)),
              ),
              TextButton(onPressed: _format, child: const Text('Format')),
            ]),
          ),
          Divider(height: 1, color: scheme.outlineVariant),
          TextField(
            controller: widget.controller,
            minLines: widget.minLines,
            maxLines: 24,
            keyboardType: TextInputType.multiline,
            style: monoStyle(context, size: 13),
            onChanged: (_) {
              if (widget.form != null && widget.name != null) widget.form!.changed(widget.name!);
              widget.onChanged?.call();
            },
            decoration: const InputDecoration(
              border: InputBorder.none,
              enabledBorder: InputBorder.none,
              focusedBorder: InputBorder.none,
              filled: false,
              contentPadding: EdgeInsets.all(12),
            ),
          ),
        ]),
      ),
    );
  }
}

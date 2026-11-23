import 'package:flutter/material.dart';

import '../tokens.dart';
import 'y_fields.dart';
import 'y_form_controller.dart';

class YResource {
  const YResource({required this.id, this.name, this.meta, this.disabledReason});

  final String id;

  /// Human name when the resource has one; the id is shown in mono beside it.
  final String? name;

  /// e.g. "OIDC · 3 profiles", "v4".
  final String? meta;

  /// Present = listed but not selectable (archived, wrong type), with the reason.
  final String? disabledReason;
}

/// Searchable picker over resources that exist, with an optional
/// "Create new…" row. Replaces free-text id fields and bare dropdowns.
class YResourcePicker extends StatelessWidget {
  const YResourcePicker({
    super.key,
    required this.label,
    required this.resources,
    required this.value,
    required this.onChanged,
    this.form,
    this.name,
    this.required = false,
    this.optional = false,
    this.wireKey,
    this.helper,
    this.hint,
    this.noneLabel,
    this.onCreate,
    this.createLabel,
    this.loading = false,
  });

  final String label;
  final List<YResource> resources;
  final String? value;
  final ValueChanged<String?> onChanged;
  final YFormController? form;
  final String? name;
  final bool required;
  final bool optional;
  final String? wireKey;
  final String? helper;
  final String? hint;

  /// Offer an explicit "none" row (for optional references).
  final String? noneLabel;

  /// Opens a create flow and returns the new id (selected on return), or null.
  final Future<String?> Function()? onCreate;
  final String? createLabel;
  final bool loading;

  static const _none = '\u0000none';
  static const _create = '\u0000create';

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final err = form != null && name != null ? form!.errorFor(name!) : null;
    final known = resources.any((r) => r.id == value);
    final entries = <DropdownMenuEntry<String>>[
      if (noneLabel != null)
        DropdownMenuEntry(value: _none, label: noneLabel!, labelWidget: Text(noneLabel!, style: TextStyle(color: scheme.onSurfaceVariant))),
      for (final r in resources)
        DropdownMenuEntry(
          value: r.id,
          label: r.name == null ? r.id : '${r.name} (${r.id})',
          enabled: r.disabledReason == null,
          labelWidget: _ResourceRow(r),
        ),
      if (value != null && value!.isNotEmpty && !known)
        DropdownMenuEntry(
          value: value!,
          label: value!,
          labelWidget: Text('$value — not found on this tenant', style: TextStyle(color: scheme.error)),
        ),
      if (onCreate != null)
        DropdownMenuEntry(
          value: _create,
          label: createLabel ?? 'Create new…',
          leadingIcon: Icon(Icons.add, color: scheme.primary, size: 18),
          labelWidget: Text(createLabel ?? 'Create new…', style: TextStyle(color: scheme.primary, fontWeight: FontWeight.w500)),
        ),
    ];
    return YFieldShell(
      label: label,
      form: form,
      name: name,
      required: required,
      optional: optional,
      wireKey: wireKey,
      helper: helper,
      error: err ?? (!known && value != null && value!.isNotEmpty && !loading ? '“$value” doesn’t exist on this tenant.' : null),
      child: LayoutBuilder(
        builder: (context, c) => DropdownMenu<String>(
          key: ValueKey(value),
          width: c.maxWidth,
          initialSelection: value == null || value!.isEmpty ? (noneLabel != null ? _none : null) : value,
          enableFilter: true,
          enableSearch: true,
          requestFocusOnTap: true,
          menuHeight: 360,
          hintText: loading ? 'Loading…' : (hint ?? 'Search…'),
          leadingIcon: const Icon(Icons.search, size: 18),
          textStyle: const TextStyle(fontSize: 14),
          inputDecorationTheme: Theme.of(context).inputDecorationTheme.copyWith(
                errorBorder: Theme.of(context).inputDecorationTheme.errorBorder,
              ),
          errorText: err == null ? null : '',
          dropdownMenuEntries: entries,
          onSelected: (v) async {
            if (form != null && name != null) {
              form!.touch(name!);
              form!.changed(name!);
            }
            if (v == _create) {
              final created = await onCreate?.call();
              onChanged(created ?? value);
              return;
            }
            onChanged(v == _none ? null : v);
          },
        ),
      ),
    );
  }
}

class _ResourceRow extends StatelessWidget {
  const _ResourceRow(this.r);
  final YResource r;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 560),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        if (r.name != null) ...[
          Flexible(child: Text(r.name!, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600))),
          const SizedBox(width: 8),
        ],
        Flexible(
          child: Text(r.id,
              overflow: TextOverflow.ellipsis,
              style: monoStyle(context, size: 12, color: r.name == null ? scheme.onSurface : scheme.onSurfaceVariant)),
        ),
        if (r.meta != null || r.disabledReason != null) ...[
          const SizedBox(width: 12),
          Flexible(
            child: Text(r.disabledReason ?? r.meta!,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 12, color: r.disabledReason != null ? scheme.error : scheme.onSurfaceVariant)),
          ),
        ],
      ]),
    );
  }
}

/// Multi-select over existing resources: chips for what's chosen, type to add.
class YChipsField extends StatefulWidget {
  const YChipsField({
    super.key,
    required this.label,
    required this.resources,
    required this.selected,
    required this.onChanged,
    this.form,
    this.name,
    this.required = false,
    this.optional = false,
    this.wireKey,
    this.helper,
    this.hint = 'Type to add…',
  });

  final String label;
  final List<YResource> resources;
  final List<String> selected;
  final ValueChanged<List<String>> onChanged;
  final YFormController? form;
  final String? name;
  final bool required;
  final bool optional;
  final String? wireKey;
  final String? helper;
  final String hint;

  @override
  State<YChipsField> createState() => _YChipsFieldState();
}

class _YChipsFieldState extends State<YChipsField> {
  final _text = TextEditingController();
  final _focus = FocusNode();

  @override
  void dispose() {
    _text.dispose();
    _focus.dispose();
    super.dispose();
  }

  void _set(List<String> next) {
    if (widget.form != null && widget.name != null) {
      widget.form!.touch(widget.name!);
      widget.form!.changed(widget.name!);
    }
    widget.onChanged(next);
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    final err = widget.form != null && widget.name != null ? widget.form!.errorFor(widget.name!) : null;
    final byId = {for (final r in widget.resources) r.id: r};
    final available = widget.resources.where((r) => !widget.selected.contains(r.id) && r.disabledReason == null).toList();
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
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: scheme.surface,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: err != null ? scheme.error : status.inputBorder, width: err != null ? 2 : 1),
        ),
        child: Wrap(spacing: 8, runSpacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
          for (final id in widget.selected)
            InputChip(
              label: Text.rich(TextSpan(children: [
                TextSpan(text: id, style: monoStyle(context, size: 12.5, weight: FontWeight.w500)),
                if (byId[id]?.meta != null)
                  TextSpan(text: '  ${byId[id]!.meta}', style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant)),
                if (byId[id] == null)
                  TextSpan(text: '  not found', style: TextStyle(fontSize: 12, color: scheme.error)),
              ])),
              side: BorderSide(color: byId[id] == null || byId[id]!.disabledReason != null ? scheme.error : status.inputBorder),
              onDeleted: () => _set([...widget.selected]..remove(id)),
              deleteButtonTooltipMessage: 'Remove $id',
            ),
          ConstrainedBox(
            constraints: const BoxConstraints(minWidth: 180, maxWidth: 320),
            child: RawAutocomplete<YResource>(
              textEditingController: _text,
              focusNode: _focus,
              displayStringForOption: (r) => r.id,
              optionsBuilder: (v) {
                final q = v.text.trim().toLowerCase();
                return available.where((r) => q.isEmpty || r.id.toLowerCase().contains(q) || (r.name ?? '').toLowerCase().contains(q));
              },
              onSelected: (r) {
                _text.clear();
                _set([...widget.selected, r.id]);
                _focus.requestFocus();
              },
              fieldViewBuilder: (context, ctrl, focus, onSubmit) => TextField(
                controller: ctrl,
                focusNode: focus,
                onSubmitted: (_) => onSubmit(),
                style: const TextStyle(fontSize: 14),
                decoration: InputDecoration(
                  hintText: available.isEmpty ? 'All added' : widget.hint,
                  border: InputBorder.none,
                  enabledBorder: InputBorder.none,
                  focusedBorder: InputBorder.none,
                  filled: false,
                  isDense: true,
                  contentPadding: const EdgeInsets.symmetric(horizontal: 4, vertical: 8),
                ),
              ),
              optionsViewBuilder: (context, onSelected, options) => Align(
                alignment: Alignment.topLeft,
                child: Material(
                  elevation: 6,
                  borderRadius: BorderRadius.circular(10),
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxHeight: 320, maxWidth: 480),
                    child: ListView(
                      padding: const EdgeInsets.symmetric(vertical: 6),
                      shrinkWrap: true,
                      children: [
                        for (final r in options)
                          InkWell(
                            onTap: () => onSelected(r),
                            child: Padding(
                              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                              child: _ResourceRow(r),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ]),
      ),
    );
  }
}

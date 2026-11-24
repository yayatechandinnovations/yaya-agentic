import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../tokens.dart';
import 'y_form_controller.dart';

/// Label row above every input: human label, required/optional mark, and the
/// API field name as a mono chip so operators who script the API can map 1:1.
class YFieldLabel extends StatelessWidget {
  const YFieldLabel({
    super.key,
    required this.label,
    this.required = false,
    this.optional = false,
    this.wireKey,
    this.trailing,
  });

  final String label;
  final bool required;
  final bool optional;
  final String? wireKey;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(crossAxisAlignment: CrossAxisAlignment.center, children: [
        Expanded(
          child: Row(children: [
            Flexible(
              child: Semantics(
                label: '$label${required ? ', required' : optional ? ', optional' : ''}',
                excludeSemantics: true,
                child: Text.rich(
                  TextSpan(children: [
                    TextSpan(text: label),
                    if (required) TextSpan(text: ' *', style: TextStyle(color: scheme.error)),
                    if (optional)
                      TextSpan(
                        text: '  Optional',
                        style: TextStyle(fontWeight: FontWeight.w400, fontSize: 13, color: scheme.onSurfaceVariant),
                      ),
                  ]),
                  style: TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: scheme.onSurface),
                ),
              ),
            ),
            if (trailing != null) ...[const SizedBox(width: 8), trailing!],
          ]),
        ),
        if (wireKey != null) ...[const SizedBox(width: 8), YWireKey(wireKey!)],
      ]),
    );
  }
}

class YWireKey extends StatelessWidget {
  const YWireKey(this.text, {super.key});
  final String text;

  @override
  Widget build(BuildContext context) {
    final s = StatusColors.of(context);
    return Tooltip(
      message: 'API field',
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        decoration: BoxDecoration(color: s.neutralContainer, borderRadius: BorderRadius.circular(4)),
        child: Text(text, style: monoStyle(context, size: 12, weight: FontWeight.w500, color: s.onNeutralContainer)),
      ),
    );
  }
}

/// Helper text, or the error in its place.
class YFieldMessage extends StatelessWidget {
  const YFieldMessage({super.key, this.helper, this.error});
  final String? helper;
  final String? error;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    if (error != null) {
      return Padding(
        padding: const EdgeInsets.only(top: 6),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Padding(
            padding: const EdgeInsets.only(top: 1),
            child: Icon(Icons.error_outline, size: 16, color: scheme.error),
          ),
          const SizedBox(width: 6),
          Expanded(
            child: Text(error!,
                style: TextStyle(fontSize: 13, height: 1.45, fontWeight: FontWeight.w500, color: scheme.error)),
          ),
        ]),
      );
    }
    if (helper == null) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 6),
      child: Text(helper!, style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant)),
    );
  }
}

/// Label + any input + helper/error, keyed so the error summary can scroll to it.
class YFieldShell extends StatelessWidget {
  const YFieldShell({
    super.key,
    required this.label,
    required this.child,
    this.form,
    this.name,
    this.required = false,
    this.optional = false,
    this.wireKey,
    this.helper,
    this.error,
    this.labelTrailing,
  });

  final String label;
  final Widget child;
  final YFormController? form;
  final String? name;
  final bool required;
  final bool optional;
  final String? wireKey;
  final String? helper;
  final String? error;
  final Widget? labelTrailing;

  @override
  Widget build(BuildContext context) {
    final err = error ?? (form != null && name != null ? form!.errorFor(name!) : null);
    return KeyedSubtree(
      key: form != null && name != null ? form!.keyFor(name!) : null,
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
        YFieldLabel(label: label, required: required, optional: optional, wireKey: wireKey, trailing: labelTrailing),
        child,
        YFieldMessage(helper: helper, error: err),
      ]),
    );
  }
}

class YTextField extends StatefulWidget {
  const YTextField({
    super.key,
    required this.label,
    required this.controller,
    this.form,
    this.name,
    this.required = false,
    this.optional = false,
    this.wireKey,
    this.helper,
    this.hint,
    this.error,
    this.mono = false,
    this.maxLines = 1,
    this.minLines,
    this.readOnly = false,
    this.enabled = true,
    this.autofocus = false,
    this.obscure = false,
    this.keyboardType,
    this.inputFormatters,
    this.onChanged,
    this.onSubmitted,
    this.prefix,
    this.suffix,
    this.labelTrailing,
  });

  final String label;
  final TextEditingController controller;
  final YFormController? form;
  final String? name;
  final bool required;
  final bool optional;
  final String? wireKey;
  final String? helper;

  /// An example value — never the instruction (that's [helper]).
  final String? hint;
  final String? error;
  final bool mono;
  final int maxLines;
  final int? minLines;
  final bool readOnly;
  final bool enabled;
  final bool autofocus;
  final bool obscure;
  final TextInputType? keyboardType;
  final List<TextInputFormatter>? inputFormatters;
  final ValueChanged<String>? onChanged;
  final ValueChanged<String>? onSubmitted;
  final Widget? prefix;
  final Widget? suffix;
  final Widget? labelTrailing;

  @override
  State<YTextField> createState() => _YTextFieldState();
}

class _YTextFieldState extends State<YTextField> {
  final _focus = FocusNode();

  // Leaving a field you never typed in isn't a mistake worth flagging; a
  // submit attempt still reveals every error.
  bool _edited = false;

  @override
  void initState() {
    super.initState();
    _focus.addListener(() {
      if (!_focus.hasFocus && _edited && widget.form != null && widget.name != null) widget.form!.touch(widget.name!);
    });
  }

  @override
  void dispose() {
    _focus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final err = widget.error ?? (widget.form != null && widget.name != null ? widget.form!.errorFor(widget.name!) : null);
    final style = widget.mono
        ? monoStyle(context, size: 13)
        : TextStyle(fontSize: 14, color: widget.readOnly ? scheme.onSurfaceVariant : scheme.onSurface);
    return YFieldShell(
      label: widget.label,
      form: widget.form,
      name: widget.name,
      required: widget.required,
      optional: widget.optional,
      wireKey: widget.wireKey,
      helper: widget.helper,
      error: err,
      labelTrailing: widget.labelTrailing,
      child: TextField(
        controller: widget.controller,
        focusNode: _focus,
        readOnly: widget.readOnly,
        enabled: widget.enabled,
        autofocus: widget.autofocus,
        obscureText: widget.obscure,
        maxLines: widget.obscure ? 1 : widget.maxLines,
        minLines: widget.minLines,
        keyboardType: widget.keyboardType ?? (widget.maxLines > 1 ? TextInputType.multiline : null),
        inputFormatters: widget.inputFormatters,
        style: style,
        onChanged: (v) {
          _edited = true;
          if (widget.form != null && widget.name != null) widget.form!.changed(widget.name!);
          widget.onChanged?.call(v);
        },
        onSubmitted: widget.onSubmitted,
        decoration: InputDecoration(
          hintText: widget.hint,
          hintStyle: widget.mono ? monoStyle(context, size: 13, color: scheme.outline) : null,
          fillColor: widget.readOnly ? scheme.surfaceContainer : null,
          // The message renders below via YFieldMessage; this only paints the border.
          errorText: err == null ? null : '',
          errorStyle: const TextStyle(height: 0, fontSize: 0),
          prefixIcon: widget.prefix,
          suffixIcon: widget.suffix,
          isDense: false,
        ),
      ),
    );
  }
}

class YOption<T> {
  const YOption(this.value, this.label, {this.description, this.meta, this.enabled = true});
  final T value;
  final String label;
  final String? description;
  final String? meta;
  final bool enabled;
}

class YSelectField<T> extends StatelessWidget {
  const YSelectField({
    super.key,
    required this.label,
    required this.options,
    required this.value,
    required this.onChanged,
    this.form,
    this.name,
    this.required = false,
    this.optional = false,
    this.wireKey,
    this.helper,
    this.hint,
    this.mono = false,
  });

  final String label;
  final List<YOption<T>> options;
  final T? value;
  final ValueChanged<T?> onChanged;
  final YFormController? form;
  final String? name;
  final bool required;
  final bool optional;
  final String? wireKey;
  final String? helper;
  final String? hint;
  final bool mono;

  @override
  Widget build(BuildContext context) {
    final err = form != null && name != null ? form!.errorFor(name!) : null;
    final hasValue = options.any((o) => o.value == value);
    return YFieldShell(
      label: label,
      form: form,
      name: name,
      required: required,
      optional: optional,
      wireKey: wireKey,
      helper: helper,
      error: err,
      child: DropdownButtonFormField<T>(
        // initialValue is read once; key on the value so an external change
        // (loading a record, restoring a draft) shows up.
        key: ValueKey<Object?>(hasValue ? value : null),
        initialValue: hasValue ? value : null,
        isExpanded: true,
        hint: hint == null ? null : Text(hint!),
        style: mono ? monoStyle(context) : TextStyle(fontSize: 14, color: Theme.of(context).colorScheme.onSurface),
        decoration: InputDecoration(errorText: err == null ? null : '', errorStyle: const TextStyle(height: 0, fontSize: 0)),
        items: [
          for (final o in options)
            DropdownMenuItem<T>(
              value: o.value,
              enabled: o.enabled,
              child: Row(children: [
                Flexible(child: Text(o.label, overflow: TextOverflow.ellipsis)),
                if (o.meta != null) ...[
                  const SizedBox(width: 8),
                  Text(o.meta!, style: TextStyle(fontSize: 12, color: Theme.of(context).colorScheme.onSurfaceVariant)),
                ],
              ]),
            ),
        ],
        onChanged: (v) {
          if (form != null && name != null) {
            form!.touch(name!);
            form!.changed(name!);
          }
          onChanged(v);
        },
      ),
    );
  }
}

/// A switch with a title, a description, and — when a rule rules it out — a
/// disabled state that says why, instead of letting the server refuse the save.
class YSwitchField extends StatelessWidget {
  const YSwitchField({
    super.key,
    required this.title,
    required this.value,
    required this.onChanged,
    this.description,
    this.wireKey,
    this.disabledReason,
  });

  final String title;
  final String? description;
  final String? wireKey;
  final bool value;
  final ValueChanged<bool> onChanged;
  final String? disabledReason;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    final disabled = disabledReason != null;
    return MergeSemantics(
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: disabled ? null : () => onChanged(!value),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Switch(value: value && !disabled, onChanged: disabled ? null : onChanged),
            const SizedBox(width: 12),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const SizedBox(height: 6),
                Wrap(spacing: 8, runSpacing: 4, crossAxisAlignment: WrapCrossAlignment.center, children: [
                  Text(title,
                      style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          color: disabled ? scheme.onSurfaceVariant : scheme.onSurface)),
                  if (wireKey != null) YWireKey(wireKey!),
                ]),
                if (description != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Text(description!,
                        style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant)),
                  ),
                if (disabled)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Text(disabledReason!,
                        style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: status.onWarningContainer)),
                  ),
              ]),
            ),
          ]),
        ),
      ),
    );
  }
}

class YEnumOption<T> {
  const YEnumOption(this.value, this.title, {this.description, this.wire, this.caution = false, this.enabled = true});
  final T value;
  final String title;
  final String? description;
  final String? wire;

  /// A choice with a security or cost consequence — selected, it reads amber.
  final bool caution;

  /// Shown dimmed and not selectable (the field's helper says why).
  final bool enabled;
}

/// Radio cards for consequential choices: each option spells out its effect.
class YEnumCards<T> extends StatelessWidget {
  const YEnumCards({
    super.key,
    required this.label,
    required this.options,
    required this.value,
    required this.onChanged,
    this.form,
    this.name,
    this.required = false,
    this.wireKey,
    this.helper,
    this.horizontal = false,
  });

  final String label;
  final List<YEnumOption<T>> options;
  final T value;
  final ValueChanged<T> onChanged;
  final YFormController? form;
  final String? name;
  final bool required;
  final String? wireKey;
  final String? helper;
  final bool horizontal;

  @override
  Widget build(BuildContext context) {
    final cards = [for (final o in options) _card(context, o)];
    return YFieldShell(
      label: label,
      form: form,
      name: name,
      required: required,
      wireKey: wireKey,
      helper: helper,
      child: horizontal
          ? LayoutBuilder(builder: (context, c) {
              if (c.maxWidth < 480) return Column(children: _spaced(cards));
              return IntrinsicHeight(
                child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  for (var i = 0; i < cards.length; i++) ...[
                    if (i > 0) const SizedBox(width: 10),
                    Expanded(child: cards[i]),
                  ],
                ]),
              );
            })
          : Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: _spaced(cards)),
    );
  }

  List<Widget> _spaced(List<Widget> cards) => [
        for (var i = 0; i < cards.length; i++) ...[if (i > 0) const SizedBox(height: 8), cards[i]],
      ];

  Widget _card(BuildContext context, YEnumOption<T> o) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    final selected = o.value == value;
    final accent = o.caution ? status.warning : scheme.primary;
    return Semantics(
      inMutuallyExclusiveGroup: true,
      checked: selected,
      enabled: o.enabled,
      child: Opacity(
        opacity: o.enabled ? 1 : 0.5,
        child: Material(
          color: selected ? (o.caution ? status.warningContainer : status.highlight) : scheme.surface,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10),
            side: BorderSide(color: selected ? accent : scheme.outlineVariant, width: selected ? 2 : 1),
          ),
          child: InkWell(
            borderRadius: BorderRadius.circular(10),
            onTap: !o.enabled
                ? null
                : () {
                    if (form != null && name != null) form!.changed(name!);
                    onChanged(o.value);
                  },
            child: Padding(
              padding: const EdgeInsets.fromLTRB(8, 10, 14, 12),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Icon(selected ? Icons.radio_button_checked : Icons.radio_button_off,
                    size: 20, color: selected ? accent : scheme.outline),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Wrap(spacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
                      Text(o.title, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
                      if (o.wire != null)
                        Text(o.wire!, style: monoStyle(context, size: 11, color: scheme.onSurfaceVariant)),
                    ]),
                    if (o.description != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 2),
                        child: Text(o.description!,
                            style: TextStyle(fontSize: 13, height: 1.4, color: scheme.onSurfaceVariant)),
                      ),
                  ]),
                ),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}

/// A secret input: masked with Reveal; on edit, blank means "keep the stored one".
class YSecretField extends StatefulWidget {
  const YSecretField({
    super.key,
    required this.label,
    required this.controller,
    this.form,
    this.name,
    this.wireKey,
    this.stored = false,
    this.required = false,
    this.helper,
  });

  final String label;
  final TextEditingController controller;
  final YFormController? form;
  final String? name;
  final String? wireKey;

  /// A value already exists server-side; leaving this blank keeps it.
  final bool stored;
  final bool required;
  final String? helper;

  @override
  State<YSecretField> createState() => _YSecretFieldState();
}

class _YSecretFieldState extends State<YSecretField> {
  bool _reveal = false;

  @override
  Widget build(BuildContext context) {
    return YTextField(
      label: widget.label,
      controller: widget.controller,
      form: widget.form,
      name: widget.name,
      wireKey: widget.wireKey,
      required: widget.required && !widget.stored,
      optional: widget.stored,
      obscure: !_reveal,
      mono: true,
      hint: widget.stored ? '•••••••• stored' : null,
      helper: widget.helper ??
          (widget.stored ? 'Stored. Leave blank to keep the current value.' : 'Shown once here; stored encrypted.'),
      suffix: IconButton(
        tooltip: _reveal ? 'Hide' : 'Reveal',
        icon: Icon(_reveal ? Icons.visibility_off_outlined : Icons.visibility_outlined),
        onPressed: () => setState(() => _reveal = !_reveal),
      ),
    );
  }
}

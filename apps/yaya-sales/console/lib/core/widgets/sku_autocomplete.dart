import 'package:flutter/material.dart';

/// A catalog variant as picker fodder — features map their entities into this
/// so the widget stays feature-neutral.
class SkuOption {
  const SkuOption({required this.sku, required this.label, this.detail});

  final String sku;
  final String label;
  final String? detail;
}

/// The one way SKUs are entered anywhere in the console: type-to-search over
/// the store's catalog instead of copying ids around.
class SkuAutocomplete extends StatelessWidget {
  const SkuAutocomplete({
    super.key,
    required this.options,
    required this.controller,
    this.labelText = 'SKU',
    this.helperText = 'type to search the catalog',
  });

  final List<SkuOption> options;
  final TextEditingController controller;
  final String labelText;
  final String helperText;

  @override
  Widget build(BuildContext context) {
    return Autocomplete<SkuOption>(
      displayStringForOption: (v) => v.sku,
      optionsBuilder: (text) {
        final q = text.text.toLowerCase();
        return options.where((v) =>
            v.sku.toLowerCase().contains(q) || v.label.toLowerCase().contains(q));
      },
      onSelected: (v) => controller.text = v.sku,
      fieldViewBuilder: (ctx, fieldController, focus, onSubmit) {
        if (fieldController.text.isEmpty && controller.text.isNotEmpty) {
          fieldController.text = controller.text;
        }
        fieldController.addListener(() => controller.text = fieldController.text);
        return TextField(
          controller: fieldController,
          focusNode: focus,
          decoration: InputDecoration(labelText: labelText, helperText: helperText),
        );
      },
      optionsViewBuilder: (ctx, onSelected, opts) => Align(
        alignment: Alignment.topLeft,
        child: Material(
          elevation: 4,
          child: SizedBox(
            width: 360,
            height: 220,
            child: ListView(
              children: opts
                  .map((v) => ListTile(
                        dense: true,
                        title: Text(v.sku),
                        subtitle: Text(
                            v.detail == null ? v.label : '${v.label} — ${v.detail}'),
                        onTap: () => onSelected(v),
                      ))
                  .toList(),
            ),
          ),
        ),
      ),
    );
  }
}

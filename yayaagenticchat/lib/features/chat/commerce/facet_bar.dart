import 'package:flutter/material.dart';

import '../../../models/commerce/facet.dart';

/// The instant, model-free filter row shipped inside `product_catalog`. Tapping a
/// chip filters the already-loaded product set locally — zero latency, zero LLM
/// round-trip (ecommerce-chat-design.md §10.2). Selections within a facet OR;
/// across facets AND (see [applyFacetFilter]).
class FacetBar extends StatelessWidget {
  const FacetBar({
    super.key,
    required this.facets,
    required this.selected,
    required this.onToggle,
    required this.accent,
  });

  final List<Facet> facets;
  final Set<FacetSelection> selected;
  final void Function(FacetSelection) onToggle;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    final chips = <Widget>[];
    for (final facet in facets) {
      if (facet.isPrice) {
        for (final b in facet.buckets) {
          chips.add(_chip(context, facet.key, b.label, b.count));
        }
      } else {
        for (final v in facet.values) {
          chips.add(_chip(context, facet.key, v.v, v.count));
        }
      }
    }
    if (chips.isEmpty) return const SizedBox.shrink();

    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          for (final c in chips) Padding(padding: const EdgeInsets.only(right: 6), child: c),
        ],
      ),
    );
  }

  Widget _chip(BuildContext context, String facetKey, String label, int count) {
    final sel = FacetSelection(facetKey, label);
    final isOn = selected.contains(sel);
    return FilterChip(
      label: Text(count > 0 ? '$label ($count)' : label),
      selected: isOn,
      onSelected: (_) => onToggle(sel),
      selectedColor: accent.withValues(alpha: 0.18),
      checkmarkColor: accent,
      visualDensity: VisualDensity.compact,
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
    );
  }
}

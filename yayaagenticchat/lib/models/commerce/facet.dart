import 'commerce_json.dart';
import 'product.dart';

/// One value of a categorical facet plus how many products carry it.
class FacetValue {
  final String v;
  final int count;
  const FacetValue(this.v, this.count);

  factory FacetValue.fromJson(Map<String, dynamic> j) =>
      FacetValue((j['v'] ?? '').toString(), asInt(j['count']));
}

/// A price band in integer minor units. `min`/`max` null = open-ended.
class PriceBucket {
  final String label;
  final int? min;
  final int? max;
  final int count;
  const PriceBucket(this.label, this.min, this.max, this.count);

  factory PriceBucket.fromJson(Map<String, dynamic> j) => PriceBucket(
        (j['label'] ?? '').toString(),
        asIntOrNull(j['min']),
        asIntOrNull(j['max']),
        asInt(j['count']),
      );

  bool contains(int amountMinor) =>
      (min == null || amountMinor >= min!) && (max == null || amountMinor < max!);
}

/// A filterable dimension shipped inside `product_catalog` so the client can
/// filter the already-loaded set with **zero latency and zero model round-trip**
/// (ecommerce-chat-design.md §10.2). Categorical facets carry `values`; a price
/// facet carries `buckets`.
class Facet {
  final String key;
  final String label;
  final List<FacetValue> values;
  final List<PriceBucket> buckets;

  const Facet({
    required this.key,
    required this.label,
    this.values = const [],
    this.buckets = const [],
  });

  bool get isPrice => buckets.isNotEmpty;

  factory Facet.fromJson(Map<String, dynamic> j) => Facet(
        key: (j['key'] ?? '').toString(),
        label: (j['label'] ?? j['key'] ?? '').toString(),
        values:
            asList(j['values']).map((e) => FacetValue.fromJson(asMap(e))).toList(),
        buckets: asList(j['buckets'])
            .map((e) => PriceBucket.fromJson(asMap(e)))
            .toList(),
      );
}

/// A single selected chip within a facet — either a categorical value or a
/// price bucket. Used for the instant client-side filter.
class FacetSelection {
  final String facetKey;
  final String label; // categorical value OR bucket label
  const FacetSelection(this.facetKey, this.label);

  @override
  bool operator ==(Object other) =>
      other is FacetSelection &&
      other.facetKey == facetKey &&
      other.label == label;

  @override
  int get hashCode => Object.hash(facetKey, label);
}

/// Instant, model-free local filtering of an already-loaded product set by a set
/// of selected facet chips. Selections within the same facet are OR'd; across
/// facets they are AND'd (the conventional storefront filter semantics).
List<Product> applyFacetFilter(
  List<Product> items,
  List<Facet> facets,
  Set<FacetSelection> selected,
) {
  if (selected.isEmpty) return items;

  // Group selections by facet key.
  final byFacet = <String, List<FacetSelection>>{};
  for (final s in selected) {
    byFacet.putIfAbsent(s.facetKey, () => []).add(s);
  }
  final facetByKey = {for (final f in facets) f.key: f};

  return items.where((p) {
    for (final entry in byFacet.entries) {
      final facet = facetByKey[entry.key];
      final matchesFacet = entry.value.any((sel) {
        if (facet != null && facet.isPrice) {
          final bucket =
              facet.buckets.where((b) => b.label == sel.label).firstOrNull;
          final amount = p.price?.amountMinor;
          return bucket != null && amount != null && bucket.contains(amount);
        }
        // Categorical: match the product's category or any attribute value.
        if (p.category != null &&
            p.category!.toLowerCase() == sel.label.toLowerCase()) {
          return true;
        }
        return p.attributes.values
            .any((v) => v.toLowerCase() == sel.label.toLowerCase());
      });
      if (!matchesFacet) return false; // AND across facets
    }
    return true;
  }).toList();
}

extension _FirstOrNull<E> on Iterable<E> {
  E? get firstOrNull {
    final it = iterator;
    return it.moveNext() ? it.current : null;
  }
}

import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticchat/models/commerce/facet.dart';
import 'package:yayaagenticchat/models/commerce/money.dart';
import 'package:yayaagenticchat/models/commerce/product.dart';

/// The instant, model-free local filter (§10.2): tapping facet chips filters the
/// already-loaded product set with no server round-trip.
void main() {
  Product p(String id, String category, Map<String, String> attrs, int priceMinor) => Product(
        id: id,
        title: id,
        price: Money(priceMinor, 'USD'),
        category: category,
        attributes: attrs,
      );

  final items = [
    p('a', 'soap', {'ingredient': 'castor oil'}, 800),
    p('b', 'soap', {'ingredient': 'shea'}, 1500),
    p('c', 'oil', {'ingredient': 'castor oil'}, 2500),
  ];

  final facets = [
    Facet(key: 'ingredient', label: 'Ingredient', values: const [
      FacetValue('castor oil', 2),
      FacetValue('shea', 1),
    ]),
    Facet(key: 'category', label: 'Type', values: const [
      FacetValue('soap', 2),
      FacetValue('oil', 1),
    ]),
    Facet(key: 'price', label: 'Price', buckets: const [
      PriceBucket('< \$10', null, 1000, 1),
      PriceBucket('\$10–20', 1000, 2000, 1),
    ]),
  ];

  test('no selection returns everything', () {
    expect(applyFacetFilter(items, facets, {}).length, 3);
  });

  test('categorical attribute filter (OR within facet)', () {
    final r = applyFacetFilter(items, facets, {const FacetSelection('ingredient', 'castor oil')});
    expect(r.map((e) => e.id), containsAll(['a', 'c']));
    expect(r.length, 2);
  });

  test('category value filter matches product.category', () {
    final r = applyFacetFilter(items, facets, {const FacetSelection('category', 'soap')});
    expect(r.map((e) => e.id), containsAll(['a', 'b']));
    expect(r.length, 2);
  });

  test('price bucket filter (min inclusive, max exclusive)', () {
    final r = applyFacetFilter(items, facets, {const FacetSelection('price', '< \$10')});
    expect(r.single.id, 'a'); // 800 < 1000
  });

  test('across-facet selections AND together', () {
    final r = applyFacetFilter(items, facets, {
      const FacetSelection('ingredient', 'castor oil'), // a, c
      const FacetSelection('category', 'soap'), // a, b
    });
    expect(r.single.id, 'a'); // intersection
  });
}

import 'commerce_json.dart';
import 'money.dart';

/// Stock signal mirrored from the backend `Availability` enum. Drives
/// inventory-aware rendering (grey out OUT) — §16.6.
enum Availability {
  inStock,
  low,
  out,
  preorder;

  static Availability parse(dynamic v) {
    switch (v?.toString().toUpperCase()) {
      case 'LOW':
        return Availability.low;
      case 'OUT':
        return Availability.out;
      case 'PREORDER':
        return Availability.preorder;
      default:
        return Availability.inStock;
    }
  }

  bool get purchasable => this != Availability.out;
}

/// A purchasable variation (size/scent/…); each carries its own price/sku/stock.
class Variant {
  final String variantId;
  final String label;
  final Money? price;
  final String? sku;
  final Availability availability;

  const Variant({
    required this.variantId,
    required this.label,
    this.price,
    this.sku,
    this.availability = Availability.inStock,
  });

  factory Variant.fromJson(Map<String, dynamic> j) => Variant(
        variantId: idStr(j['variantId']) ?? '',
        label: (j['label'] ?? '').toString(),
        price: Money.tryFrom(j['price']),
        sku: asStr(j['sku']),
        availability: Availability.parse(j['availability']),
      );
}

/// The render + cart contract — the mirror of the backend `Product` record.
/// The same payload backs a compact card (carousel) and an expanded card
/// (bottom sheet), so expanding is a view change, not a refetch (§10.1).
class Product {
  final String id;
  final String title;
  final String shortDescription;
  final Money? price;
  final List<String> imageUrls;
  final String? category;
  final Map<String, String> attributes;
  final List<Variant> variants;
  final Availability availability;
  final String? detailUrl;

  const Product({
    required this.id,
    required this.title,
    this.shortDescription = '',
    this.price,
    this.imageUrls = const [],
    this.category,
    this.attributes = const {},
    this.variants = const [],
    this.availability = Availability.inStock,
    this.detailUrl,
  });

  String? get heroImage => imageUrls.isNotEmpty ? imageUrls.first : null;

  bool get hasVariants => variants.isNotEmpty;

  factory Product.fromJson(Map<String, dynamic> j) => Product(
        id: idStr(j['id']) ?? '',
        title: (j['title'] ?? '').toString(),
        shortDescription: (j['shortDescription'] ?? '').toString(),
        price: Money.tryFrom(j['price']),
        imageUrls: asStrList(j['imageUrls']),
        category: asStr(j['category']),
        attributes: asMap(j['attributes'])
            .map((k, v) => MapEntry(k, v?.toString() ?? '')),
        variants:
            asList(j['variants']).map((e) => Variant.fromJson(asMap(e))).toList(),
        availability: Availability.parse(j['availability']),
        detailUrl: asStr(j['detailUrl']),
      );
}

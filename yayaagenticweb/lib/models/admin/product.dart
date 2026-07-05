// Admin models for managed products (the Database catalog backing store).
// Plain (no codegen). Prices are integer minor units end-to-end; the editor
// converts a decimal entry to minor units before sending, so the UI never hands
// the backend a float. See commerce-redesign-authoring-rendering-paypal.md Pillar A.

int _int(dynamic v) => (v as num?)?.toInt() ?? 0;

/// One row of the paged product table (lightweight projection).
class ProductRow {
  ProductRow({
    required this.productId,
    required this.title,
    required this.priceMinor,
    required this.currency,
    this.thumbnail,
    required this.availability,
    this.inventory,
    required this.status,
  });

  final String productId;
  final String title;
  final int priceMinor;
  final String currency;
  final String? thumbnail;
  final String availability;
  final int? inventory;
  final String status;

  bool get archived => status == 'ARCHIVED';

  factory ProductRow.fromJson(Map<String, dynamic> j) => ProductRow(
        productId: j['productId']?.toString() ?? '',
        title: j['title']?.toString() ?? '',
        priceMinor: _int(j['priceMinor']),
        currency: j['currency']?.toString() ?? '',
        thumbnail: j['thumbnail']?.toString(),
        availability: j['availability']?.toString() ?? 'IN_STOCK',
        inventory: (j['inventory'] as num?)?.toInt(),
        status: j['status']?.toString() ?? 'ACTIVE',
      );
}

class ProductsPage {
  ProductsPage({required this.items, required this.page, required this.size, required this.total});

  final List<ProductRow> items;
  final int page;
  final int size;
  final int total;

  int get pageCount => size <= 0 ? 1 : ((total + size - 1) ~/ size).clamp(1, 1 << 30);

  factory ProductsPage.fromJson(Map<String, dynamic> j) => ProductsPage(
        items: ((j['items'] as List?) ?? const [])
            .map((e) => ProductRow.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
        page: _int(j['page']),
        size: _int(j['size']),
        total: _int(j['total']),
      );
}

class VariantView {
  VariantView({
    required this.variantId,
    this.label = '',
    this.priceMinor = 0,
    this.currency,
    this.sku,
    this.availability,
  });

  final String variantId;
  final String label;
  final int priceMinor;
  final String? currency;
  final String? sku;
  final String? availability;

  factory VariantView.fromJson(Map<String, dynamic> j) => VariantView(
        variantId: j['variantId']?.toString() ?? '',
        label: j['label']?.toString() ?? '',
        priceMinor: _int(j['priceMinor']),
        currency: j['currency']?.toString(),
        sku: j['sku']?.toString(),
        availability: j['availability']?.toString(),
      );

  Map<String, dynamic> toJson() => {
        'variantId': variantId,
        'label': label,
        'priceMinor': priceMinor,
        'currency': currency,
        'sku': sku,
        'availability': availability,
      };
}

/// Full product for the editor.
class ProductDetail {
  ProductDetail({
    required this.productId,
    required this.title,
    this.shortDescription,
    this.longDescription,
    required this.priceMinor,
    required this.currency,
    this.category,
    this.attributes = const {},
    this.imageUrls = const [],
    this.variants = const [],
    required this.availability,
    this.inventory,
    this.detailUrl,
    required this.status,
    this.sortOrder = 0,
  });

  final String productId;
  final String title;
  final String? shortDescription;
  final String? longDescription;
  final int priceMinor;
  final String currency;
  final String? category;
  final Map<String, String> attributes;
  final List<String> imageUrls;
  final List<VariantView> variants;
  final String availability;
  final int? inventory;
  final String? detailUrl;
  final String status;
  final int sortOrder;

  factory ProductDetail.fromJson(Map<String, dynamic> j) => ProductDetail(
        productId: j['productId']?.toString() ?? '',
        title: j['title']?.toString() ?? '',
        shortDescription: j['shortDescription']?.toString(),
        longDescription: j['longDescription']?.toString(),
        priceMinor: _int(j['priceMinor']),
        currency: j['currency']?.toString() ?? '',
        category: j['category']?.toString(),
        attributes: ((j['attributes'] as Map?) ?? const {})
            .map((k, v) => MapEntry(k.toString(), v?.toString() ?? '')),
        imageUrls: ((j['imageUrls'] as List?) ?? const []).map((e) => e.toString()).toList(),
        variants: ((j['variants'] as List?) ?? const [])
            .map((e) => VariantView.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
        availability: j['availability']?.toString() ?? 'IN_STOCK',
        inventory: (j['inventory'] as num?)?.toInt(),
        detailUrl: j['detailUrl']?.toString(),
        status: j['status']?.toString() ?? 'ACTIVE',
        sortOrder: _int(j['sortOrder']),
      );
}

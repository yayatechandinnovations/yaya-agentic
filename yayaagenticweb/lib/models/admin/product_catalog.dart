// Admin models for product catalogs. Plain (no codegen) — mirrors the
// knowledge-source model's shape: a locationKind discriminator plus a raw
// location map the operator fills in. See ecommerce-chat-design §7.1, §15.

class CreateProductCatalogRequest {
  CreateProductCatalogRequest({
    this.tenant = 'default',
    required this.id,
    required this.displayName,
    required this.currency,
    this.imageUrl,
    required this.locationKind,
    this.location = const {},
    this.sync = const {},
    this.search = const {},
    this.access = const {},
  });

  final String tenant;
  final String id;
  final String displayName;
  final String currency;
  final String? imageUrl;
  final String locationKind;
  final Map<String, dynamic> location;
  final Map<String, dynamic> sync;
  final Map<String, dynamic> search;
  final Map<String, dynamic> access;

  Map<String, dynamic> toJson() => {
        'tenant': tenant,
        'id': id,
        'displayName': displayName,
        'currency': currency,
        'imageUrl': imageUrl,
        'locationKind': locationKind,
        'location': location,
        'sync': sync,
        'search': search,
        'access': access,
      };
}

class ProductCatalogResponse {
  ProductCatalogResponse({
    required this.id,
    required this.version,
    required this.tenant,
    required this.displayName,
    required this.currency,
    this.imageUrl,
    required this.locationKind,
    required this.productCount,
  });

  final String id;
  final int version;
  final String tenant;
  final String displayName;
  final String currency;
  final String? imageUrl;
  final String locationKind;

  /// Number of products for an INLINE catalog; -1 for HTTP_API.
  final int productCount;

  factory ProductCatalogResponse.fromJson(Map<String, dynamic> json) =>
      ProductCatalogResponse(
        id: json['id']?.toString() ?? '',
        version: (json['version'] as num?)?.toInt() ?? 1,
        tenant: json['tenant']?.toString() ?? '',
        displayName: json['displayName']?.toString() ?? '',
        currency: json['currency']?.toString() ?? '',
        imageUrl: json['imageUrl']?.toString(),
        locationKind: json['locationKind']?.toString() ?? 'INLINE',
        productCount: (json['productCount'] as num?)?.toInt() ?? -1,
      );
}

/// Full catalog detail (from GET /{id}) carrying the raw [location] map so the
/// admin form can rehydrate every field for edit-in-place.
class ProductCatalogDetail {
  ProductCatalogDetail({
    required this.id,
    required this.version,
    required this.tenant,
    required this.displayName,
    required this.currency,
    this.imageUrl,
    required this.locationKind,
    this.location = const {},
  });

  final String id;
  final int version;
  final String tenant;
  final String displayName;
  final String currency;
  final String? imageUrl;
  final String locationKind;
  final Map<String, dynamic> location;

  factory ProductCatalogDetail.fromJson(Map<String, dynamic> json) =>
      ProductCatalogDetail(
        id: json['id']?.toString() ?? '',
        version: (json['version'] as num?)?.toInt() ?? 1,
        tenant: json['tenant']?.toString() ?? '',
        displayName: json['displayName']?.toString() ?? '',
        currency: json['currency']?.toString() ?? '',
        imageUrl: json['imageUrl']?.toString(),
        locationKind: json['locationKind']?.toString() ?? 'INLINE',
        location: (json['location'] as Map?)?.cast<String, dynamic>() ?? const {},
      );
}

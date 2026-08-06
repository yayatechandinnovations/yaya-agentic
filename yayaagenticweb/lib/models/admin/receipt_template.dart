/// Receipt template — one row in `receipt_templates` (chat-experience-design.md
/// §7.4). `elements` is the ordered layout array validated server-side against
/// the closed vocabulary (header/status/meta/divider/title/text/kv/section/
/// table/cells/total/code/barcode/qr/footer/spacer); every domain word lives in
/// runtime `data` bindings, never in the schema.
class ReceiptTemplateResponse {
  ReceiptTemplateResponse({
    required this.id,
    required this.name,
    this.kindLabel,
    required this.paperCols,
    required this.paperProfile,
    required this.elements,
    this.brand,
    this.createdAt,
    this.updatedAt,
  });

  final String id;
  final String name;
  final String? kindLabel;
  final int paperCols;
  final String paperProfile;
  final List<dynamic> elements;
  final Map<String, dynamic>? brand;
  final String? createdAt;
  final String? updatedAt;

  factory ReceiptTemplateResponse.fromJson(Map<String, dynamic> json) =>
      ReceiptTemplateResponse(
        id: (json['id'] ?? '') as String,
        name: (json['name'] ?? '') as String,
        kindLabel: json['kindLabel'] as String?,
        paperCols: (json['paperCols'] as num?)?.toInt() ?? 32,
        paperProfile: (json['paperProfile'] ?? '58mm') as String,
        elements: (json['elements'] as List?) ?? const [],
        brand: json['brand'] == null
            ? null
            : Map<String, dynamic>.from(json['brand'] as Map),
        createdAt: json['createdAt']?.toString(),
        updatedAt: json['updatedAt']?.toString(),
      );
}

class ReceiptTemplateRequest {
  ReceiptTemplateRequest({
    required this.name,
    this.kindLabel,
    required this.paperCols,
    required this.paperProfile,
    required this.elements,
    this.brand,
  });

  final String name;
  final String? kindLabel;
  final int paperCols;
  final String paperProfile;
  final List<dynamic> elements;
  final Map<String, dynamic>? brand;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'name': name,
        if (kindLabel != null && kindLabel!.isNotEmpty) 'kindLabel': kindLabel,
        'paperCols': paperCols,
        'paperProfile': paperProfile,
        'elements': elements,
        if (brand != null) 'brand': brand,
      };
}

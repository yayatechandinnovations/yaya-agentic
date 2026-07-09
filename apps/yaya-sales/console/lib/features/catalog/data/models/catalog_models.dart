import '../../../../core/network/json.dart';
import '../../domain/entities/catalog_entities.dart';

Catalog catalogFromJson(Map<String, dynamic> json) => Catalog(
      id: idOf(json['id']),
      version: intOf(json['version']),
      displayName: '${json['displayName'] ?? ''}',
      status: '${json['status'] ?? 'DRAFT'}',
    );

Variant variantFromJson(Map<String, dynamic> json) => Variant(
      sku: '${json['sku'] ?? ''}',
      label: '${json['label'] ?? ''}',
      price: moneyOf(json['price']),
      options: mapOf(json['options']).map((k, dynamic v) => MapEntry(k, '$v')),
      weightGrams: json['weightGrams'] == null ? null : intOf(json['weightGrams']),
      barcode: json['barcode'] as String?,
    );

Product productFromJson(Map<String, dynamic> json) => Product(
      id: idOf(json['id']),
      title: '${json['title'] ?? ''}',
      description: '${json['description'] ?? ''}',
      category: '${json['category'] ?? ''}',
      status: '${json['status'] ?? ''}',
      sortOrder: intOf(json['sortOrder']),
      attributes: mapOf(json['attributes']).map((k, dynamic v) => MapEntry(k, '$v')),
      media: (json['media'] as List<dynamic>? ?? []).map((m) => '$m').toList(),
      metadata: mapOf(json['metadata']),
      variants: listOfMaps(json['variants']).map(variantFromJson).toList(),
    );

Map<String, dynamic> draftToJson(ProductDraft draft) => {
      'title': draft.title,
      'description': draft.description,
      'category': draft.category,
      if (draft.status != null) 'status': draft.status,
      'sortOrder': draft.sortOrder,
      'attributes': draft.attributes,
      'media': draft.media,
      'metadata': draft.metadata,
      'variants': draft.variants
          .map((v) => {
                'sku': v.sku,
                'label': v.label,
                if (v.price != null)
                  'price': {
                    'amountMinor': v.price!.amountMinor,
                    'currency': v.price!.currency,
                  },
                'options': v.options,
                if (v.weightGrams != null) 'weightGrams': v.weightGrams,
                if (v.barcode != null && v.barcode!.isNotEmpty) 'barcode': v.barcode,
              })
          .toList(),
    };

ImportResult importResultFromJson(Map<String, dynamic> json) => ImportResult(
      created: intOf(json['created']),
      updated: intOf(json['updated']),
      errors: listOfMaps(json['errors'])
          .map((e) => ImportRowError(row: intOf(e['row']), message: '${e['message'] ?? ''}'))
          .toList(),
    );

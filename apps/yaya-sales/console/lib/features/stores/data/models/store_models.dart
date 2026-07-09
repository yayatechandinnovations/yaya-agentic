import '../../../../core/network/json.dart';
import '../../domain/entities/store_entities.dart';

Store storeFromJson(Map<String, dynamic> json) {
  return Store(
    id: idOf(json['id']),
    tenantId: idOrNull(json['tenant']) ?? '',
    displayName: '${json['displayName'] ?? ''}',
    defaultCurrency: '${json['defaultCurrency'] ?? ''}',
    policies: policiesFromJson(mapOf(json['policies'])),
  );
}

StorePolicies policiesFromJson(Map<String, dynamic> json) {
  return StorePolicies(
    deductionPoint: '${json['deductionPoint'] ?? 'ON_PAYMENT'}',
    backorder: '${json['backorder'] ?? 'DENY'}',
    softHoldTtlSeconds: intOf(json['softHoldTtlSeconds'], 600),
    checkoutHoldTtlSeconds: intOf(json['checkoutHoldTtlSeconds'], 1800),
  );
}

Map<String, dynamic> policiesToJson(StorePolicies p) => {
      'deductionPoint': p.deductionPoint,
      'backorder': p.backorder,
      'softHoldTtlSeconds': p.softHoldTtlSeconds,
      'checkoutHoldTtlSeconds': p.checkoutHoldTtlSeconds,
    };

StoreLocation locationFromJson(Map<String, dynamic> json) {
  return StoreLocation(
    id: idOf(json['id']),
    label: '${json['label'] ?? ''}',
    kind: '${json['kind'] ?? 'WAREHOUSE'}',
    sellable: json['sellable'] == true,
  );
}

Channel channelFromJson(Map<String, dynamic> json) {
  return Channel(
    id: idOf(json['id']),
    kind: '${json['kind'] ?? ''}',
    label: '${json['label'] ?? ''}',
    active: json['active'] == true,
  );
}

TaxPolicy taxFromJson(Map<String, dynamic> json) {
  return TaxPolicy(
    inclusive: json['inclusive'] == true,
    rateBps: intOf(json['rateBps']),
    label: '${json['label'] ?? ''}',
  );
}

Map<String, dynamic> taxToJson(TaxPolicy tax) =>
    {'inclusive': tax.inclusive, 'rateBps': tax.rateBps, 'label': tax.label};

Region regionFromJson(Map<String, dynamic> json) {
  return Region(
    id: idOf(json['id']),
    name: '${json['name'] ?? ''}',
    currency: '${json['currency'] ?? ''}',
    tax: taxFromJson(mapOf(json['tax'])),
    tenders: (json['tenders'] as List<dynamic>? ?? []).map((t) => '$t').toList(),
  );
}

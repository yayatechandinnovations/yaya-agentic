import '../../../../core/domain/money.dart';
import '../../../../core/network/json.dart';
import '../../domain/entities/shipping_entities.dart';

RegionMatch regionMatchFromJson(Map<String, dynamic> json) => RegionMatch(
      country: json['country'] as String?,
      region: json['region'] as String?,
      postalPrefix: json['postalPrefix'] as String?,
    );

ShippingZone zoneFromJson(Map<String, dynamic> json) => ShippingZone(
      id: idOf(json['id']),
      name: '${json['name'] ?? ''}',
      regions: listOfMaps(json['regions']).map(regionMatchFromJson).toList(),
    );

DeliveryMethod methodFromJson(Map<String, dynamic> json) => DeliveryMethod(
      id: idOf(json['id']),
      kind: '${json['kind'] ?? ''}',
      label: '${json['label'] ?? ''}',
      active: json['active'] == true,
    );

Carrier carrierFromJson(Map<String, dynamic> json) => Carrier(
      id: idOf(json['id']),
      kind: '${json['kind'] ?? ''}',
      name: '${json['name'] ?? ''}',
    );

/// The serialized RateRule union carries no type tag — infer one for display.
String ruleSummary(dynamic rule) {
  if (rule is! Map) return '—';
  final map = mapOf(rule);
  if (map.containsKey('threshold')) {
    final threshold = moneyOf(map['threshold']);
    return 'FREE over ${formatMoney(threshold)}, else ${ruleSummary(map['otherwise'])}';
  }
  if (map.containsKey('amount')) {
    return 'FLAT ${formatMoney(moneyOf(map['amount']))}';
  }
  if (map.containsKey('tiers')) {
    final tiers = listOfMaps(map['tiers'])
        .map((t) => '≤${t['upTo']}: ${formatMoney(moneyOf(t['amount']))}')
        .join(', ');
    return 'TIERED [$tiers]';
  }
  if (map.containsKey('carrier')) {
    return 'CARRIER CALCULATED';
  }
  return '—';
}

ShippingRate rateFromJson(Map<String, dynamic> json) => ShippingRate(
      id: idOf(json['id']),
      zoneId: idOf(json['zone']),
      methodId: idOf(json['method']),
      carrierId: idOrNull(json['carrier']),
      serviceLevel: json['serviceLevel'] as String?,
      ruleSummary: ruleSummary(json['rule']),
      handlingFee: moneyOf(json['handlingFee']),
      transit: json['transit'] == null
          ? null
          : TransitEstimate(
              minDays: intOf(mapOf(json['transit'])['minDays']),
              maxDays: intOf(mapOf(json['transit'])['maxDays']),
            ),
    );

Map<String, dynamic> ruleDraftToJson(RateRuleDraft rule) => {
      'type': rule.type,
      if (rule.amountMinor != null) 'amountMinor': rule.amountMinor,
      if (rule.tiers.isNotEmpty)
        'tiers': rule.tiers
            .map((t) => {'upTo': t.upTo, 'amountMinor': t.amountMinor})
            .toList(),
      if (rule.thresholdMinor != null) 'thresholdMinor': rule.thresholdMinor,
      if (rule.otherwise != null) 'otherwise': ruleDraftToJson(rule.otherwise!),
    };

DeliverySlot slotFromJson(Map<String, dynamic> json) => DeliverySlot(
      id: idOf(json['id']),
      locationId: idOf(json['location']),
      methodId: idOf(json['method']),
      startsAt: dateOf(json['startsAt']),
      endsAt: dateOf(json['endsAt']),
      capacity: intOf(json['capacity']),
      booked: intOf(json['booked']),
    );

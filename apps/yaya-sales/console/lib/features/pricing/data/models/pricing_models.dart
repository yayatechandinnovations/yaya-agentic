import '../../../../core/network/json.dart';
import '../../domain/entities/pricing_entities.dart';

// The pricing admin API answers plain maps; nulls arrive as empty strings.
String? _blankAsNull(dynamic v) {
  final s = v == null ? '' : '$v';
  return s.isEmpty ? null : s;
}

PriceRule ruleFromJson(Map<String, dynamic> json) => PriceRule(
      sku: '${json['sku'] ?? ''}',
      minQty: json['minQty'] == null ? null : intOf(json['minQty']),
      priceMinor: intOf(json['priceMinor']),
    );

Map<String, dynamic> ruleToJson(PriceRule rule) => {
      'sku': rule.sku,
      if (rule.minQty != null) 'minQty': rule.minQty,
      'priceMinor': rule.priceMinor,
    };

PriceList priceListFromJson(Map<String, dynamic> json) => PriceList(
      id: '${json['id'] ?? ''}',
      regionId: _blankAsNull(json['regionId']),
      channelId: _blankAsNull(json['channelId']),
      customerGroup: _blankAsNull(json['customerGroup']),
      rules: (json['rules'] is List) ? listOfMaps(json['rules']).map(ruleFromJson).toList() : [],
      active: json['active'] == true,
    );

Promotion promotionFromJson(Map<String, dynamic> json) {
  final value = mapOf(json['value']);
  final eligibility = mapOf(json['eligibility']);
  return Promotion(
    id: '${json['id'] ?? ''}',
    kind: '${json['kind'] ?? ''}',
    bps: value['bps'] == null ? null : intOf(value['bps']),
    amountMinor: value['amountMinor'] == null ? null : intOf(value['amountMinor']),
    minSubtotalMinor: eligibility['minSubtotalMinor'] == null
        ? null
        : intOf(eligibility['minSubtotalMinor']),
    skus: (eligibility['skus'] as List<dynamic>? ?? []).map((s) => '$s').toList(),
    code: _blankAsNull(json['code']),
    active: json['active'] == true,
  );
}

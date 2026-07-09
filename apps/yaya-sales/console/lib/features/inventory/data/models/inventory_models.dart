import '../../../../core/network/json.dart';
import '../../domain/entities/inventory_entities.dart';

StockLevel stockLevelFromJson(Map<String, dynamic> json) => StockLevel(
      sku: idOf(json['sku']),
      locationId: idOf(json['location']),
      onHand: intOf(json['onHand']),
      reserved: intOf(json['reserved']),
      reorderPoint: intOf(json['reorderPoint']),
      reorderQty: intOf(json['reorderQty']),
      backorder: '${json['backorder'] ?? 'DENY'}',
      avgCost: moneyOf(json['avgCost']),
    );

Movement movementFromJson(Map<String, dynamic> json) => Movement(
      id: idOf(json['id']),
      sku: idOf(json['sku']),
      locationId: idOf(json['location']),
      qtyDelta: intOf(json['qtyDelta']),
      kind: '${json['kind'] ?? ''}',
      unitCost: moneyOf(json['unitCost']),
      refType: json['refType'] as String?,
      refId: json['refId'] as String?,
      reason: json['reason'] as String?,
      occurredAt: dateOf(json['occurredAt']),
    );

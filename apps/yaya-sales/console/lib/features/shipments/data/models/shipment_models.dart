import '../../../../core/network/json.dart';
import '../../domain/entities/shipment_entities.dart';

Shipment shipmentFromJson(Map<String, dynamic> json) => Shipment(
      id: idOf(json['id']),
      orderId: idOf(json['order']),
      status: '${json['status'] ?? ''}',
      deliveryStatus:
          json['deliveryStatus'] == null ? null : '${json['deliveryStatus']}',
      kind: json['kind'] == null ? null : '${json['kind']}',
      locationId: idOrNull(json['location']),
      carrierId: idOrNull(json['carrier']),
      lines: listOfMaps(json['lines'])
          .map((l) => ShipmentLine(sku: idOf(l['sku']), qty: intOf(l['qty'])))
          .toList(),
      parcel: json['parcel'] == null
          ? null
          : Parcel(
              weightGrams: _optInt(mapOf(json['parcel'])['weightGrams']),
              lengthCm: _optInt(mapOf(json['parcel'])['lengthCm']),
              widthCm: _optInt(mapOf(json['parcel'])['widthCm']),
              heightCm: _optInt(mapOf(json['parcel'])['heightCm']),
            ),
      trackingNumber: json['trackingNumber'] as String?,
      trackingUrl: json['trackingUrl'] as String?,
      dispatchedAt: dateOf(json['dispatchedAt']),
      deliveredAt: dateOf(json['deliveredAt']),
    );

int? _optInt(dynamic v) => v == null ? null : intOf(v);

TrackingEvent trackingEventFromJson(Map<String, dynamic> json) => TrackingEvent(
      status: '${json['status'] ?? ''}',
      description: json['description'] as String?,
      location: json['location'] as String?,
      occurredAt: dateOf(json['occurredAt']),
    );

Assignment assignmentFromJson(Map<String, dynamic> json) => Assignment(
      id: idOf(json['id']),
      shipmentId: idOf(json['shipment']),
      agentId: idOf(json['agent']),
      status: '${json['status'] ?? ''}',
    );

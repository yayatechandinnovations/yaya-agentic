import '../../../../core/network/json.dart';
import '../../domain/entities/rma_entities.dart';

Rma rmaFromJson(Map<String, dynamic> json) => Rma(
      id: idOf(json['id']),
      orderId: idOf(json['order']),
      status: '${json['status'] ?? ''}',
      reason: json['reason'] as String?,
      lines: listOfMaps(json['lines'])
          .map((l) => RmaLine(sku: idOf(l['sku']), qty: intOf(l['qty'])))
          .toList(),
      returnShipment: json['returnShipment'] == null
          ? null
          : ReturnShipmentInfo(
              carrier: mapOf(json['returnShipment'])['carrier'] as String?,
              trackingNumber: mapOf(json['returnShipment'])['trackingNumber'] as String?,
              instructions: mapOf(json['returnShipment'])['instructions'] as String?,
            ),
      dispositions: listOfMaps(json['dispositions'])
          .map((d) => RmaDisposition(
                sku: idOf(d['sku']),
                qty: intOf(d['qty']),
                kind: '${d['kind'] ?? ''}',
              ))
          .toList(),
      refundRef: json['refundRef'] as String?,
      receivedAt: dateOf(json['receivedAt']),
      createdAt: dateOf(json['createdAt']),
    );

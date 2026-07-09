import '../../../../core/network/json.dart';
import '../../domain/entities/purchasing_entities.dart';

Supplier supplierFromJson(Map<String, dynamic> json) => Supplier(
      id: idOf(json['id']),
      name: '${json['name'] ?? ''}',
      contact: mapOf(json['contact']),
    );

PurchaseOrder poFromJson(Map<String, dynamic> json) => PurchaseOrder(
      id: idOf(json['id']),
      supplierId: idOf(json['supplier']),
      locationId: idOf(json['location']),
      status: '${json['status'] ?? ''}',
      lines: listOfMaps(json['lines'])
          .map((l) => PoLine(
                sku: idOf(l['sku']),
                qty: intOf(l['qty']),
                unitCostMinor: intOf(l['unitCostMinor']),
                receivedQty: intOf(l['receivedQty']),
              ))
          .toList(),
      expectedAt: dateOf(json['expectedAt']),
      createdAt: dateOf(json['createdAt']),
    );

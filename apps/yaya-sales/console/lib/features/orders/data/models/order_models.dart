import '../../../../core/network/json.dart';
import '../../domain/entities/order_entities.dart';

Order orderFromJson(Map<String, dynamic> json) {
  final totals = mapOf(json['totals']);
  final delivery = json['delivery'] == null ? null : mapOf(json['delivery']);
  return Order(
    id: idOf(json['id']),
    status: '${json['status'] ?? ''}',
    paymentState: '${json['paymentState'] ?? ''}',
    currency: '${json['currency'] ?? ''}',
    customerRef: json['customerRef'] as String?,
    source: json['source'] as String?,
    fulfillmentState: json['fulfillmentState'] as String?,
    lines: listOfMaps(json['lines'])
        .map((l) => OrderLine(
              sku: idOf(l['sku']),
              title: '${l['title'] ?? ''}',
              qty: intOf(l['qty']),
              unitPrice: moneyOf(l['unitPrice']),
            ))
        .toList(),
    totals: OrderTotals(
      subtotal: moneyOf(totals['subtotal']),
      discount: moneyOf(totals['discount']),
      tax: moneyOf(totals['tax']),
      taxInclusive: totals['taxInclusive'] == true,
      shipping: moneyOf(totals['shipping']),
      grand: moneyOf(totals['grand']),
    ),
    delivery: delivery == null
        ? null
        : OrderDelivery(
            kind: delivery['kind'] as String?,
            label: delivery['label'] as String?,
            price: moneyOf(delivery['price']),
            promisedFrom: dateOf(delivery['promisedFrom']),
            promisedTo: dateOf(delivery['promisedTo']),
            shipTo: delivery['shipTo'] == null
                ? null
                : _address(mapOf(delivery['shipTo'])),
          ),
    createdAt: dateOf(json['createdAt']),
  );
}

String _address(Map<String, dynamic> a) => [
      a['line1'],
      a['line2'],
      a['city'],
      a['region'],
      a['postalCode'],
      a['country'],
    ].where((p) => p != null && '$p'.isNotEmpty).join(', ');

OrderEvent orderEventFromJson(Map<String, dynamic> json) => OrderEvent(
      type: '${json['type'] ?? ''}',
      actor: json['actor'] as String?,
      fromState: json['fromState'] as String?,
      toState: json['toState'] as String?,
      at: dateOf(json['at']),
    );

Tender tenderFromJson(Map<String, dynamic> json) => Tender(
      id: idOf(json['id']),
      kind: '${json['kind'] ?? ''}',
      status: '${json['status'] ?? ''}',
      amount: moneyOf(json['amount']),
      settledAt: dateOf(json['settledAt']),
    );

OrderDetail orderDetailFromJson(Map<String, dynamic> json) => OrderDetail(
      order: orderFromJson(mapOf(json['order'])),
      events: listOfMaps(json['events']).map(orderEventFromJson).toList(),
      tenders: listOfMaps(json['tenders']).map(tenderFromJson).toList(),
    );

StoreCreditBalance storeCreditFromJson(Map<String, dynamic> json) => StoreCreditBalance(
      customerRef: '${json['customerRef'] ?? ''}',
      balanceMinor: intOf(json['balanceMinor']),
      currency: '${json['currency'] ?? ''}',
    );

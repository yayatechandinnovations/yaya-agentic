import 'package:equatable/equatable.dart';

import '../../../../core/domain/money.dart';

class OrderLine extends Equatable {
  const OrderLine({
    required this.sku,
    required this.title,
    required this.qty,
    this.unitPrice,
  });

  final String sku;
  final String title;
  final int qty;
  final Money? unitPrice;

  @override
  List<Object?> get props => [sku, title, qty, unitPrice];
}

class OrderTotals extends Equatable {
  const OrderTotals({
    this.subtotal,
    this.discount,
    this.tax,
    this.taxInclusive = false,
    this.shipping,
    this.grand,
  });

  final Money? subtotal;
  final Money? discount;
  final Money? tax;
  final bool taxInclusive;
  final Money? shipping;
  final Money? grand;

  @override
  List<Object?> get props => [subtotal, discount, tax, taxInclusive, shipping, grand];
}

class OrderDelivery extends Equatable {
  const OrderDelivery({
    this.kind,
    this.label,
    this.price,
    this.promisedFrom,
    this.promisedTo,
    this.shipTo,
  });

  final String? kind;
  final String? label;
  final Money? price;
  final DateTime? promisedFrom;
  final DateTime? promisedTo;
  final String? shipTo;

  @override
  List<Object?> get props => [kind, label, price, promisedFrom, promisedTo, shipTo];
}

class Order extends Equatable {
  const Order({
    required this.id,
    required this.status,
    required this.paymentState,
    required this.currency,
    this.customerRef,
    this.source,
    this.fulfillmentState,
    this.lines = const [],
    this.totals = const OrderTotals(),
    this.delivery,
    this.createdAt,
  });

  final String id;
  final String status;
  final String paymentState;
  final String currency;
  final String? customerRef;
  final String? source;
  final String? fulfillmentState;
  final List<OrderLine> lines;
  final OrderTotals totals;
  final OrderDelivery? delivery;
  final DateTime? createdAt;

  static const statuses = [
    'DRAFT',
    'PENDING_PAYMENT',
    'AUTH_FAILED',
    'PAID',
    'FULFILLING',
    'PARTIALLY_FULFILLED',
    'FULFILLED',
    'CANCELLED',
    'PARTIALLY_REFUNDED',
    'REFUNDED',
  ];

  @override
  List<Object?> get props => [
        id,
        status,
        paymentState,
        currency,
        customerRef,
        source,
        fulfillmentState,
        lines,
        totals,
        delivery,
        createdAt,
      ];
}

class OrderEvent extends Equatable {
  const OrderEvent({
    required this.type,
    this.actor,
    this.fromState,
    this.toState,
    this.at,
  });

  final String type;
  final String? actor;
  final String? fromState;
  final String? toState;
  final DateTime? at;

  @override
  List<Object?> get props => [type, actor, fromState, toState, at];
}

class Tender extends Equatable {
  const Tender({
    required this.id,
    required this.kind,
    required this.status,
    this.amount,
    this.settledAt,
  });

  final String id;
  final String kind; // PSP | COD | MANUAL | STORE_CREDIT
  final String status; // PENDING | SETTLED | REFUNDED | VOIDED
  final Money? amount;
  final DateTime? settledAt;

  static const kinds = ['PSP', 'COD', 'MANUAL', 'STORE_CREDIT'];

  @override
  List<Object?> get props => [id, kind, status, amount, settledAt];
}

/// The detail endpoint's composite: order + event timeline + payment ledger.
class OrderDetail extends Equatable {
  const OrderDetail({required this.order, required this.events, required this.tenders});

  final Order order;
  final List<OrderEvent> events;
  final List<Tender> tenders;

  @override
  List<Object?> get props => [order, events, tenders];
}

class StoreCreditBalance extends Equatable {
  const StoreCreditBalance({
    required this.customerRef,
    required this.balanceMinor,
    required this.currency,
  });

  final String customerRef;
  final int balanceMinor;
  final String currency;

  @override
  List<Object?> get props => [customerRef, balanceMinor, currency];
}

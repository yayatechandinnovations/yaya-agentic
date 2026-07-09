import 'package:equatable/equatable.dart';

class DraftLine extends Equatable {
  const DraftLine({required this.sku, required this.qty, this.unitPriceMinor});

  final String sku;
  final int qty;

  /// Optional one-off price override, minor units.
  final int? unitPriceMinor;

  @override
  List<Object?> get props => [sku, qty, unitPriceMinor];
}

/// The outcome of "send" — mirrors the CheckoutOutcome wire union.
class SendOutcome extends Equatable {
  const SendOutcome({
    required this.status,
    required this.orderId,
    this.paymentUrl,
    this.clientSecret,
    this.instructions,
    this.insufficientSku,
    this.available,
  });

  final String status; // AWAITING_PAYMENT | PLACED | PAID | INSUFFICIENT_STOCK
  final String orderId;
  final String? paymentUrl;
  final String? clientSecret;
  final Map<String, dynamic>? instructions;
  final String? insufficientSku;
  final int? available;

  @override
  List<Object?> get props =>
      [status, orderId, paymentUrl, clientSecret, instructions, insufficientSku, available];
}

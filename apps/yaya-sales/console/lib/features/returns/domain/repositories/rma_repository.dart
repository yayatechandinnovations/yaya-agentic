import '../entities/rma_entities.dart';

abstract class RmaRepository {
  Future<Rma> request(String orderId, List<RmaLine> lines, String? reason);
  Future<List<Rma>> list({String? status});
  Future<Rma> detail(String id);
  Future<Rma> authorize(String id);
  Future<Rma> reject(String id, String? reason);
  Future<Rma> cancel(String id);
  Future<Rma> setReturnShipment(String id, ReturnShipmentInfo info);

  /// [refundTo] is `store_credit` (default) or `original_tender`.
  Future<Rma> receive(String id, List<RmaDisposition> dispositions, String refundTo,
      int? amountMinor);
}

import '../entities/order_entities.dart';

abstract class OrderRepository {
  Future<List<Order>> orders({String? status});
  Future<OrderDetail> detail(String id);
  Future<Order> cancel(String id);
  Future<Tender> addTender(String orderId, String kind, int amountMinor);
  Future<void> settleTender(String orderId, String tenderId);

  /// [amountMinor] null = full refund; [toStoreCredit] routes the money.
  Future<Order> refund(String orderId, {int? amountMinor, required bool toStoreCredit});

  Future<StoreCreditBalance> storeCreditBalance(String customerRef, String currency);
  Future<void> issueStoreCredit(
      String customerRef, int amountMinor, String currency, String? reason);
}

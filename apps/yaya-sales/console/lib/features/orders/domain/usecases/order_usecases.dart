import '../entities/order_entities.dart';
import '../repositories/order_repository.dart';

class ListOrders {
  const ListOrders(this._repo);
  final OrderRepository _repo;
  Future<List<Order>> call({String? status}) => _repo.orders(status: status);
}

class GetOrderDetail {
  const GetOrderDetail(this._repo);
  final OrderRepository _repo;
  Future<OrderDetail> call(String id) => _repo.detail(id);
}

class CancelOrder {
  const CancelOrder(this._repo);
  final OrderRepository _repo;
  Future<Order> call(String id) => _repo.cancel(id);
}

class AddTender {
  const AddTender(this._repo);
  final OrderRepository _repo;
  Future<Tender> call(String orderId, String kind, int amountMinor) =>
      _repo.addTender(orderId, kind, amountMinor);
}

class SettleTender {
  const SettleTender(this._repo);
  final OrderRepository _repo;
  Future<void> call(String orderId, String tenderId) => _repo.settleTender(orderId, tenderId);
}

class RefundOrder {
  const RefundOrder(this._repo);
  final OrderRepository _repo;
  Future<Order> call(String orderId, {int? amountMinor, required bool toStoreCredit}) =>
      _repo.refund(orderId, amountMinor: amountMinor, toStoreCredit: toStoreCredit);
}

class GetStoreCreditBalance {
  const GetStoreCreditBalance(this._repo);
  final OrderRepository _repo;
  Future<StoreCreditBalance> call(String customerRef, String currency) =>
      _repo.storeCreditBalance(customerRef, currency);
}

class IssueStoreCredit {
  const IssueStoreCredit(this._repo);
  final OrderRepository _repo;
  Future<void> call(String customerRef, int amountMinor, String currency, String? reason) =>
      _repo.issueStoreCredit(customerRef, amountMinor, currency, reason);
}

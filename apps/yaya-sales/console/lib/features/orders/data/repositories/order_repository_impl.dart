import 'package:dio/dio.dart';

import '../../../../core/network/error_mapper.dart';
import '../../domain/entities/order_entities.dart';
import '../../domain/repositories/order_repository.dart';
import '../datasources/order_remote_data_source.dart';
import '../models/order_models.dart';

class OrderRepositoryImpl implements OrderRepository {
  OrderRepositoryImpl(this._remote);

  final OrderRemoteDataSource _remote;

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw mapDioError(e);
    }
  }

  @override
  Future<List<Order>> orders({String? status}) =>
      _guard(() async => (await _remote.orders(status: status)).map(orderFromJson).toList());

  @override
  Future<OrderDetail> detail(String id) =>
      _guard(() async => orderDetailFromJson(await _remote.detail(id)));

  @override
  Future<Order> cancel(String id) =>
      _guard(() async => orderFromJson(await _remote.cancel(id)));

  @override
  Future<Tender> addTender(String orderId, String kind, int amountMinor) => _guard(
      () async => tenderFromJson(await _remote.addTender(orderId, kind, amountMinor)));

  @override
  Future<void> settleTender(String orderId, String tenderId) =>
      _guard(() => _remote.settleTender(orderId, tenderId));

  @override
  Future<Order> refund(String orderId, {int? amountMinor, required bool toStoreCredit}) =>
      _guard(() async => orderFromJson(await _remote.refund(
          orderId, amountMinor, toStoreCredit ? 'store_credit' : 'psp')));

  @override
  Future<StoreCreditBalance> storeCreditBalance(String customerRef, String currency) =>
      _guard(() async =>
          storeCreditFromJson(await _remote.storeCreditBalance(customerRef, currency)));

  @override
  Future<void> issueStoreCredit(
          String customerRef, int amountMinor, String currency, String? reason) =>
      _guard(() => _remote.issueStoreCredit(customerRef, amountMinor, currency, reason));
}

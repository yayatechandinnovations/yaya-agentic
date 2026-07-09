import 'package:dio/dio.dart';

import '../../../../core/network/error_mapper.dart';
import '../../domain/entities/purchasing_entities.dart';
import '../../domain/repositories/purchasing_repository.dart';
import '../datasources/purchasing_remote_data_source.dart';
import '../models/purchasing_models.dart';

class PurchasingRepositoryImpl implements PurchasingRepository {
  PurchasingRepositoryImpl(this._remote);

  final PurchasingRemoteDataSource _remote;

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw mapDioError(e);
    }
  }

  @override
  Future<List<Supplier>> suppliers() =>
      _guard(() async => (await _remote.suppliers()).map(supplierFromJson).toList());

  @override
  Future<Supplier> createSupplier(String name, Map<String, dynamic> contact) =>
      _guard(() async => supplierFromJson(await _remote.createSupplier(name, contact)));

  @override
  Future<Supplier> updateSupplier(String id, String name, Map<String, dynamic> contact) =>
      _guard(() async => supplierFromJson(await _remote.updateSupplier(id, name, contact)));

  @override
  Future<void> deleteSupplier(String id) => _guard(() => _remote.deleteSupplier(id));

  @override
  Future<List<PurchaseOrder>> purchaseOrders({String? status}) => _guard(
      () async => (await _remote.purchaseOrders(status: status)).map(poFromJson).toList());

  @override
  Future<PurchaseOrder> createPo(String supplierId, String locationId, List<PoLine> lines,
          DateTime? expectedAt) =>
      _guard(() async => poFromJson(await _remote.createPo({
            'supplierId': supplierId,
            'locationId': locationId,
            'lines': lines
                .map((l) =>
                    {'sku': l.sku, 'qty': l.qty, 'unitCostMinor': l.unitCostMinor})
                .toList(),
            if (expectedAt != null) 'expectedAt': expectedAt.toUtc().toIso8601String(),
          })));

  @override
  Future<PurchaseOrder> place(String poId) =>
      _guard(() async => poFromJson(await _remote.place(poId)));

  @override
  Future<PurchaseOrder> cancel(String poId) =>
      _guard(() async => poFromJson(await _remote.cancel(poId)));

  @override
  Future<PurchaseOrder> receive(String poId, List<PoLine> lines) =>
      _guard(() async => poFromJson(await _remote.receive(
            poId,
            lines
                .map((l) =>
                    {'sku': l.sku, 'qty': l.qty, 'unitCostMinor': l.unitCostMinor})
                .toList(),
          )));
}

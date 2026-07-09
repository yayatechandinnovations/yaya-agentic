import 'package:dio/dio.dart';

import '../../../../core/network/error_mapper.dart';
import '../../domain/entities/inventory_entities.dart';
import '../../domain/repositories/inventory_repository.dart';
import '../datasources/inventory_remote_data_source.dart';
import '../models/inventory_models.dart';

class InventoryRepositoryImpl implements InventoryRepository {
  InventoryRepositoryImpl(this._remote);

  final InventoryRemoteDataSource _remote;

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw mapDioError(e);
    }
  }

  @override
  Future<List<StockLevel>> levels({String? location, bool lowOnly = false}) =>
      _guard(() async => (await _remote.levels(location: location, lowOnly: lowOnly))
          .map(stockLevelFromJson)
          .toList());

  @override
  Future<List<Movement>> ledger(String sku, {String? location}) => _guard(() async =>
      (await _remote.ledger(sku, location: location)).map(movementFromJson).toList());

  @override
  Future<void> receive(String sku, String location, int qty, int unitCostMinor,
          String currency, String? poRef) =>
      _guard(() => _remote.receive(sku, location, qty, unitCostMinor, currency, poRef));

  @override
  Future<void> adjust(String sku, String location, int delta, String reason) =>
      _guard(() => _remote.adjust(sku, location, delta, reason));

  @override
  Future<void> transfer(String sku, String from, String to, int qty) =>
      _guard(() => _remote.transfer(sku, from, to, qty));

  @override
  Future<void> setReorder(String sku, String location, int point, int qty) =>
      _guard(() => _remote.setReorder(sku, location, point, qty));

  @override
  Future<void> setBackorder(String sku, String location, String policy) =>
      _guard(() => _remote.setBackorder(sku, location, policy));
}

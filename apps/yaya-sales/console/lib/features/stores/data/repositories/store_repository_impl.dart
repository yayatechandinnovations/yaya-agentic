import 'package:dio/dio.dart';

import '../../../../core/network/error_mapper.dart';
import '../../domain/entities/store_entities.dart';
import '../../domain/repositories/store_repository.dart';
import '../datasources/store_remote_data_source.dart';
import '../models/store_models.dart';

class StoreRepositoryImpl implements StoreRepository {
  StoreRepositoryImpl(this._remote);

  final StoreRemoteDataSource _remote;

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw mapDioError(e);
    }
  }

  @override
  Future<List<Store>> storesOf(String tenantId) =>
      _guard(() async => (await _remote.storesOf(tenantId)).map(storeFromJson).toList());

  @override
  Future<Store> createStore(String tenantId, String displayName, String defaultCurrency) =>
      _guard(() async =>
          storeFromJson(await _remote.createStore(tenantId, displayName, defaultCurrency)));

  @override
  Future<Store> store(String id) => _guard(() async => storeFromJson(await _remote.store(id)));

  @override
  Future<Store> updatePolicies(String id, StorePolicies policies) => _guard(
      () async => storeFromJson(await _remote.updatePolicies(id, policiesToJson(policies))));

  @override
  Future<List<StoreLocation>> locations() =>
      _guard(() async => (await _remote.locations()).map(locationFromJson).toList());

  @override
  Future<StoreLocation> addLocation(String label, String kind, bool sellable,
          {Map<String, dynamic>? address}) =>
      _guard(() async =>
          locationFromJson(await _remote.addLocation(label, kind, sellable, address)));

  @override
  Future<StoreLocation> updateLocation(String id,
          {String? label, String? kind, bool? sellable, Map<String, dynamic>? address}) =>
      _guard(() async => locationFromJson(await _remote.updateLocation(id, {
            if (label != null) 'label': label,
            if (kind != null) 'kind': kind,
            if (sellable != null) 'sellable': sellable,
            if (address != null) 'address': address,
          })));

  @override
  Future<List<Channel>> channels() =>
      _guard(() async => (await _remote.channels()).map(channelFromJson).toList());

  @override
  Future<Channel> addChannel(String kind, String? label) =>
      _guard(() async => channelFromJson(await _remote.addChannel(kind, label)));

  @override
  Future<List<Region>> regions() =>
      _guard(() async => (await _remote.regions()).map(regionFromJson).toList());

  @override
  Future<Region> addRegion(
          String name, String currency, TaxPolicy tax, List<String> tenders) =>
      _guard(() async =>
          regionFromJson(await _remote.addRegion(name, currency, taxToJson(tax), tenders)));

  @override
  Future<Region> updateRegion(String id, String name, TaxPolicy tax, List<String> tenders) =>
      _guard(() async =>
          regionFromJson(await _remote.updateRegion(id, name, taxToJson(tax), tenders)));
}

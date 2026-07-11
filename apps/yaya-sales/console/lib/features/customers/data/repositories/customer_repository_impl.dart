import 'package:dio/dio.dart';

import '../../../../core/network/error_mapper.dart';
import '../../domain/entities/customer_entities.dart';
import '../../domain/repositories/customer_repository.dart';
import '../datasources/customer_remote_data_source.dart';
import '../models/customer_models.dart';

class CustomerRepositoryImpl implements CustomerRepository {
  CustomerRepositoryImpl(this._remote);

  final CustomerRemoteDataSource _remote;

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw mapDioError(e);
    }
  }

  @override
  Future<List<Customer>> search(String? q) => _guard(
      () async => (await _remote.search(q)).map(customerFromJson).toList());

  @override
  Future<Customer> create(CustomerDraft draft) => _guard(
      () async => customerFromJson(await _remote.create(customerDraftToJson(draft))));

  @override
  Future<Customer> update(String id, CustomerDraft draft) => _guard(
      () async => customerFromJson(await _remote.update(id, customerDraftToJson(draft))));

  @override
  Future<void> delete(String id) => _guard(() => _remote.delete(id));

  @override
  Future<Customer> addAddress(String id, String kind, Map<String, dynamic> address) =>
      _guard(() async => customerFromJson(await _remote.addAddress(id, kind, address)));

  @override
  Future<Customer> removeAddress(String id, String addressId) =>
      _guard(() async => customerFromJson(await _remote.removeAddress(id, addressId)));
}

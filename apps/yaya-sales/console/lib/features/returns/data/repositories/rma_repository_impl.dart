import 'package:dio/dio.dart';

import '../../../../core/network/error_mapper.dart';
import '../../domain/entities/rma_entities.dart';
import '../../domain/repositories/rma_repository.dart';
import '../datasources/rma_remote_data_source.dart';
import '../models/rma_models.dart';

class RmaRepositoryImpl implements RmaRepository {
  RmaRepositoryImpl(this._remote);

  final RmaRemoteDataSource _remote;

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw mapDioError(e);
    }
  }

  @override
  Future<Rma> request(String orderId, List<RmaLine> lines, String? reason) =>
      _guard(() async => rmaFromJson(await _remote.request(
            orderId,
            lines.map((l) => {'sku': l.sku, 'qty': l.qty}).toList(),
            reason,
          )));

  @override
  Future<List<Rma>> list({String? status}) =>
      _guard(() async => (await _remote.list(status: status)).map(rmaFromJson).toList());

  @override
  Future<Rma> detail(String id) => _guard(() async => rmaFromJson(await _remote.detail(id)));

  @override
  Future<Rma> authorize(String id) =>
      _guard(() async => rmaFromJson(await _remote.authorize(id)));

  @override
  Future<Rma> reject(String id, String? reason) =>
      _guard(() async => rmaFromJson(await _remote.reject(id, reason)));

  @override
  Future<Rma> cancel(String id) => _guard(() async => rmaFromJson(await _remote.cancel(id)));

  @override
  Future<Rma> setReturnShipment(String id, ReturnShipmentInfo info) =>
      _guard(() async => rmaFromJson(await _remote.setReturnShipment(id, {
            if (info.carrier != null) 'carrier': info.carrier,
            if (info.trackingNumber != null) 'trackingNumber': info.trackingNumber,
            if (info.instructions != null) 'instructions': info.instructions,
          })));

  @override
  Future<Rma> receive(String id, List<RmaDisposition> dispositions, String refundTo,
          int? amountMinor) =>
      _guard(() async => rmaFromJson(await _remote.receive(id, {
            'dispositions': dispositions
                .map((d) => {'sku': d.sku, 'qty': d.qty, 'disposition': d.kind})
                .toList(),
            'refundTo': refundTo,
            if (amountMinor != null) 'amountMinor': amountMinor,
          })));
}

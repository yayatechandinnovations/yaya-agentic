import 'package:dio/dio.dart';

import '../../../../core/network/error_mapper.dart';
import '../../../../core/network/json.dart';
import '../../../orders/data/models/order_models.dart';
import '../../../orders/domain/entities/order_entities.dart';
import '../../domain/entities/draft_entities.dart';
import '../../domain/repositories/draft_repository.dart';
import '../datasources/draft_remote_data_source.dart';

class DraftRepositoryImpl implements DraftRepository {
  DraftRepositoryImpl(this._remote);

  final DraftRemoteDataSource _remote;

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw mapDioError(e);
    }
  }

  static List<Map<String, dynamic>> _lines(List<DraftLine> lines) => lines
      .map((l) => {
            'sku': l.sku,
            'qty': l.qty,
            if (l.unitPriceMinor != null) 'unitPriceMinor': l.unitPriceMinor,
          })
      .toList();

  @override
  Future<Order> create({
    String? channel,
    String? regionId,
    String? customerRef,
    String? note,
    required List<DraftLine> lines,
  }) =>
      _guard(() async => orderFromJson(await _remote.create({
            if (channel != null && channel.isNotEmpty) 'channel': channel,
            if (regionId != null && regionId.isNotEmpty) 'regionId': regionId,
            if (customerRef != null && customerRef.isNotEmpty) 'customerRef': customerRef,
            if (note != null && note.isNotEmpty) 'note': note,
            'lines': _lines(lines),
          })));

  @override
  Future<Order> update(String orderId, List<DraftLine> lines) => _guard(
      () async => orderFromJson(await _remote.update(orderId, {'lines': _lines(lines)})));

  @override
  Future<SendOutcome> send(
    String orderId, {
    String? tender,
    bool applyStoreCredit = false,
    String? returnUrl,
    String? customerRef,
  }) =>
      _guard(() async {
        final json = await _remote.send(orderId, {
          if (tender != null && tender.isNotEmpty) 'tender': tender,
          'applyStoreCredit': applyStoreCredit,
          if (returnUrl != null && returnUrl.isNotEmpty) 'returnUrl': returnUrl,
          if (customerRef != null && customerRef.isNotEmpty) 'customerRef': customerRef,
        });
        return SendOutcome(
          status: '${json['status'] ?? ''}',
          orderId: '${json['orderId'] ?? orderId}',
          paymentUrl: json['paymentUrl'] as String?,
          clientSecret: json['clientSecret'] as String?,
          instructions: json['instructions'] == null ? null : mapOf(json['instructions']),
          insufficientSku: json['sku'] as String?,
          available: json['available'] == null ? null : intOf(json['available']),
        );
      });
}

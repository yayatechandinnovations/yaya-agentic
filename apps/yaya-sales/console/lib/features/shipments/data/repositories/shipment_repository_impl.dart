import 'package:dio/dio.dart';

import '../../../../core/network/error_mapper.dart';
import '../../domain/entities/shipment_entities.dart';
import '../../domain/repositories/shipment_repository.dart';
import '../datasources/shipment_remote_data_source.dart';
import '../models/shipment_models.dart';

class ShipmentRepositoryImpl implements ShipmentRepository {
  ShipmentRepositoryImpl(this._remote);

  final ShipmentRemoteDataSource _remote;

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw mapDioError(e);
    }
  }

  @override
  Future<List<Shipment>> ofOrder(String orderId) =>
      _guard(() async => (await _remote.ofOrder(orderId)).map(shipmentFromJson).toList());

  @override
  Future<Shipment> create(String orderId, List<ShipmentLine> lines, String? locationId) =>
      _guard(() async => shipmentFromJson(await _remote.create(
            orderId,
            lines.map((l) => {'sku': l.sku, 'qty': l.qty}).toList(),
            locationId,
          )));

  @override
  Future<Shipment> pick(String shipmentId) =>
      _guard(() async => shipmentFromJson(await _remote.pick(shipmentId)));

  @override
  Future<Shipment> pack(String shipmentId, Parcel? parcel) =>
      _guard(() async => shipmentFromJson(await _remote.pack(
            shipmentId,
            parcel == null
                ? null
                : {
                    if (parcel.weightGrams != null) 'weightGrams': parcel.weightGrams,
                    if (parcel.lengthCm != null) 'lengthCm': parcel.lengthCm,
                    if (parcel.widthCm != null) 'widthCm': parcel.widthCm,
                    if (parcel.heightCm != null) 'heightCm': parcel.heightCm,
                  },
          )));

  @override
  Future<Shipment> dispatch(
          String shipmentId, String? trackingNumber, String? trackingUrl) =>
      _guard(() async => shipmentFromJson(
          await _remote.dispatch(shipmentId, trackingNumber, trackingUrl)));

  @override
  Future<Shipment> advanceDelivery(String shipmentId, String status,
          {Map<String, dynamic>? proof, String? description, String? location}) =>
      _guard(() async => shipmentFromJson(await _remote.advanceDelivery(
          shipmentId, status, proof, description, location)));

  @override
  Future<List<TrackingEvent>> tracking(String shipmentId) => _guard(() async =>
      (await _remote.tracking(shipmentId)).map(trackingEventFromJson).toList());

  @override
  Future<Assignment> assign(String shipmentId, String? agentId) =>
      _guard(() async => assignmentFromJson(await _remote.assign(shipmentId, agentId)));
}

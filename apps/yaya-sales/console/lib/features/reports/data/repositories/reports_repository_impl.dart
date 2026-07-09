import 'package:dio/dio.dart';

import '../../../../core/network/error_mapper.dart';
import '../../../../core/network/json.dart';
import '../../../inventory/data/models/inventory_models.dart';
import '../../domain/entities/report_entities.dart';
import '../../domain/repositories/reports_repository.dart';
import '../datasources/reports_remote_data_source.dart';

class ReportsRepositoryImpl implements ReportsRepository {
  ReportsRepositoryImpl(this._remote);

  final ReportsRemoteDataSource _remote;

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw mapDioError(e);
    }
  }

  @override
  Future<SalesReport> sales(DateTime? from, DateTime? to) => _guard(() async {
        final json = await _remote.sales(from, to);
        return SalesReport(
          currency: '${json['currency'] ?? ''}',
          ordersCount: intOf(json['ordersCount']),
          grossMinor: intOf(json['grossMinor']),
          refundsMinor: intOf(json['refundsMinor']),
          netMinor: intOf(json['netMinor']),
        );
      });

  @override
  Future<ValuationReport> inventoryValuation() => _guard(() async {
        final json = await _remote.inventoryValuation();
        return ValuationReport(
          currency: '${json['currency'] ?? ''}',
          totalMinor: intOf(json['totalMinor']),
          lines: listOfMaps(json['lines'])
              .map((l) => ValuationLine(
                    sku: '${l['sku'] ?? ''}',
                    location: '${l['location'] ?? ''}',
                    onHand: intOf(l['onHand']),
                    avgCostMinor: intOf(l['avgCostMinor']),
                    valueMinor: intOf(l['valueMinor']),
                  ))
              .toList(),
        );
      });

  @override
  Future<MarginReport> margin(DateTime? from, DateTime? to) => _guard(() async {
        final json = await _remote.margin(from, to);
        return MarginReport(
          currency: '${json['currency'] ?? ''}',
          revenueMinor: intOf(json['revenueMinor']),
          cogsMinor: intOf(json['cogsMinor']),
          marginMinor: intOf(json['marginMinor']),
        );
      });

  @override
  Future<LowStockReport> lowStock() => _guard(() async {
        final json = await _remote.lowStock();
        return LowStockReport(
          levels: listOfMaps(json['levels']).map(stockLevelFromJson).toList(),
        );
      });

  @override
  Future<CodReport> codReconciliation(DateTime? from, DateTime? to) => _guard(() async {
        final json = await _remote.codReconciliation(from, to);
        final mismatches = mapOf(json['mismatches']);
        List<CodMismatch> mismatchList(dynamic v) => listOfMaps(v)
            .map((m) => CodMismatch(
                orderId: '${m['orderId'] ?? ''}', amountMinor: intOf(m['amountMinor'])))
            .toList();
        return CodReport(
          currency: '${json['currency'] ?? ''}',
          couriers: listOfMaps(json['couriers'])
              .map((c) => CourierCod(
                    agentId: '${c['agentId'] ?? ''}',
                    legalName: '${c['legalName'] ?? ''}',
                    ordersDelivered: intOf(c['ordersDelivered']),
                    codSettledMinor: intOf(c['codSettledMinor']),
                  ))
              .toList(),
          settledWithoutDelivery: mismatchList(mismatches['settledWithoutDelivery']),
          deliveredWithoutSettlement: mismatchList(mismatches['deliveredWithoutSettlement']),
        );
      });
}

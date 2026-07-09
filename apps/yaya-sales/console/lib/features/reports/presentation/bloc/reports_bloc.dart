import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/report_entities.dart';
import '../../domain/usecases/report_usecases.dart';

part 'reports_event.dart';
part 'reports_state.dart';

/// Read-only reports: sales, inventory valuation, margin, low stock, COD
/// reconciliation. One event per report so tabs load independently.
class ReportsBloc extends Bloc<ReportsEvent, ReportsState> {
  ReportsBloc({
    required ReadSalesReport readSales,
    required ReadValuationReport readValuation,
    required ReadMarginReport readMargin,
    required ReadLowStockReport readLowStock,
    required ReadCodReport readCod,
  })  : _readSales = readSales,
        _readValuation = readValuation,
        _readMargin = readMargin,
        _readLowStock = readLowStock,
        _readCod = readCod,
        super(const ReportsState()) {
    on<SalesReportRequested>(_onSales);
    on<ValuationReportRequested>(_onValuation);
    on<MarginReportRequested>(_onMargin);
    on<LowStockReportRequested>(_onLowStock);
    on<CodReportRequested>(_onCod);
  }

  final ReadSalesReport _readSales;
  final ReadValuationReport _readValuation;
  final ReadMarginReport _readMargin;
  final ReadLowStockReport _readLowStock;
  final ReadCodReport _readCod;

  Future<void> _onSales(SalesReportRequested event, Emitter<ReportsState> emit) async {
    await _load(emit, () async {
      final report = await _readSales(event.from, event.to);
      return state.copyWith(status: ReportsStatus.loaded, sales: report);
    });
  }

  Future<void> _onValuation(
      ValuationReportRequested event, Emitter<ReportsState> emit) async {
    await _load(emit, () async {
      final report = await _readValuation();
      return state.copyWith(status: ReportsStatus.loaded, valuation: report);
    });
  }

  Future<void> _onMargin(MarginReportRequested event, Emitter<ReportsState> emit) async {
    await _load(emit, () async {
      final report = await _readMargin(event.from, event.to);
      return state.copyWith(status: ReportsStatus.loaded, margin: report);
    });
  }

  Future<void> _onLowStock(
      LowStockReportRequested event, Emitter<ReportsState> emit) async {
    await _load(emit, () async {
      final report = await _readLowStock();
      return state.copyWith(status: ReportsStatus.loaded, lowStock: report);
    });
  }

  Future<void> _onCod(CodReportRequested event, Emitter<ReportsState> emit) async {
    await _load(emit, () async {
      final report = await _readCod(event.from, event.to);
      return state.copyWith(status: ReportsStatus.loaded, cod: report);
    });
  }

  Future<void> _load(
      Emitter<ReportsState> emit, Future<ReportsState> Function() loader) async {
    emit(state.copyWith(status: ReportsStatus.loading));
    try {
      emit(await loader());
    } on Failure catch (f) {
      emit(state.copyWith(status: ReportsStatus.failure, error: f.message));
    }
  }
}

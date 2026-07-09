import '../entities/report_entities.dart';
import '../repositories/reports_repository.dart';

class ReadSalesReport {
  const ReadSalesReport(this._repo);
  final ReportsRepository _repo;
  Future<SalesReport> call(DateTime? from, DateTime? to) => _repo.sales(from, to);
}

class ReadValuationReport {
  const ReadValuationReport(this._repo);
  final ReportsRepository _repo;
  Future<ValuationReport> call() => _repo.inventoryValuation();
}

class ReadMarginReport {
  const ReadMarginReport(this._repo);
  final ReportsRepository _repo;
  Future<MarginReport> call(DateTime? from, DateTime? to) => _repo.margin(from, to);
}

class ReadLowStockReport {
  const ReadLowStockReport(this._repo);
  final ReportsRepository _repo;
  Future<LowStockReport> call() => _repo.lowStock();
}

class ReadCodReport {
  const ReadCodReport(this._repo);
  final ReportsRepository _repo;
  Future<CodReport> call(DateTime? from, DateTime? to) => _repo.codReconciliation(from, to);
}

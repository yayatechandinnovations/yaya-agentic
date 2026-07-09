import '../entities/report_entities.dart';

abstract class ReportsRepository {
  Future<SalesReport> sales(DateTime? from, DateTime? to);
  Future<ValuationReport> inventoryValuation();
  Future<MarginReport> margin(DateTime? from, DateTime? to);
  Future<LowStockReport> lowStock();
  Future<CodReport> codReconciliation(DateTime? from, DateTime? to);
}

part of 'reports_bloc.dart';

enum ReportsStatus { initial, loading, loaded, failure }

class ReportsState extends Equatable {
  const ReportsState({
    this.status = ReportsStatus.initial,
    this.sales,
    this.valuation,
    this.margin,
    this.lowStock,
    this.cod,
    this.error,
  });

  final ReportsStatus status;
  final SalesReport? sales;
  final ValuationReport? valuation;
  final MarginReport? margin;
  final LowStockReport? lowStock;
  final CodReport? cod;
  final String? error;

  ReportsState copyWith({
    ReportsStatus? status,
    SalesReport? sales,
    ValuationReport? valuation,
    MarginReport? margin,
    LowStockReport? lowStock,
    CodReport? cod,
    String? error,
  }) =>
      ReportsState(
        status: status ?? this.status,
        sales: sales ?? this.sales,
        valuation: valuation ?? this.valuation,
        margin: margin ?? this.margin,
        lowStock: lowStock ?? this.lowStock,
        cod: cod ?? this.cod,
        error: error,
      );

  @override
  List<Object?> get props => [status, sales, valuation, margin, lowStock, cod, error];
}

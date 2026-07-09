part of 'reports_bloc.dart';

sealed class ReportsEvent extends Equatable {
  const ReportsEvent();

  @override
  List<Object?> get props => [];
}

class SalesReportRequested extends ReportsEvent {
  const SalesReportRequested({this.from, this.to});

  final DateTime? from;
  final DateTime? to;

  @override
  List<Object?> get props => [from, to];
}

class ValuationReportRequested extends ReportsEvent {
  const ValuationReportRequested();
}

class MarginReportRequested extends ReportsEvent {
  const MarginReportRequested({this.from, this.to});

  final DateTime? from;
  final DateTime? to;

  @override
  List<Object?> get props => [from, to];
}

class LowStockReportRequested extends ReportsEvent {
  const LowStockReportRequested();
}

class CodReportRequested extends ReportsEvent {
  const CodReportRequested({this.from, this.to});

  final DateTime? from;
  final DateTime? to;

  @override
  List<Object?> get props => [from, to];
}

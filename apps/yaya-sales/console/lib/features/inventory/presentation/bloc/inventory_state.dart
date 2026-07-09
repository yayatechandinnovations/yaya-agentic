part of 'inventory_bloc.dart';

enum InventoryStatus { initial, loading, loaded, failure }

class InventoryState extends Equatable {
  const InventoryState({
    this.status = InventoryStatus.initial,
    this.levels = const [],
    this.locationFilter,
    this.lowOnly = false,
    this.ledgerSku,
    this.ledger = const [],
    this.error,
  });

  final InventoryStatus status;
  final List<StockLevel> levels;
  final String? locationFilter;
  final bool lowOnly;
  final String? ledgerSku;
  final List<Movement> ledger;
  final String? error;

  InventoryState copyWith({
    InventoryStatus? status,
    List<StockLevel>? levels,
    String? locationFilter,
    bool? lowOnly,
    String? ledgerSku,
    List<Movement>? ledger,
    String? error,
    bool clearLedger = false,
  }) =>
      InventoryState(
        status: status ?? this.status,
        levels: levels ?? this.levels,
        locationFilter: locationFilter ?? this.locationFilter,
        lowOnly: lowOnly ?? this.lowOnly,
        ledgerSku: clearLedger ? null : (ledgerSku ?? this.ledgerSku),
        ledger: clearLedger ? const [] : (ledger ?? this.ledger),
        error: error,
      );

  @override
  List<Object?> get props =>
      [status, levels, locationFilter, lowOnly, ledgerSku, ledger, error];
}

part of 'inventory_bloc.dart';

enum InventoryStatus { initial, loading, loaded, failure }

class InventoryState extends Equatable {
  const InventoryState({
    this.status = InventoryStatus.initial,
    this.levels = const [],
    this.locations = const [],
    this.locationFilter,
    this.lowOnly = false,
    this.ledgerSku,
    this.ledger = const [],
    this.error,
  });

  final InventoryStatus status;
  final List<StockLevel> levels;
  final List<StoreLocation> locations;
  final String? locationFilter;
  final bool lowOnly;
  final String? ledgerSku;
  final List<Movement> ledger;
  final String? error;

  InventoryState copyWith({
    InventoryStatus? status,
    List<StockLevel>? levels,
    List<StoreLocation>? locations,
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
        locations: locations ?? this.locations,
        locationFilter: locationFilter ?? this.locationFilter,
        lowOnly: lowOnly ?? this.lowOnly,
        ledgerSku: clearLedger ? null : (ledgerSku ?? this.ledgerSku),
        ledger: clearLedger ? const [] : (ledger ?? this.ledger),
        error: error,
      );

  @override
  List<Object?> get props =>
      [status, levels, locations, locationFilter, lowOnly, ledgerSku, ledger, error];
}

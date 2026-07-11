part of 'drafts_bloc.dart';

enum DraftsStatus { initial, loading, loaded, failure }

class DraftsState extends Equatable {
  const DraftsState({
    this.status = DraftsStatus.initial,
    this.drafts = const [],
    this.skuOptions = const [],
    this.regions = const [],
    this.customers = const [],
    this.lastOutcome,
    this.error,
  });

  final DraftsStatus status;
  final List<Order> drafts;
  final List<Variant> skuOptions;
  final List<Region> regions;
  final List<Customer> customers;
  final SendOutcome? lastOutcome;
  final String? error;

  DraftsState copyWith({
    DraftsStatus? status,
    List<Order>? drafts,
    List<Variant>? skuOptions,
    List<Region>? regions,
    List<Customer>? customers,
    SendOutcome? lastOutcome,
    String? error,
  }) =>
      DraftsState(
        status: status ?? this.status,
        drafts: drafts ?? this.drafts,
        skuOptions: skuOptions ?? this.skuOptions,
        regions: regions ?? this.regions,
        customers: customers ?? this.customers,
        lastOutcome: lastOutcome,
        error: error,
      );

  @override
  List<Object?> get props =>
      [status, drafts, skuOptions, regions, customers, lastOutcome, error];
}

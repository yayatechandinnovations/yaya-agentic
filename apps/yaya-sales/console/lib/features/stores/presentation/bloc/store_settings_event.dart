part of 'store_settings_bloc.dart';

sealed class StoreSettingsEvent extends Equatable {
  const StoreSettingsEvent();

  @override
  List<Object?> get props => [];
}

class StoreSettingsRequested extends StoreSettingsEvent {
  const StoreSettingsRequested(this.storeId);

  final String storeId;

  @override
  List<Object?> get props => [storeId];
}

class PoliciesSaveRequested extends StoreSettingsEvent {
  const PoliciesSaveRequested(this.policies);

  final StorePolicies policies;

  @override
  List<Object?> get props => [policies];
}

class LocationAddRequested extends StoreSettingsEvent {
  const LocationAddRequested(this.label, this.kind, this.sellable);

  final String label;
  final String kind;
  final bool sellable;

  @override
  List<Object?> get props => [label, kind, sellable];
}

class ChannelAddRequested extends StoreSettingsEvent {
  const ChannelAddRequested(this.kind, this.label);

  final String kind;
  final String? label;

  @override
  List<Object?> get props => [kind, label];
}

class RegionAddRequested extends StoreSettingsEvent {
  const RegionAddRequested(this.name, this.currency, this.tax, this.tenders);

  final String name;
  final String currency;
  final TaxPolicy tax;
  final List<String> tenders;

  @override
  List<Object?> get props => [name, currency, tax, tenders];
}

class RegionUpdateRequested extends StoreSettingsEvent {
  const RegionUpdateRequested(this.id, this.name, this.tax, this.tenders);

  final String id;
  final String name;
  final TaxPolicy tax;
  final List<String> tenders;

  @override
  List<Object?> get props => [id, name, tax, tenders];
}

part of 'store_settings_bloc.dart';

enum StoreSettingsStatus { initial, loading, loaded, failure }

class StoreSettingsState extends Equatable {
  const StoreSettingsState({
    this.status = StoreSettingsStatus.initial,
    this.storeId = '',
    this.store,
    this.locations = const [],
    this.channels = const [],
    this.regions = const [],
    this.error,
  });

  final StoreSettingsStatus status;
  final String storeId;
  final Store? store;
  final List<StoreLocation> locations;
  final List<Channel> channels;
  final List<Region> regions;
  final String? error;

  StoreSettingsState copyWith({
    StoreSettingsStatus? status,
    String? storeId,
    Store? store,
    List<StoreLocation>? locations,
    List<Channel>? channels,
    List<Region>? regions,
    String? error,
  }) =>
      StoreSettingsState(
        status: status ?? this.status,
        storeId: storeId ?? this.storeId,
        store: store ?? this.store,
        locations: locations ?? this.locations,
        channels: channels ?? this.channels,
        regions: regions ?? this.regions,
        error: error,
      );

  @override
  List<Object?> get props => [status, storeId, store, locations, channels, regions, error];
}

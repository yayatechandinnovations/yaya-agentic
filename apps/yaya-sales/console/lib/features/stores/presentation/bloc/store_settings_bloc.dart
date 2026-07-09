import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/store_entities.dart';
import '../../domain/usecases/store_usecases.dart';

part 'store_settings_event.dart';
part 'store_settings_state.dart';

/// Store settings: detail + policies + locations + channels + regions.
class StoreSettingsBloc extends Bloc<StoreSettingsEvent, StoreSettingsState> {
  StoreSettingsBloc({
    required GetStore getStore,
    required UpdateStorePolicies updatePolicies,
    required ListLocations listLocations,
    required AddLocation addLocation,
    required ListChannels listChannels,
    required AddChannel addChannel,
    required ListRegions listRegions,
    required AddRegion addRegion,
    required UpdateRegion updateRegion,
  })  : _getStore = getStore,
        _updatePolicies = updatePolicies,
        _listLocations = listLocations,
        _addLocation = addLocation,
        _listChannels = listChannels,
        _addChannel = addChannel,
        _listRegions = listRegions,
        _addRegion = addRegion,
        _updateRegion = updateRegion,
        super(const StoreSettingsState()) {
    on<StoreSettingsRequested>(_onRequested);
    on<PoliciesSaveRequested>(_onPoliciesSave);
    on<LocationAddRequested>(_onLocationAdd);
    on<ChannelAddRequested>(_onChannelAdd);
    on<RegionAddRequested>(_onRegionAdd);
    on<RegionUpdateRequested>(_onRegionUpdate);
  }

  final GetStore _getStore;
  final UpdateStorePolicies _updatePolicies;
  final ListLocations _listLocations;
  final AddLocation _addLocation;
  final ListChannels _listChannels;
  final AddChannel _addChannel;
  final ListRegions _listRegions;
  final AddRegion _addRegion;
  final UpdateRegion _updateRegion;

  Future<void> _onRequested(
      StoreSettingsRequested event, Emitter<StoreSettingsState> emit) async {
    emit(state.copyWith(status: StoreSettingsStatus.loading, storeId: event.storeId));
    try {
      final results = await Future.wait<Object>([
        _getStore(event.storeId),
        _listLocations(),
        _listChannels(),
        _listRegions(),
      ]);
      emit(state.copyWith(
        status: StoreSettingsStatus.loaded,
        store: results[0] as Store,
        locations: (results[1] as List<StoreLocation>),
        channels: (results[2] as List<Channel>),
        regions: (results[3] as List<Region>),
      ));
    } on Failure catch (f) {
      emit(state.copyWith(status: StoreSettingsStatus.failure, error: f.message));
    }
  }

  Future<void> _onPoliciesSave(
      PoliciesSaveRequested event, Emitter<StoreSettingsState> emit) async {
    try {
      final store = await _updatePolicies(state.storeId, event.policies);
      emit(state.copyWith(status: StoreSettingsStatus.loaded, store: store));
    } on Failure catch (f) {
      emit(state.copyWith(status: StoreSettingsStatus.failure, error: f.message));
    }
  }

  Future<void> _onLocationAdd(
      LocationAddRequested event, Emitter<StoreSettingsState> emit) async {
    await _mutate(emit, () => _addLocation(event.label, event.kind, event.sellable));
  }

  Future<void> _onChannelAdd(
      ChannelAddRequested event, Emitter<StoreSettingsState> emit) async {
    await _mutate(emit, () => _addChannel(event.kind, event.label));
  }

  Future<void> _onRegionAdd(
      RegionAddRequested event, Emitter<StoreSettingsState> emit) async {
    await _mutate(emit, () => _addRegion(event.name, event.currency, event.tax, event.tenders));
  }

  Future<void> _onRegionUpdate(
      RegionUpdateRequested event, Emitter<StoreSettingsState> emit) async {
    await _mutate(
        emit, () => _updateRegion(event.id, event.name, event.tax, event.tenders));
  }

  Future<void> _mutate(
      Emitter<StoreSettingsState> emit, Future<Object> Function() action) async {
    try {
      await action();
      add(StoreSettingsRequested(state.storeId));
    } on Failure catch (f) {
      emit(state.copyWith(status: StoreSettingsStatus.failure, error: f.message));
    }
  }
}

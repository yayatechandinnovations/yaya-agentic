import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../../catalog/domain/entities/catalog_entities.dart';
import '../../../catalog/domain/usecases/catalog_usecases.dart';
import '../../../stores/domain/entities/store_entities.dart';
import '../../../stores/domain/usecases/store_usecases.dart';
import '../../domain/entities/pricing_entities.dart';
import '../../domain/usecases/pricing_usecases.dart';

part 'pricing_event.dart';
part 'pricing_state.dart';

class PricingBloc extends Bloc<PricingEvent, PricingState> {
  PricingBloc({
    required ListPriceLists listPriceLists,
    required CreatePriceList createPriceList,
    required UpdatePriceList updatePriceList,
    required ListPromotions listPromotions,
    required CreatePromotion createPromotion,
    required TogglePromotion togglePromotion,
    required ListRegions listRegions,
    required ListChannels listChannels,
    required ListCatalogs listCatalogs,
    required ListProducts listProducts,
  })  : _listPriceLists = listPriceLists,
        _createPriceList = createPriceList,
        _updatePriceList = updatePriceList,
        _listPromotions = listPromotions,
        _createPromotion = createPromotion,
        _togglePromotion = togglePromotion,
        _listRegions = listRegions,
        _listChannels = listChannels,
        _listCatalogs = listCatalogs,
        _listProducts = listProducts,
        super(const PricingState()) {
    on<PricingRequested>(_onRequested);
    on<PriceListCreateRequested>(_onCreateList);
    on<PriceListUpdateRequested>(_onUpdateList);
    on<PromotionCreateRequested>(_onCreatePromo);
    on<PromotionToggleRequested>(_onTogglePromo);
  }

  final ListPriceLists _listPriceLists;
  final CreatePriceList _createPriceList;
  final UpdatePriceList _updatePriceList;
  final ListPromotions _listPromotions;
  final CreatePromotion _createPromotion;
  final TogglePromotion _togglePromotion;
  final ListRegions _listRegions;
  final ListChannels _listChannels;
  final ListCatalogs _listCatalogs;
  final ListProducts _listProducts;

  Future<void> _onRequested(PricingRequested event, Emitter<PricingState> emit) async {
    emit(state.copyWith(status: PricingStatus.loading));
    try {
      final results = await Future.wait<Object>([
        _listPriceLists(),
        _listPromotions(),
        _listRegions(),
        _listChannels(),
        _loadSkuOptions(),
      ]);
      emit(state.copyWith(
        status: PricingStatus.loaded,
        priceLists: results[0] as List<PriceList>,
        promotions: results[1] as List<Promotion>,
        regions: results[2] as List<Region>,
        channels: results[3] as List<Channel>,
        skuOptions: results[4] as List<Variant>,
      ));
    } on Failure catch (f) {
      emit(state.copyWith(status: PricingStatus.failure, error: f.message));
    }
  }

  /// Flattens every catalog's product variants into the SKU picker options.
  Future<List<Variant>> _loadSkuOptions() async {
    final catalogs = await _listCatalogs();
    final variants = <Variant>[];
    for (final catalog in catalogs) {
      final products = await _listProducts(catalog.id);
      for (final product in products) {
        variants.addAll(product.variants);
      }
    }
    return variants;
  }

  Future<void> _onCreateList(
      PriceListCreateRequested event, Emitter<PricingState> emit) async {
    await _mutate(emit,
        () => _createPriceList(event.regionId, event.channelId, event.customerGroup, event.rules));
  }

  Future<void> _onUpdateList(
      PriceListUpdateRequested event, Emitter<PricingState> emit) async {
    await _mutate(
        emit, () => _updatePriceList(event.id, rules: event.rules, active: event.active));
  }

  Future<void> _onCreatePromo(
      PromotionCreateRequested event, Emitter<PricingState> emit) async {
    await _mutate(
        emit,
        () => _createPromotion(
              kind: event.kind,
              bps: event.bps,
              amountMinor: event.amountMinor,
              minSubtotalMinor: event.minSubtotalMinor,
              skus: event.skus,
              code: event.code,
              startsAt: event.startsAt,
              endsAt: event.endsAt,
            ));
  }

  Future<void> _onTogglePromo(
      PromotionToggleRequested event, Emitter<PricingState> emit) async {
    await _mutate(emit, () => _togglePromotion(event.id, event.active));
  }

  Future<void> _mutate(Emitter<PricingState> emit, Future<Object> Function() action) async {
    try {
      await action();
      add(const PricingRequested());
    } on Failure catch (f) {
      emit(state.copyWith(status: PricingStatus.failure, error: f.message));
    }
  }
}

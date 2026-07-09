import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
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
  })  : _listPriceLists = listPriceLists,
        _createPriceList = createPriceList,
        _updatePriceList = updatePriceList,
        _listPromotions = listPromotions,
        _createPromotion = createPromotion,
        _togglePromotion = togglePromotion,
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

  Future<void> _onRequested(PricingRequested event, Emitter<PricingState> emit) async {
    emit(state.copyWith(status: PricingStatus.loading));
    try {
      final results = await Future.wait<Object>([_listPriceLists(), _listPromotions()]);
      emit(state.copyWith(
        status: PricingStatus.loaded,
        priceLists: results[0] as List<PriceList>,
        promotions: results[1] as List<Promotion>,
      ));
    } on Failure catch (f) {
      emit(state.copyWith(status: PricingStatus.failure, error: f.message));
    }
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

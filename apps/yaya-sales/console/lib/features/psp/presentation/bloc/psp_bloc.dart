import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/psp_entities.dart';
import '../../domain/usecases/psp_usecases.dart';

part 'psp_event.dart';
part 'psp_state.dart';

/// PSP onboarding: provider cards, connect (hosted onboarding URL), the
/// redirect-return reconciliation, disconnect. Platform credentials never
/// reach the console — only the hosted action URL does.
class PspBloc extends Bloc<PspEvent, PspState> {
  PspBloc({
    required ListPspAccounts listAccounts,
    required BeginPspOnboarding beginOnboarding,
    required ReconcilePspReturn reconcileReturn,
    required DisconnectPsp disconnect,
  })  : _listAccounts = listAccounts,
        _beginOnboarding = beginOnboarding,
        _reconcileReturn = reconcileReturn,
        _disconnect = disconnect,
        super(const PspState()) {
    on<PspStatusRequested>(_onStatusRequested);
    on<PspConnectRequested>(_onConnect);
    on<PspReturnReceived>(_onReturn);
    on<PspDisconnectRequested>(_onDisconnect);
  }

  final ListPspAccounts _listAccounts;
  final BeginPspOnboarding _beginOnboarding;
  final ReconcilePspReturn _reconcileReturn;
  final DisconnectPsp _disconnect;

  Future<void> _onStatusRequested(
      PspStatusRequested event, Emitter<PspState> emit) async {
    emit(state.copyWith(status: PspStatus.loading));
    try {
      final accounts = await _listAccounts();
      emit(state.copyWith(status: PspStatus.loaded, accounts: accounts));
    } on Failure catch (f) {
      emit(state.copyWith(status: PspStatus.failure, error: f.message));
    }
  }

  Future<void> _onConnect(PspConnectRequested event, Emitter<PspState> emit) async {
    try {
      final link = await _beginOnboarding(event.provider, event.returnUrl);
      emit(state.copyWith(onboardingLink: link));
    } on Failure catch (f) {
      emit(state.copyWith(status: PspStatus.failure, error: f.message));
    }
  }

  Future<void> _onReturn(PspReturnReceived event, Emitter<PspState> emit) async {
    try {
      final account = await _reconcileReturn(event.provider, event.params);
      emit(state.copyWith(reconciled: account));
      add(const PspStatusRequested());
    } on Failure catch (f) {
      emit(state.copyWith(status: PspStatus.failure, error: f.message));
    }
  }

  Future<void> _onDisconnect(PspDisconnectRequested event, Emitter<PspState> emit) async {
    try {
      await _disconnect(event.provider);
      add(const PspStatusRequested());
    } on Failure catch (f) {
      emit(state.copyWith(status: PspStatus.failure, error: f.message));
    }
  }
}

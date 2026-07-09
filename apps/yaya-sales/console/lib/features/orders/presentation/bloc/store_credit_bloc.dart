import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/order_entities.dart';
import '../../domain/usecases/order_usecases.dart';

part 'store_credit_event.dart';
part 'store_credit_state.dart';

/// Store-credit desk: balance lookup + manual issue.
class StoreCreditBloc extends Bloc<StoreCreditEvent, StoreCreditState> {
  StoreCreditBloc({
    required GetStoreCreditBalance getBalance,
    required IssueStoreCredit issueCredit,
  })  : _getBalance = getBalance,
        _issueCredit = issueCredit,
        super(const StoreCreditState()) {
    on<BalanceLookupRequested>(_onLookup);
    on<CreditIssueRequested>(_onIssue);
  }

  final GetStoreCreditBalance _getBalance;
  final IssueStoreCredit _issueCredit;

  Future<void> _onLookup(
      BalanceLookupRequested event, Emitter<StoreCreditState> emit) async {
    emit(state.copyWith(status: StoreCreditStatus.loading));
    try {
      final balance = await _getBalance(event.customerRef, event.currency);
      emit(state.copyWith(status: StoreCreditStatus.loaded, balance: balance));
    } on Failure catch (f) {
      emit(state.copyWith(status: StoreCreditStatus.failure, error: f.message));
    }
  }

  Future<void> _onIssue(CreditIssueRequested event, Emitter<StoreCreditState> emit) async {
    try {
      await _issueCredit(event.customerRef, event.amountMinor, event.currency, event.reason);
      add(BalanceLookupRequested(event.customerRef, event.currency));
    } on Failure catch (f) {
      emit(state.copyWith(status: StoreCreditStatus.failure, error: f.message));
    }
  }
}

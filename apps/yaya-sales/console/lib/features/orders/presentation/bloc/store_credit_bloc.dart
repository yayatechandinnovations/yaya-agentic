import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../../customers/domain/entities/customer_entities.dart';
import '../../../customers/domain/usecases/customer_usecases.dart';
import '../../domain/entities/order_entities.dart';
import '../../domain/usecases/order_usecases.dart';

part 'store_credit_event.dart';
part 'store_credit_state.dart';

/// Store-credit desk: balance lookup + manual issue.
class StoreCreditBloc extends Bloc<StoreCreditEvent, StoreCreditState> {
  StoreCreditBloc({
    required GetStoreCreditBalance getBalance,
    required IssueStoreCredit issueCredit,
    required SearchCustomers searchCustomers,
  })  : _getBalance = getBalance,
        _issueCredit = issueCredit,
        _searchCustomers = searchCustomers,
        super(const StoreCreditState()) {
    on<StoreCreditContextRequested>(_onContext);
    on<BalanceLookupRequested>(_onLookup);
    on<CreditIssueRequested>(_onIssue);
  }

  final GetStoreCreditBalance _getBalance;
  final IssueStoreCredit _issueCredit;
  final SearchCustomers _searchCustomers;

  Future<void> _onContext(
      StoreCreditContextRequested event, Emitter<StoreCreditState> emit) async {
    try {
      final customers = await _searchCustomers();
      emit(state.copyWith(customers: customers));
    } on Failure catch (f) {
      emit(state.copyWith(status: StoreCreditStatus.failure, error: f.message));
    }
  }

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

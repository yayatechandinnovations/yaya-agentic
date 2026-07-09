part of 'store_credit_bloc.dart';

sealed class StoreCreditEvent extends Equatable {
  const StoreCreditEvent();

  @override
  List<Object?> get props => [];
}

class BalanceLookupRequested extends StoreCreditEvent {
  const BalanceLookupRequested(this.customerRef, this.currency);

  final String customerRef;
  final String currency;

  @override
  List<Object?> get props => [customerRef, currency];
}

class CreditIssueRequested extends StoreCreditEvent {
  const CreditIssueRequested(this.customerRef, this.amountMinor, this.currency, this.reason);

  final String customerRef;
  final int amountMinor;
  final String currency;
  final String? reason;

  @override
  List<Object?> get props => [customerRef, amountMinor, currency, reason];
}

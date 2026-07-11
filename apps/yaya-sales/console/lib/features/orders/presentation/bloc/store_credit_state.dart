part of 'store_credit_bloc.dart';

enum StoreCreditStatus { initial, loading, loaded, failure }

class StoreCreditState extends Equatable {
  const StoreCreditState({
    this.status = StoreCreditStatus.initial,
    this.balance,
    this.customers = const [],
    this.error,
  });

  final StoreCreditStatus status;
  final StoreCreditBalance? balance;
  final List<Customer> customers;
  final String? error;

  StoreCreditState copyWith({
    StoreCreditStatus? status,
    StoreCreditBalance? balance,
    List<Customer>? customers,
    String? error,
  }) =>
      StoreCreditState(
        status: status ?? this.status,
        balance: balance ?? this.balance,
        customers: customers ?? this.customers,
        error: error,
      );

  @override
  List<Object?> get props => [status, balance, customers, error];
}

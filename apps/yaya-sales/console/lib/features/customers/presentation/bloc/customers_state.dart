part of 'customers_bloc.dart';

enum CustomersStatus { initial, loading, loaded, failure }

class CustomersState extends Equatable {
  const CustomersState({
    this.status = CustomersStatus.initial,
    this.customers = const [],
    this.query,
    this.message,
    this.error,
  });

  final CustomersStatus status;
  final List<Customer> customers;
  final String? query;
  final String? message;
  final String? error;

  CustomersState copyWith({
    CustomersStatus? status,
    List<Customer>? customers,
    String? query,
    String? message,
    String? error,
  }) =>
      CustomersState(
        status: status ?? this.status,
        customers: customers ?? this.customers,
        query: query ?? this.query,
        message: message,
        error: error,
      );

  @override
  List<Object?> get props => [status, customers, query, message, error];
}

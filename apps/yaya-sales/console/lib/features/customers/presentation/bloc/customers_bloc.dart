import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/customer_entities.dart';
import '../../domain/usecases/customer_usecases.dart';

part 'customers_event.dart';
part 'customers_state.dart';

/// The customer directory: search, profile CRUD, and structured addresses.
class CustomersBloc extends Bloc<CustomersEvent, CustomersState> {
  CustomersBloc({
    required SearchCustomers searchCustomers,
    required CreateCustomer createCustomer,
    required UpdateCustomer updateCustomer,
    required DeleteCustomer deleteCustomer,
    required AddCustomerAddress addAddress,
    required RemoveCustomerAddress removeAddress,
  })  : _search = searchCustomers,
        _create = createCustomer,
        _update = updateCustomer,
        _delete = deleteCustomer,
        _addAddress = addAddress,
        _removeAddress = removeAddress,
        super(const CustomersState()) {
    on<CustomersRequested>(_onRequested);
    on<CustomerCreateRequested>(_onCreate);
    on<CustomerUpdateRequested>(_onUpdate);
    on<CustomerDeleteRequested>(_onDelete);
    on<CustomerAddressAdded>(_onAddressAdded);
    on<CustomerAddressRemoved>(_onAddressRemoved);
  }

  final SearchCustomers _search;
  final CreateCustomer _create;
  final UpdateCustomer _update;
  final DeleteCustomer _delete;
  final AddCustomerAddress _addAddress;
  final RemoveCustomerAddress _removeAddress;

  Future<void> _onRequested(CustomersRequested event, Emitter<CustomersState> emit) async {
    emit(state.copyWith(status: CustomersStatus.loading, query: event.q));
    try {
      final customers = await _search(event.q);
      emit(state.copyWith(status: CustomersStatus.loaded, customers: customers));
    } on Failure catch (f) {
      emit(state.copyWith(status: CustomersStatus.failure, error: f.message));
    }
  }

  Future<void> _onCreate(CustomerCreateRequested event, Emitter<CustomersState> emit) async {
    try {
      final created = await _create(event.draft);
      emit(state.copyWith(message: 'Customer "${created.displayName}" created.'));
      add(CustomersRequested(state.query));
    } on Failure catch (f) {
      emit(state.copyWith(status: CustomersStatus.failure, error: f.message));
    }
  }

  Future<void> _onUpdate(CustomerUpdateRequested event, Emitter<CustomersState> emit) async {
    try {
      final updated = await _update(event.id, event.draft);
      _swap(emit, updated, message: 'Customer updated.');
    } on Failure catch (f) {
      emit(state.copyWith(status: CustomersStatus.failure, error: f.message));
    }
  }

  Future<void> _onDelete(CustomerDeleteRequested event, Emitter<CustomersState> emit) async {
    try {
      await _delete(event.id);
      emit(state.copyWith(message: 'Customer deleted.'));
      add(CustomersRequested(state.query));
    } on Failure catch (f) {
      emit(state.copyWith(status: CustomersStatus.failure, error: f.message));
    }
  }

  Future<void> _onAddressAdded(CustomerAddressAdded event, Emitter<CustomersState> emit) async {
    try {
      final updated = await _addAddress(event.id, event.kind, event.address);
      _swap(emit, updated, message: 'Address added.');
    } on Failure catch (f) {
      emit(state.copyWith(status: CustomersStatus.failure, error: f.message));
    }
  }

  Future<void> _onAddressRemoved(
      CustomerAddressRemoved event, Emitter<CustomersState> emit) async {
    try {
      final updated = await _removeAddress(event.id, event.addressId);
      _swap(emit, updated, message: 'Address removed.');
    } on Failure catch (f) {
      emit(state.copyWith(status: CustomersStatus.failure, error: f.message));
    }
  }

  /// In-place swap keeps the list stable (no loading flicker) after row edits.
  void _swap(Emitter<CustomersState> emit, Customer updated, {String? message}) {
    emit(state.copyWith(
      customers: state.customers.map((c) => c.id == updated.id ? updated : c).toList(),
      message: message,
    ));
  }
}

part of 'customers_bloc.dart';

sealed class CustomersEvent extends Equatable {
  const CustomersEvent();

  @override
  List<Object?> get props => [];
}

class CustomersRequested extends CustomersEvent {
  const CustomersRequested([this.q]);

  final String? q;

  @override
  List<Object?> get props => [q];
}

class CustomerCreateRequested extends CustomersEvent {
  const CustomerCreateRequested(this.draft);

  final CustomerDraft draft;

  @override
  List<Object?> get props => [draft];
}

class CustomerUpdateRequested extends CustomersEvent {
  const CustomerUpdateRequested(this.id, this.draft);

  final String id;
  final CustomerDraft draft;

  @override
  List<Object?> get props => [id, draft];
}

class CustomerDeleteRequested extends CustomersEvent {
  const CustomerDeleteRequested(this.id);

  final String id;

  @override
  List<Object?> get props => [id];
}

class CustomerAddressAdded extends CustomersEvent {
  const CustomerAddressAdded(this.id, this.kind, this.address);

  final String id;
  final String kind;
  final Map<String, dynamic> address;

  @override
  List<Object?> get props => [id, kind, address];
}

class CustomerAddressRemoved extends CustomersEvent {
  const CustomerAddressRemoved(this.id, this.addressId);

  final String id;
  final String addressId;

  @override
  List<Object?> get props => [id, addressId];
}

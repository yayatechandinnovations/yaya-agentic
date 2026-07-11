import '../entities/customer_entities.dart';
import '../repositories/customer_repository.dart';

class SearchCustomers {
  const SearchCustomers(this._repo);
  final CustomerRepository _repo;
  Future<List<Customer>> call([String? q]) => _repo.search(q);
}

class CreateCustomer {
  const CreateCustomer(this._repo);
  final CustomerRepository _repo;
  Future<Customer> call(CustomerDraft draft) => _repo.create(draft);
}

class UpdateCustomer {
  const UpdateCustomer(this._repo);
  final CustomerRepository _repo;
  Future<Customer> call(String id, CustomerDraft draft) => _repo.update(id, draft);
}

class DeleteCustomer {
  const DeleteCustomer(this._repo);
  final CustomerRepository _repo;
  Future<void> call(String id) => _repo.delete(id);
}

class AddCustomerAddress {
  const AddCustomerAddress(this._repo);
  final CustomerRepository _repo;
  Future<Customer> call(String id, String kind, Map<String, dynamic> address) =>
      _repo.addAddress(id, kind, address);
}

class RemoveCustomerAddress {
  const RemoveCustomerAddress(this._repo);
  final CustomerRepository _repo;
  Future<Customer> call(String id, String addressId) => _repo.removeAddress(id, addressId);
}

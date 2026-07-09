import '../entities/purchasing_entities.dart';
import '../repositories/purchasing_repository.dart';

class ListSuppliers {
  const ListSuppliers(this._repo);
  final PurchasingRepository _repo;
  Future<List<Supplier>> call() => _repo.suppliers();
}

class CreateSupplier {
  const CreateSupplier(this._repo);
  final PurchasingRepository _repo;
  Future<Supplier> call(String name, Map<String, dynamic> contact) =>
      _repo.createSupplier(name, contact);
}

class UpdateSupplier {
  const UpdateSupplier(this._repo);
  final PurchasingRepository _repo;
  Future<Supplier> call(String id, String name, Map<String, dynamic> contact) =>
      _repo.updateSupplier(id, name, contact);
}

class DeleteSupplier {
  const DeleteSupplier(this._repo);
  final PurchasingRepository _repo;
  Future<void> call(String id) => _repo.deleteSupplier(id);
}

class ListPurchaseOrders {
  const ListPurchaseOrders(this._repo);
  final PurchasingRepository _repo;
  Future<List<PurchaseOrder>> call({String? status}) => _repo.purchaseOrders(status: status);
}

class CreatePurchaseOrder {
  const CreatePurchaseOrder(this._repo);
  final PurchasingRepository _repo;
  Future<PurchaseOrder> call(
          String supplierId, String locationId, List<PoLine> lines, DateTime? expectedAt) =>
      _repo.createPo(supplierId, locationId, lines, expectedAt);
}

class PlacePurchaseOrder {
  const PlacePurchaseOrder(this._repo);
  final PurchasingRepository _repo;
  Future<PurchaseOrder> call(String poId) => _repo.place(poId);
}

class CancelPurchaseOrder {
  const CancelPurchaseOrder(this._repo);
  final PurchasingRepository _repo;
  Future<PurchaseOrder> call(String poId) => _repo.cancel(poId);
}

class ReceivePurchaseOrder {
  const ReceivePurchaseOrder(this._repo);
  final PurchasingRepository _repo;
  Future<PurchaseOrder> call(String poId, List<PoLine> lines) => _repo.receive(poId, lines);
}

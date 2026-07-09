import '../entities/inventory_entities.dart';
import '../repositories/inventory_repository.dart';

class ListLevels {
  const ListLevels(this._repo);
  final InventoryRepository _repo;
  Future<List<StockLevel>> call({String? location, bool lowOnly = false}) =>
      _repo.levels(location: location, lowOnly: lowOnly);
}

class ReadLedger {
  const ReadLedger(this._repo);
  final InventoryRepository _repo;
  Future<List<Movement>> call(String sku, {String? location}) =>
      _repo.ledger(sku, location: location);
}

class ReceiveStock {
  const ReceiveStock(this._repo);
  final InventoryRepository _repo;
  Future<void> call(String sku, String location, int qty, int unitCostMinor,
          String currency, String? poRef) =>
      _repo.receive(sku, location, qty, unitCostMinor, currency, poRef);
}

class AdjustStock {
  const AdjustStock(this._repo);
  final InventoryRepository _repo;
  Future<void> call(String sku, String location, int delta, String reason) =>
      _repo.adjust(sku, location, delta, reason);
}

class TransferStock {
  const TransferStock(this._repo);
  final InventoryRepository _repo;
  Future<void> call(String sku, String from, String to, int qty) =>
      _repo.transfer(sku, from, to, qty);
}

class SetReorderPolicy {
  const SetReorderPolicy(this._repo);
  final InventoryRepository _repo;
  Future<void> call(String sku, String location, int point, int qty) =>
      _repo.setReorder(sku, location, point, qty);
}

class SetBackorderPolicy {
  const SetBackorderPolicy(this._repo);
  final InventoryRepository _repo;
  Future<void> call(String sku, String location, String policy) =>
      _repo.setBackorder(sku, location, policy);
}

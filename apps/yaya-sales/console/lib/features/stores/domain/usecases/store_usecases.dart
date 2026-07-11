import '../entities/store_entities.dart';
import '../repositories/store_repository.dart';

class ListStores {
  const ListStores(this._repo);
  final StoreRepository _repo;
  Future<List<Store>> call(String tenantId) => _repo.storesOf(tenantId);
}

class CreateStore {
  const CreateStore(this._repo);
  final StoreRepository _repo;
  Future<Store> call(String tenantId, String displayName, String currency) =>
      _repo.createStore(tenantId, displayName, currency);
}

class GetStore {
  const GetStore(this._repo);
  final StoreRepository _repo;
  Future<Store> call(String id) => _repo.store(id);
}

class UpdateStorePolicies {
  const UpdateStorePolicies(this._repo);
  final StoreRepository _repo;
  Future<Store> call(String id, StorePolicies policies) => _repo.updatePolicies(id, policies);
}

class ListLocations {
  const ListLocations(this._repo);
  final StoreRepository _repo;
  Future<List<StoreLocation>> call() => _repo.locations();
}

class AddLocation {
  const AddLocation(this._repo);
  final StoreRepository _repo;
  Future<StoreLocation> call(String label, String kind, bool sellable,
          {Map<String, dynamic>? address}) =>
      _repo.addLocation(label, kind, sellable, address: address);
}

class UpdateLocation {
  const UpdateLocation(this._repo);
  final StoreRepository _repo;
  Future<StoreLocation> call(String id,
          {String? label, String? kind, bool? sellable, Map<String, dynamic>? address}) =>
      _repo.updateLocation(id,
          label: label, kind: kind, sellable: sellable, address: address);
}

class ListChannels {
  const ListChannels(this._repo);
  final StoreRepository _repo;
  Future<List<Channel>> call() => _repo.channels();
}

class AddChannel {
  const AddChannel(this._repo);
  final StoreRepository _repo;
  Future<Channel> call(String kind, String? label) => _repo.addChannel(kind, label);
}

class ListRegions {
  const ListRegions(this._repo);
  final StoreRepository _repo;
  Future<List<Region>> call() => _repo.regions();
}

class AddRegion {
  const AddRegion(this._repo);
  final StoreRepository _repo;
  Future<Region> call(String name, String currency, TaxPolicy tax, List<String> tenders) =>
      _repo.addRegion(name, currency, tax, tenders);
}

class UpdateRegion {
  const UpdateRegion(this._repo);
  final StoreRepository _repo;
  Future<Region> call(String id, String name, TaxPolicy tax, List<String> tenders) =>
      _repo.updateRegion(id, name, tax, tenders);
}

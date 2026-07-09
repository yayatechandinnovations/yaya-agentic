import '../entities/organization.dart';
import '../repositories/org_repository.dart';

class ListOrgs {
  const ListOrgs(this._repo);
  final OrgRepository _repo;
  Future<List<Organization>> call() => _repo.list();
}

class CreateOrg {
  const CreateOrg(this._repo);
  final OrgRepository _repo;
  Future<Organization> call(String name) => _repo.create(name);
}

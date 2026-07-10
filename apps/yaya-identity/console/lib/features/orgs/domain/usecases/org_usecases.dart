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
  Future<Organization> call(OrgDraft draft) => _repo.create(draft);
}

class UpdateOrg {
  const UpdateOrg(this._repo);
  final OrgRepository _repo;
  Future<Organization> call(String orgId, OrgDraft draft) => _repo.update(orgId, draft);
}

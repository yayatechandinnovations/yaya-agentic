import '../entities/organization.dart';

abstract class OrgRepository {
  Future<List<Organization>> list();
  Future<Organization> create(OrgDraft draft);
  Future<Organization> update(String orgId, OrgDraft draft);
}

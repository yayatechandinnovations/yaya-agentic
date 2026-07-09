import '../entities/organization.dart';

abstract class OrgRepository {
  Future<List<Organization>> list();
  Future<Organization> create(String name);
}

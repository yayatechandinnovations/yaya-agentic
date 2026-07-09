import '../entities/audit_row.dart';

abstract class AuditRepository {
  Future<List<AuditRow>> read(String org, {int limit});
}

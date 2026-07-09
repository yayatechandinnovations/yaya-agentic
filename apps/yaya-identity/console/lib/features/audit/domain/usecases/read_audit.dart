import '../entities/audit_row.dart';
import '../repositories/audit_repository.dart';

class ReadAudit {
  const ReadAudit(this._repo);
  final AuditRepository _repo;
  Future<List<AuditRow>> call(String org, {int limit = 50}) => _repo.read(org, limit: limit);
}

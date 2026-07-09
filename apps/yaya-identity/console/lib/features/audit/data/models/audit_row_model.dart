import '../../domain/entities/audit_row.dart';

class AuditRowModel {
  static AuditRow fromJson(Map<String, dynamic> json) => AuditRow(
        eventType: json['eventType'] as String? ?? '',
        subject: json['subject'] as String?,
        action: json['action'] as String?,
        resource: json['resource'] as String?,
        decision: json['decision'] as String?,
        userReason: json['userReason'] as String?,
        auditReason: json['auditReason'] as String?,
        at: json['at'] as String?,
      );
}

import 'package:equatable/equatable.dart';

/// One append-only audit entry as the console shows it.
class AuditRow extends Equatable {
  const AuditRow({
    required this.eventType,
    this.subject,
    this.action,
    this.resource,
    this.decision,
    this.userReason,
    this.auditReason,
    this.at,
  });

  final String eventType;
  final String? subject;
  final String? action;
  final String? resource;
  final String? decision; // PERMIT | DENY | null
  final String? userReason;
  final String? auditReason;
  final String? at;

  bool get isDeny => decision == 'DENY';

  @override
  List<Object?> get props =>
      [eventType, subject, action, resource, decision, userReason, auditReason, at];
}

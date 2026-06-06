import 'package:freezed_annotation/freezed_annotation.dart';

part 'audit.freezed.dart';
part 'audit.g.dart';

@freezed
class AuthzAuditEntry with _$AuthzAuditEntry {
  const factory AuthzAuditEntry({
    required int id,
    required String tenant,
    String? sessionId,
    String? turnId,
    String? principal,
    String? toolId,
    required String decision,
    String? userReason,
    String? auditReason,
    @Default(<String, dynamic>{}) Map<String, dynamic> policyTrace,
    String? createdAt,
  }) = _AuthzAuditEntry;
  factory AuthzAuditEntry.fromJson(Map<String, dynamic> json) =>
      _$AuthzAuditEntryFromJson(json);
}

@freezed
class AuthzAuditPage with _$AuthzAuditPage {
  const factory AuthzAuditPage({
    @Default(<AuthzAuditEntry>[]) List<AuthzAuditEntry> items,
    required int page,
    required int pageSize,
    required int total,
  }) = _AuthzAuditPage;
  factory AuthzAuditPage.fromJson(Map<String, dynamic> json) =>
      _$AuthzAuditPageFromJson(json);
}

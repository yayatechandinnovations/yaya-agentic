import 'package:freezed_annotation/freezed_annotation.dart';

part 'clone.freezed.dart';
part 'clone.g.dart';

/// Mirrors backend tenant.clone.CloneRequest.
@freezed
class CloneRequest with _$CloneRequest {
  const factory CloneRequest({
    required String destinationTenant,
    String? destinationProfileId,
    @Default('FAIL') String conflictPolicy,           // FAIL | SKIP | NEW_VERSION
    @Default('RETAIN') String knowledgeLocationStrategy, // RETAIN | TEMPLATE | OMIT
    @Default('AUTO') String personalityPolicy,        // AUTO | ALWAYS | NEVER
    @Default(true) bool dryRun,
  }) = _CloneRequest;
  factory CloneRequest.fromJson(Map<String, dynamic> json) =>
      _$CloneRequestFromJson(json);
}

@freezed
class ResourceAction with _$ResourceAction {
  const factory ResourceAction({
    required String id,
    required String action,
    int? fromVersion,
    int? toVersion,
    @Default(<String>[]) List<String> notes,
  }) = _ResourceAction;
  factory ResourceAction.fromJson(Map<String, dynamic> json) =>
      _$ResourceActionFromJson(json);
}

@freezed
class KnowledgeAction with _$KnowledgeAction {
  const factory KnowledgeAction({
    required String id,
    required String action,
    int? fromVersion,
    int? toVersion,
    String? locationKind,
    @Default(<String, dynamic>{}) Map<String, dynamic> location,
    @Default(<String>[]) List<String> notes,
  }) = _KnowledgeAction;
  factory KnowledgeAction.fromJson(Map<String, dynamic> json) =>
      _$KnowledgeActionFromJson(json);
}

@freezed
class PersonalityAction with _$PersonalityAction {
  const factory PersonalityAction({
    String? locale,
    required String action,
    int? fromVersion,
    int? toVersion,
    @Default(<String>[]) List<String> notes,
  }) = _PersonalityAction;
  factory PersonalityAction.fromJson(Map<String, dynamic> json) =>
      _$PersonalityActionFromJson(json);
}

@freezed
class ClonePlan with _$ClonePlan {
  const factory ClonePlan({
    required String sourceTenant,
    required String destinationTenant,
    required String destinationProfileId,
    required ResourceAction profile,
    @Default(<ResourceAction>[]) List<ResourceAction> capabilities,
    @Default(<ResourceAction>[]) List<ResourceAction> tools,
    @Default(<KnowledgeAction>[]) List<KnowledgeAction> knowledgeSources,
    @Default(<ResourceAction>[]) List<ResourceAction> authBindings,
    @Default(<ResourceAction>[]) List<ResourceAction> recordingStrategies,
    @Default(<PersonalityAction>[]) List<PersonalityAction> personality,
    @Default(<String>[]) List<String> warnings,
  }) = _ClonePlan;
  factory ClonePlan.fromJson(Map<String, dynamic> json) =>
      _$ClonePlanFromJson(json);
}

@freezed
class CloneResult with _$CloneResult {
  const factory CloneResult({
    String? jobId,
    required String status,                            // DRY_RUN | APPLIED | FAILED
    required ClonePlan plan,
    String? errorCode,
    String? errorMessage,
  }) = _CloneResult;
  factory CloneResult.fromJson(Map<String, dynamic> json) =>
      _$CloneResultFromJson(json);
}

/// AbsoluteToPathMigrator.Plan view.
@freezed
class MigrateToPathPlan with _$MigrateToPathPlan {
  const factory MigrateToPathPlan({
    @Default(<MigrateCandidate>[]) List<MigrateCandidate> candidates,
    @Default(<MigrateUnsafe>[]) List<MigrateUnsafe> unsafe,
  }) = _MigrateToPathPlan;
  factory MigrateToPathPlan.fromJson(Map<String, dynamic> json) =>
      _$MigrateToPathPlanFromJson(json);
}

@freezed
class MigrateCandidate with _$MigrateCandidate {
  const factory MigrateCandidate({
    required String toolId,
    required int version,
    required String current,
    required String rewritten,
  }) = _MigrateCandidate;
  factory MigrateCandidate.fromJson(Map<String, dynamic> json) =>
      _$MigrateCandidateFromJson(json);
}

@freezed
class MigrateUnsafe with _$MigrateUnsafe {
  const factory MigrateUnsafe({
    required String toolId,
    required int version,
    required String current,
    required String reason,
  }) = _MigrateUnsafe;
  factory MigrateUnsafe.fromJson(Map<String, dynamic> json) =>
      _$MigrateUnsafeFromJson(json);
}

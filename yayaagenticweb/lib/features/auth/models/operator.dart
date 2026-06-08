import 'package:freezed_annotation/freezed_annotation.dart';

part 'operator.freezed.dart';
part 'operator.g.dart';

/// Mirrors the backend's `AuthController.MeResponse`. Field names match
/// what /v1/auth/me returns so Jackson's default Java-record JSON serialisation
/// (camelCase keys) lines up without a custom converter.
@freezed
class Operator with _$Operator {
  const factory Operator({
    required String subject,
    required String displayName,
    required String source,
    @Default(<String, dynamic>{}) Map<String, dynamic> attributes,
    DateTime? verifiedAt,
  }) = _Operator;

  factory Operator.fromJson(Map<String, dynamic> json) => _$OperatorFromJson(json);
}

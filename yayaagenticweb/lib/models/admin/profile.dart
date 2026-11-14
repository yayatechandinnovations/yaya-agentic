// @JsonKey on a freezed factory parameter is the documented freezed usage.
// ignore_for_file: invalid_annotation_target

import 'package:freezed_annotation/freezed_annotation.dart';

part 'profile.freezed.dart';
part 'profile.g.dart';

@freezed
class ProfileRequest with _$ProfileRequest {
  const factory ProfileRequest({
    required String tenant,
    required String id,
    required String displayName,
    required String introOneLiner,
    required String systemPromptFragment,
    @Default(<String>[]) List<String> capabilities,
    String? authBindingId,
    @Default('en') String language,
    @Default(<String, dynamic>{}) Map<String, dynamic> metadata,
    // App-specific traits keyed by namespaced kind -> opaque payload. The store
    // binding rides under the yaya-sales-owned `sales.store` kind; the framework
    // never interprets it. Catalogs/pricing/PSP live in yaya-sales.
    Map<String, dynamic>? extensions,
    // Default Session Start Flow id (null = none).
    String? defaultStartFlowId,
    // Model for this profile's turns (null = the deployment's model). The
    // server refuses a model that is not on its allow-list.
    String? model,
    // Tool rounds per turn, 1..16 (null = the server default, 5).
    int? maxToolRounds,
    // True = turns count against, and are refused by, the tenant's monthly
    // allowance.
    bool? metered,
    // The source profile exactly as the admin API returned it, when this
    // request is a new version of an existing profile ("clone & edit"). Not a
    // wire field: [ProfileRequestWire.toWireJson] sends back the keys of it
    // this model does not know, so a server field the console has no form
    // control (or no model field) for survives the round trip.
    @JsonKey(includeFromJson: false, includeToJson: false)
    @Default(<String, dynamic>{})
    Map<String, dynamic> carried,
  }) = _ProfileRequest;

  factory ProfileRequest.fromJson(Map<String, dynamic> json) =>
      _$ProfileRequestFromJson(json);
}

extension ProfileRequestWire on ProfileRequest {
  /// Keys of a profile response the server assigns; never sent back.
  static const serverOwnedKeys = {'version', 'status', 'createdAt'};

  /// The POST body: every field of this request, plus the [carried] keys the
  /// request model does not know. A request field always wins over a carried
  /// one, so what the form edits is what gets saved.
  Map<String, dynamic> toWireJson() {
    final own = toJson();
    return {
      for (final e in carried.entries)
        if (!serverOwnedKeys.contains(e.key) && !own.containsKey(e.key))
          e.key: e.value,
      ...own,
    };
  }
}

@freezed
class ProfileResponse with _$ProfileResponse {
  const factory ProfileResponse({
    required String id,
    required int version,
    required String tenant,
    required String displayName,
    required String introOneLiner,
    required String systemPromptFragment,
    @Default(<String>[]) List<String> capabilities,
    String? authBindingId,
    @Default('en') String language,
    @Default(<String, dynamic>{}) Map<String, dynamic> metadata,
    String? status,
    String? createdAt,
    Map<String, dynamic>? extensions,
    String? defaultStartFlowId,
    String? model,
    int? maxToolRounds,
    @Default(false) bool metered,
    // The response body as received, including keys this model has no field
    // for. Kept so "clone & edit" can send them back (see ProfileRequest).
    @JsonKey(includeFromJson: false, includeToJson: false)
    @Default(<String, dynamic>{})
    Map<String, dynamic> raw,
  }) = _ProfileResponse;

  factory ProfileResponse.fromJson(Map<String, dynamic> json) =>
      _$ProfileResponseFromJson(json).copyWith(raw: json);
}

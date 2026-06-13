import 'package:freezed_annotation/freezed_annotation.dart';

part 'act_as.freezed.dart';
part 'act_as.g.dart';

/// Operator-supplied end-user credential for a playground session. See
/// docs/design/playground-actas-auth-design.md §4. Serialises to the
/// polymorphic JSON shape the backend's Jackson layer expects:
/// `{"kind":"raw-token","scheme":"Bearer","token":"…"}`.
///
/// `kind` is an explicit field with a default rather than just a freezed
/// `unionKey`, because freezed only honours `unionKey` on `fromJson` — its
/// generated `toJson` emits only the variant's own fields. The explicit
/// default keeps the field invisible at the call site while guaranteeing
/// the discriminator is on the wire.
///
/// v1 ships only the `rawToken` variant. `signedIdentity` and `serviceToken`
/// will land as additional union factories without breaking serialization.
@Freezed(unionKey: 'kind', unionValueCase: FreezedUnionCase.kebab)
class ActAs with _$ActAs {
  const factory ActAs.rawToken({
    @Default('raw-token') String kind,
    @Default('Bearer') String scheme,
    required String token,
  }) = ActAsRawToken;

  factory ActAs.fromJson(Map<String, dynamic> json) => _$ActAsFromJson(json);
}

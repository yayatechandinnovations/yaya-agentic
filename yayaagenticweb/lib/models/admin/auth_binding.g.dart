// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'auth_binding.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$AuthBindingRequestImpl _$$AuthBindingRequestImplFromJson(
  Map<String, dynamic> json,
) => _$AuthBindingRequestImpl(
  tenant: json['tenant'] as String,
  id: json['id'] as String,
  authenticatorRef: json['authenticatorRef'] as String,
  authorizerChain:
      (json['authorizerChain'] as List<dynamic>?)
          ?.map((e) => e as String)
          .toList() ??
      const <String>[],
);

Map<String, dynamic> _$$AuthBindingRequestImplToJson(
  _$AuthBindingRequestImpl instance,
) => <String, dynamic>{
  'tenant': instance.tenant,
  'id': instance.id,
  'authenticatorRef': instance.authenticatorRef,
  'authorizerChain': instance.authorizerChain,
};

_$AuthBindingResponseImpl _$$AuthBindingResponseImplFromJson(
  Map<String, dynamic> json,
) => _$AuthBindingResponseImpl(
  id: json['id'] as String,
  tenant: json['tenant'] as String,
  authenticatorRef: json['authenticatorRef'] as String,
  authorizerChain:
      (json['authorizerChain'] as List<dynamic>?)
          ?.map((e) => e as String)
          .toList() ??
      const <String>[],
  createdAt: json['createdAt'] as String?,
);

Map<String, dynamic> _$$AuthBindingResponseImplToJson(
  _$AuthBindingResponseImpl instance,
) => <String, dynamic>{
  'id': instance.id,
  'tenant': instance.tenant,
  'authenticatorRef': instance.authenticatorRef,
  'authorizerChain': instance.authorizerChain,
  'createdAt': instance.createdAt,
};

_$AuthAvailabilityImpl _$$AuthAvailabilityImplFromJson(
  Map<String, dynamic> json,
) => _$AuthAvailabilityImpl(
  authenticators:
      (json['authenticators'] as List<dynamic>?)
          ?.map((e) => e as String)
          .toList() ??
      const <String>[],
  authorizers:
      (json['authorizers'] as List<dynamic>?)
          ?.map((e) => e as String)
          .toList() ??
      const <String>[],
);

Map<String, dynamic> _$$AuthAvailabilityImplToJson(
  _$AuthAvailabilityImpl instance,
) => <String, dynamic>{
  'authenticators': instance.authenticators,
  'authorizers': instance.authorizers,
};

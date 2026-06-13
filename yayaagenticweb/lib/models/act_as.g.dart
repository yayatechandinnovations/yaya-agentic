// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'act_as.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$ActAsRawTokenImpl _$$ActAsRawTokenImplFromJson(Map<String, dynamic> json) =>
    _$ActAsRawTokenImpl(
      scheme: json['scheme'] as String? ?? 'Bearer',
      token: json['token'] as String,
    );

Map<String, dynamic> _$$ActAsRawTokenImplToJson(_$ActAsRawTokenImpl instance) =>
    <String, dynamic>{'scheme': instance.scheme, 'token': instance.token};

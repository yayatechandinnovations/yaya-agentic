// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'operator.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$OperatorImpl _$$OperatorImplFromJson(Map<String, dynamic> json) =>
    _$OperatorImpl(
      subject: json['subject'] as String,
      displayName: json['displayName'] as String,
      source: json['source'] as String,
      attributes:
          json['attributes'] as Map<String, dynamic>? ??
          const <String, dynamic>{},
      verifiedAt: json['verifiedAt'] == null
          ? null
          : DateTime.parse(json['verifiedAt'] as String),
    );

Map<String, dynamic> _$$OperatorImplToJson(_$OperatorImpl instance) =>
    <String, dynamic>{
      'subject': instance.subject,
      'displayName': instance.displayName,
      'source': instance.source,
      'attributes': instance.attributes,
      'verifiedAt': instance.verifiedAt?.toIso8601String(),
    };

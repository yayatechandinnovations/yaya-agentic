// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'start_session.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$StartSessionRequestImpl _$$StartSessionRequestImplFromJson(
  Map<String, dynamic> json,
) => _$StartSessionRequestImpl(
  tenant: json['tenant'] as String? ?? 'default',
  profileId: json['profileId'] as String? ?? 'hello-world',
  profileVersion: (json['profileVersion'] as num?)?.toInt() ?? 1,
  channel: json['channel'] as String? ?? 'web',
  hints: json['hints'] as Map<String, dynamic>? ?? const <String, dynamic>{},
  actAs: json['actAs'] == null
      ? null
      : ActAs.fromJson(json['actAs'] as Map<String, dynamic>),
);

Map<String, dynamic> _$$StartSessionRequestImplToJson(
  _$StartSessionRequestImpl instance,
) => <String, dynamic>{
  'tenant': instance.tenant,
  'profileId': instance.profileId,
  'profileVersion': instance.profileVersion,
  'channel': instance.channel,
  'hints': instance.hints,
  'actAs': instance.actAs,
};

_$StartSessionResponseImpl _$$StartSessionResponseImplFromJson(
  Map<String, dynamic> json,
) => _$StartSessionResponseImpl(
  sessionId: json['sessionId'] as String,
  profileId: json['profileId'] as String,
  profileVersion: (json['profileVersion'] as num).toInt(),
  greeting: json['greeting'] as String,
  quickReplies: (json['quickReplies'] as List<dynamic>)
      .map((e) => e as String)
      .toList(),
);

Map<String, dynamic> _$$StartSessionResponseImplToJson(
  _$StartSessionResponseImpl instance,
) => <String, dynamic>{
  'sessionId': instance.sessionId,
  'profileId': instance.profileId,
  'profileVersion': instance.profileVersion,
  'greeting': instance.greeting,
  'quickReplies': instance.quickReplies,
};

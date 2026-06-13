// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'start_session.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

StartSessionRequest _$StartSessionRequestFromJson(Map<String, dynamic> json) {
  return _StartSessionRequest.fromJson(json);
}

/// @nodoc
mixin _$StartSessionRequest {
  String get tenant => throw _privateConstructorUsedError;
  String get profileId => throw _privateConstructorUsedError;
  int get profileVersion => throw _privateConstructorUsedError;
  String get channel => throw _privateConstructorUsedError;
  Map<String, dynamic> get hints => throw _privateConstructorUsedError;
  ActAs? get actAs => throw _privateConstructorUsedError;

  /// Serializes this StartSessionRequest to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of StartSessionRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $StartSessionRequestCopyWith<StartSessionRequest> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $StartSessionRequestCopyWith<$Res> {
  factory $StartSessionRequestCopyWith(
    StartSessionRequest value,
    $Res Function(StartSessionRequest) then,
  ) = _$StartSessionRequestCopyWithImpl<$Res, StartSessionRequest>;
  @useResult
  $Res call({
    String tenant,
    String profileId,
    int profileVersion,
    String channel,
    Map<String, dynamic> hints,
    ActAs? actAs,
  });

  $ActAsCopyWith<$Res>? get actAs;
}

/// @nodoc
class _$StartSessionRequestCopyWithImpl<$Res, $Val extends StartSessionRequest>
    implements $StartSessionRequestCopyWith<$Res> {
  _$StartSessionRequestCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of StartSessionRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenant = null,
    Object? profileId = null,
    Object? profileVersion = null,
    Object? channel = null,
    Object? hints = null,
    Object? actAs = freezed,
  }) {
    return _then(
      _value.copyWith(
            tenant: null == tenant
                ? _value.tenant
                : tenant // ignore: cast_nullable_to_non_nullable
                      as String,
            profileId: null == profileId
                ? _value.profileId
                : profileId // ignore: cast_nullable_to_non_nullable
                      as String,
            profileVersion: null == profileVersion
                ? _value.profileVersion
                : profileVersion // ignore: cast_nullable_to_non_nullable
                      as int,
            channel: null == channel
                ? _value.channel
                : channel // ignore: cast_nullable_to_non_nullable
                      as String,
            hints: null == hints
                ? _value.hints
                : hints // ignore: cast_nullable_to_non_nullable
                      as Map<String, dynamic>,
            actAs: freezed == actAs
                ? _value.actAs
                : actAs // ignore: cast_nullable_to_non_nullable
                      as ActAs?,
          )
          as $Val,
    );
  }

  /// Create a copy of StartSessionRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @pragma('vm:prefer-inline')
  $ActAsCopyWith<$Res>? get actAs {
    if (_value.actAs == null) {
      return null;
    }

    return $ActAsCopyWith<$Res>(_value.actAs!, (value) {
      return _then(_value.copyWith(actAs: value) as $Val);
    });
  }
}

/// @nodoc
abstract class _$$StartSessionRequestImplCopyWith<$Res>
    implements $StartSessionRequestCopyWith<$Res> {
  factory _$$StartSessionRequestImplCopyWith(
    _$StartSessionRequestImpl value,
    $Res Function(_$StartSessionRequestImpl) then,
  ) = __$$StartSessionRequestImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String tenant,
    String profileId,
    int profileVersion,
    String channel,
    Map<String, dynamic> hints,
    ActAs? actAs,
  });

  @override
  $ActAsCopyWith<$Res>? get actAs;
}

/// @nodoc
class __$$StartSessionRequestImplCopyWithImpl<$Res>
    extends _$StartSessionRequestCopyWithImpl<$Res, _$StartSessionRequestImpl>
    implements _$$StartSessionRequestImplCopyWith<$Res> {
  __$$StartSessionRequestImplCopyWithImpl(
    _$StartSessionRequestImpl _value,
    $Res Function(_$StartSessionRequestImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of StartSessionRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenant = null,
    Object? profileId = null,
    Object? profileVersion = null,
    Object? channel = null,
    Object? hints = null,
    Object? actAs = freezed,
  }) {
    return _then(
      _$StartSessionRequestImpl(
        tenant: null == tenant
            ? _value.tenant
            : tenant // ignore: cast_nullable_to_non_nullable
                  as String,
        profileId: null == profileId
            ? _value.profileId
            : profileId // ignore: cast_nullable_to_non_nullable
                  as String,
        profileVersion: null == profileVersion
            ? _value.profileVersion
            : profileVersion // ignore: cast_nullable_to_non_nullable
                  as int,
        channel: null == channel
            ? _value.channel
            : channel // ignore: cast_nullable_to_non_nullable
                  as String,
        hints: null == hints
            ? _value._hints
            : hints // ignore: cast_nullable_to_non_nullable
                  as Map<String, dynamic>,
        actAs: freezed == actAs
            ? _value.actAs
            : actAs // ignore: cast_nullable_to_non_nullable
                  as ActAs?,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$StartSessionRequestImpl implements _StartSessionRequest {
  const _$StartSessionRequestImpl({
    this.tenant = 'default',
    this.profileId = 'hello-world',
    this.profileVersion = 1,
    this.channel = 'web',
    final Map<String, dynamic> hints = const <String, dynamic>{},
    this.actAs,
  }) : _hints = hints;

  factory _$StartSessionRequestImpl.fromJson(Map<String, dynamic> json) =>
      _$$StartSessionRequestImplFromJson(json);

  @override
  @JsonKey()
  final String tenant;
  @override
  @JsonKey()
  final String profileId;
  @override
  @JsonKey()
  final int profileVersion;
  @override
  @JsonKey()
  final String channel;
  final Map<String, dynamic> _hints;
  @override
  @JsonKey()
  Map<String, dynamic> get hints {
    if (_hints is EqualUnmodifiableMapView) return _hints;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_hints);
  }

  @override
  final ActAs? actAs;

  @override
  String toString() {
    return 'StartSessionRequest(tenant: $tenant, profileId: $profileId, profileVersion: $profileVersion, channel: $channel, hints: $hints, actAs: $actAs)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$StartSessionRequestImpl &&
            (identical(other.tenant, tenant) || other.tenant == tenant) &&
            (identical(other.profileId, profileId) ||
                other.profileId == profileId) &&
            (identical(other.profileVersion, profileVersion) ||
                other.profileVersion == profileVersion) &&
            (identical(other.channel, channel) || other.channel == channel) &&
            const DeepCollectionEquality().equals(other._hints, _hints) &&
            (identical(other.actAs, actAs) || other.actAs == actAs));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    tenant,
    profileId,
    profileVersion,
    channel,
    const DeepCollectionEquality().hash(_hints),
    actAs,
  );

  /// Create a copy of StartSessionRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$StartSessionRequestImplCopyWith<_$StartSessionRequestImpl> get copyWith =>
      __$$StartSessionRequestImplCopyWithImpl<_$StartSessionRequestImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$StartSessionRequestImplToJson(this);
  }
}

abstract class _StartSessionRequest implements StartSessionRequest {
  const factory _StartSessionRequest({
    final String tenant,
    final String profileId,
    final int profileVersion,
    final String channel,
    final Map<String, dynamic> hints,
    final ActAs? actAs,
  }) = _$StartSessionRequestImpl;

  factory _StartSessionRequest.fromJson(Map<String, dynamic> json) =
      _$StartSessionRequestImpl.fromJson;

  @override
  String get tenant;
  @override
  String get profileId;
  @override
  int get profileVersion;
  @override
  String get channel;
  @override
  Map<String, dynamic> get hints;
  @override
  ActAs? get actAs;

  /// Create a copy of StartSessionRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$StartSessionRequestImplCopyWith<_$StartSessionRequestImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

StartSessionResponse _$StartSessionResponseFromJson(Map<String, dynamic> json) {
  return _StartSessionResponse.fromJson(json);
}

/// @nodoc
mixin _$StartSessionResponse {
  String get sessionId => throw _privateConstructorUsedError;
  String get profileId => throw _privateConstructorUsedError;
  int get profileVersion => throw _privateConstructorUsedError;
  String get greeting => throw _privateConstructorUsedError;
  List<String> get quickReplies => throw _privateConstructorUsedError;

  /// Serializes this StartSessionResponse to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of StartSessionResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $StartSessionResponseCopyWith<StartSessionResponse> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $StartSessionResponseCopyWith<$Res> {
  factory $StartSessionResponseCopyWith(
    StartSessionResponse value,
    $Res Function(StartSessionResponse) then,
  ) = _$StartSessionResponseCopyWithImpl<$Res, StartSessionResponse>;
  @useResult
  $Res call({
    String sessionId,
    String profileId,
    int profileVersion,
    String greeting,
    List<String> quickReplies,
  });
}

/// @nodoc
class _$StartSessionResponseCopyWithImpl<
  $Res,
  $Val extends StartSessionResponse
>
    implements $StartSessionResponseCopyWith<$Res> {
  _$StartSessionResponseCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of StartSessionResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? sessionId = null,
    Object? profileId = null,
    Object? profileVersion = null,
    Object? greeting = null,
    Object? quickReplies = null,
  }) {
    return _then(
      _value.copyWith(
            sessionId: null == sessionId
                ? _value.sessionId
                : sessionId // ignore: cast_nullable_to_non_nullable
                      as String,
            profileId: null == profileId
                ? _value.profileId
                : profileId // ignore: cast_nullable_to_non_nullable
                      as String,
            profileVersion: null == profileVersion
                ? _value.profileVersion
                : profileVersion // ignore: cast_nullable_to_non_nullable
                      as int,
            greeting: null == greeting
                ? _value.greeting
                : greeting // ignore: cast_nullable_to_non_nullable
                      as String,
            quickReplies: null == quickReplies
                ? _value.quickReplies
                : quickReplies // ignore: cast_nullable_to_non_nullable
                      as List<String>,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$StartSessionResponseImplCopyWith<$Res>
    implements $StartSessionResponseCopyWith<$Res> {
  factory _$$StartSessionResponseImplCopyWith(
    _$StartSessionResponseImpl value,
    $Res Function(_$StartSessionResponseImpl) then,
  ) = __$$StartSessionResponseImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String sessionId,
    String profileId,
    int profileVersion,
    String greeting,
    List<String> quickReplies,
  });
}

/// @nodoc
class __$$StartSessionResponseImplCopyWithImpl<$Res>
    extends _$StartSessionResponseCopyWithImpl<$Res, _$StartSessionResponseImpl>
    implements _$$StartSessionResponseImplCopyWith<$Res> {
  __$$StartSessionResponseImplCopyWithImpl(
    _$StartSessionResponseImpl _value,
    $Res Function(_$StartSessionResponseImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of StartSessionResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? sessionId = null,
    Object? profileId = null,
    Object? profileVersion = null,
    Object? greeting = null,
    Object? quickReplies = null,
  }) {
    return _then(
      _$StartSessionResponseImpl(
        sessionId: null == sessionId
            ? _value.sessionId
            : sessionId // ignore: cast_nullable_to_non_nullable
                  as String,
        profileId: null == profileId
            ? _value.profileId
            : profileId // ignore: cast_nullable_to_non_nullable
                  as String,
        profileVersion: null == profileVersion
            ? _value.profileVersion
            : profileVersion // ignore: cast_nullable_to_non_nullable
                  as int,
        greeting: null == greeting
            ? _value.greeting
            : greeting // ignore: cast_nullable_to_non_nullable
                  as String,
        quickReplies: null == quickReplies
            ? _value._quickReplies
            : quickReplies // ignore: cast_nullable_to_non_nullable
                  as List<String>,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$StartSessionResponseImpl implements _StartSessionResponse {
  const _$StartSessionResponseImpl({
    required this.sessionId,
    required this.profileId,
    required this.profileVersion,
    required this.greeting,
    required final List<String> quickReplies,
  }) : _quickReplies = quickReplies;

  factory _$StartSessionResponseImpl.fromJson(Map<String, dynamic> json) =>
      _$$StartSessionResponseImplFromJson(json);

  @override
  final String sessionId;
  @override
  final String profileId;
  @override
  final int profileVersion;
  @override
  final String greeting;
  final List<String> _quickReplies;
  @override
  List<String> get quickReplies {
    if (_quickReplies is EqualUnmodifiableListView) return _quickReplies;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_quickReplies);
  }

  @override
  String toString() {
    return 'StartSessionResponse(sessionId: $sessionId, profileId: $profileId, profileVersion: $profileVersion, greeting: $greeting, quickReplies: $quickReplies)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$StartSessionResponseImpl &&
            (identical(other.sessionId, sessionId) ||
                other.sessionId == sessionId) &&
            (identical(other.profileId, profileId) ||
                other.profileId == profileId) &&
            (identical(other.profileVersion, profileVersion) ||
                other.profileVersion == profileVersion) &&
            (identical(other.greeting, greeting) ||
                other.greeting == greeting) &&
            const DeepCollectionEquality().equals(
              other._quickReplies,
              _quickReplies,
            ));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    sessionId,
    profileId,
    profileVersion,
    greeting,
    const DeepCollectionEquality().hash(_quickReplies),
  );

  /// Create a copy of StartSessionResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$StartSessionResponseImplCopyWith<_$StartSessionResponseImpl>
  get copyWith =>
      __$$StartSessionResponseImplCopyWithImpl<_$StartSessionResponseImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$StartSessionResponseImplToJson(this);
  }
}

abstract class _StartSessionResponse implements StartSessionResponse {
  const factory _StartSessionResponse({
    required final String sessionId,
    required final String profileId,
    required final int profileVersion,
    required final String greeting,
    required final List<String> quickReplies,
  }) = _$StartSessionResponseImpl;

  factory _StartSessionResponse.fromJson(Map<String, dynamic> json) =
      _$StartSessionResponseImpl.fromJson;

  @override
  String get sessionId;
  @override
  String get profileId;
  @override
  int get profileVersion;
  @override
  String get greeting;
  @override
  List<String> get quickReplies;

  /// Create a copy of StartSessionResponse
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$StartSessionResponseImplCopyWith<_$StartSessionResponseImpl>
  get copyWith => throw _privateConstructorUsedError;
}

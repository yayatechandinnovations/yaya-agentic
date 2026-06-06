// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'recording_strategy.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

RecordingStrategyRequest _$RecordingStrategyRequestFromJson(
  Map<String, dynamic> json,
) {
  return _RecordingStrategyRequest.fromJson(json);
}

/// @nodoc
mixin _$RecordingStrategyRequest {
  String get tenant => throw _privateConstructorUsedError;
  String get scopeKind =>
      throw _privateConstructorUsedError; // TENANT | PROFILE
  String get scopeId => throw _privateConstructorUsedError;
  Map<String, dynamic> get strategy => throw _privateConstructorUsedError;

  /// Serializes this RecordingStrategyRequest to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of RecordingStrategyRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $RecordingStrategyRequestCopyWith<RecordingStrategyRequest> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $RecordingStrategyRequestCopyWith<$Res> {
  factory $RecordingStrategyRequestCopyWith(
    RecordingStrategyRequest value,
    $Res Function(RecordingStrategyRequest) then,
  ) = _$RecordingStrategyRequestCopyWithImpl<$Res, RecordingStrategyRequest>;
  @useResult
  $Res call({
    String tenant,
    String scopeKind,
    String scopeId,
    Map<String, dynamic> strategy,
  });
}

/// @nodoc
class _$RecordingStrategyRequestCopyWithImpl<
  $Res,
  $Val extends RecordingStrategyRequest
>
    implements $RecordingStrategyRequestCopyWith<$Res> {
  _$RecordingStrategyRequestCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of RecordingStrategyRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenant = null,
    Object? scopeKind = null,
    Object? scopeId = null,
    Object? strategy = null,
  }) {
    return _then(
      _value.copyWith(
            tenant: null == tenant
                ? _value.tenant
                : tenant // ignore: cast_nullable_to_non_nullable
                      as String,
            scopeKind: null == scopeKind
                ? _value.scopeKind
                : scopeKind // ignore: cast_nullable_to_non_nullable
                      as String,
            scopeId: null == scopeId
                ? _value.scopeId
                : scopeId // ignore: cast_nullable_to_non_nullable
                      as String,
            strategy: null == strategy
                ? _value.strategy
                : strategy // ignore: cast_nullable_to_non_nullable
                      as Map<String, dynamic>,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$RecordingStrategyRequestImplCopyWith<$Res>
    implements $RecordingStrategyRequestCopyWith<$Res> {
  factory _$$RecordingStrategyRequestImplCopyWith(
    _$RecordingStrategyRequestImpl value,
    $Res Function(_$RecordingStrategyRequestImpl) then,
  ) = __$$RecordingStrategyRequestImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String tenant,
    String scopeKind,
    String scopeId,
    Map<String, dynamic> strategy,
  });
}

/// @nodoc
class __$$RecordingStrategyRequestImplCopyWithImpl<$Res>
    extends
        _$RecordingStrategyRequestCopyWithImpl<
          $Res,
          _$RecordingStrategyRequestImpl
        >
    implements _$$RecordingStrategyRequestImplCopyWith<$Res> {
  __$$RecordingStrategyRequestImplCopyWithImpl(
    _$RecordingStrategyRequestImpl _value,
    $Res Function(_$RecordingStrategyRequestImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of RecordingStrategyRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenant = null,
    Object? scopeKind = null,
    Object? scopeId = null,
    Object? strategy = null,
  }) {
    return _then(
      _$RecordingStrategyRequestImpl(
        tenant: null == tenant
            ? _value.tenant
            : tenant // ignore: cast_nullable_to_non_nullable
                  as String,
        scopeKind: null == scopeKind
            ? _value.scopeKind
            : scopeKind // ignore: cast_nullable_to_non_nullable
                  as String,
        scopeId: null == scopeId
            ? _value.scopeId
            : scopeId // ignore: cast_nullable_to_non_nullable
                  as String,
        strategy: null == strategy
            ? _value._strategy
            : strategy // ignore: cast_nullable_to_non_nullable
                  as Map<String, dynamic>,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$RecordingStrategyRequestImpl implements _RecordingStrategyRequest {
  const _$RecordingStrategyRequestImpl({
    required this.tenant,
    required this.scopeKind,
    required this.scopeId,
    required final Map<String, dynamic> strategy,
  }) : _strategy = strategy;

  factory _$RecordingStrategyRequestImpl.fromJson(Map<String, dynamic> json) =>
      _$$RecordingStrategyRequestImplFromJson(json);

  @override
  final String tenant;
  @override
  final String scopeKind;
  // TENANT | PROFILE
  @override
  final String scopeId;
  final Map<String, dynamic> _strategy;
  @override
  Map<String, dynamic> get strategy {
    if (_strategy is EqualUnmodifiableMapView) return _strategy;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_strategy);
  }

  @override
  String toString() {
    return 'RecordingStrategyRequest(tenant: $tenant, scopeKind: $scopeKind, scopeId: $scopeId, strategy: $strategy)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$RecordingStrategyRequestImpl &&
            (identical(other.tenant, tenant) || other.tenant == tenant) &&
            (identical(other.scopeKind, scopeKind) ||
                other.scopeKind == scopeKind) &&
            (identical(other.scopeId, scopeId) || other.scopeId == scopeId) &&
            const DeepCollectionEquality().equals(other._strategy, _strategy));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    tenant,
    scopeKind,
    scopeId,
    const DeepCollectionEquality().hash(_strategy),
  );

  /// Create a copy of RecordingStrategyRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$RecordingStrategyRequestImplCopyWith<_$RecordingStrategyRequestImpl>
  get copyWith =>
      __$$RecordingStrategyRequestImplCopyWithImpl<
        _$RecordingStrategyRequestImpl
      >(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$RecordingStrategyRequestImplToJson(this);
  }
}

abstract class _RecordingStrategyRequest implements RecordingStrategyRequest {
  const factory _RecordingStrategyRequest({
    required final String tenant,
    required final String scopeKind,
    required final String scopeId,
    required final Map<String, dynamic> strategy,
  }) = _$RecordingStrategyRequestImpl;

  factory _RecordingStrategyRequest.fromJson(Map<String, dynamic> json) =
      _$RecordingStrategyRequestImpl.fromJson;

  @override
  String get tenant;
  @override
  String get scopeKind; // TENANT | PROFILE
  @override
  String get scopeId;
  @override
  Map<String, dynamic> get strategy;

  /// Create a copy of RecordingStrategyRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$RecordingStrategyRequestImplCopyWith<_$RecordingStrategyRequestImpl>
  get copyWith => throw _privateConstructorUsedError;
}

RecordingStrategyResponse _$RecordingStrategyResponseFromJson(
  Map<String, dynamic> json,
) {
  return _RecordingStrategyResponse.fromJson(json);
}

/// @nodoc
mixin _$RecordingStrategyResponse {
  String get tenant => throw _privateConstructorUsedError;
  String get scopeKind => throw _privateConstructorUsedError;
  String get scopeId => throw _privateConstructorUsedError;
  Map<String, dynamic> get strategy => throw _privateConstructorUsedError;
  int get version => throw _privateConstructorUsedError;
  String? get createdAt => throw _privateConstructorUsedError;

  /// Serializes this RecordingStrategyResponse to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of RecordingStrategyResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $RecordingStrategyResponseCopyWith<RecordingStrategyResponse> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $RecordingStrategyResponseCopyWith<$Res> {
  factory $RecordingStrategyResponseCopyWith(
    RecordingStrategyResponse value,
    $Res Function(RecordingStrategyResponse) then,
  ) = _$RecordingStrategyResponseCopyWithImpl<$Res, RecordingStrategyResponse>;
  @useResult
  $Res call({
    String tenant,
    String scopeKind,
    String scopeId,
    Map<String, dynamic> strategy,
    int version,
    String? createdAt,
  });
}

/// @nodoc
class _$RecordingStrategyResponseCopyWithImpl<
  $Res,
  $Val extends RecordingStrategyResponse
>
    implements $RecordingStrategyResponseCopyWith<$Res> {
  _$RecordingStrategyResponseCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of RecordingStrategyResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenant = null,
    Object? scopeKind = null,
    Object? scopeId = null,
    Object? strategy = null,
    Object? version = null,
    Object? createdAt = freezed,
  }) {
    return _then(
      _value.copyWith(
            tenant: null == tenant
                ? _value.tenant
                : tenant // ignore: cast_nullable_to_non_nullable
                      as String,
            scopeKind: null == scopeKind
                ? _value.scopeKind
                : scopeKind // ignore: cast_nullable_to_non_nullable
                      as String,
            scopeId: null == scopeId
                ? _value.scopeId
                : scopeId // ignore: cast_nullable_to_non_nullable
                      as String,
            strategy: null == strategy
                ? _value.strategy
                : strategy // ignore: cast_nullable_to_non_nullable
                      as Map<String, dynamic>,
            version: null == version
                ? _value.version
                : version // ignore: cast_nullable_to_non_nullable
                      as int,
            createdAt: freezed == createdAt
                ? _value.createdAt
                : createdAt // ignore: cast_nullable_to_non_nullable
                      as String?,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$RecordingStrategyResponseImplCopyWith<$Res>
    implements $RecordingStrategyResponseCopyWith<$Res> {
  factory _$$RecordingStrategyResponseImplCopyWith(
    _$RecordingStrategyResponseImpl value,
    $Res Function(_$RecordingStrategyResponseImpl) then,
  ) = __$$RecordingStrategyResponseImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String tenant,
    String scopeKind,
    String scopeId,
    Map<String, dynamic> strategy,
    int version,
    String? createdAt,
  });
}

/// @nodoc
class __$$RecordingStrategyResponseImplCopyWithImpl<$Res>
    extends
        _$RecordingStrategyResponseCopyWithImpl<
          $Res,
          _$RecordingStrategyResponseImpl
        >
    implements _$$RecordingStrategyResponseImplCopyWith<$Res> {
  __$$RecordingStrategyResponseImplCopyWithImpl(
    _$RecordingStrategyResponseImpl _value,
    $Res Function(_$RecordingStrategyResponseImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of RecordingStrategyResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenant = null,
    Object? scopeKind = null,
    Object? scopeId = null,
    Object? strategy = null,
    Object? version = null,
    Object? createdAt = freezed,
  }) {
    return _then(
      _$RecordingStrategyResponseImpl(
        tenant: null == tenant
            ? _value.tenant
            : tenant // ignore: cast_nullable_to_non_nullable
                  as String,
        scopeKind: null == scopeKind
            ? _value.scopeKind
            : scopeKind // ignore: cast_nullable_to_non_nullable
                  as String,
        scopeId: null == scopeId
            ? _value.scopeId
            : scopeId // ignore: cast_nullable_to_non_nullable
                  as String,
        strategy: null == strategy
            ? _value._strategy
            : strategy // ignore: cast_nullable_to_non_nullable
                  as Map<String, dynamic>,
        version: null == version
            ? _value.version
            : version // ignore: cast_nullable_to_non_nullable
                  as int,
        createdAt: freezed == createdAt
            ? _value.createdAt
            : createdAt // ignore: cast_nullable_to_non_nullable
                  as String?,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$RecordingStrategyResponseImpl implements _RecordingStrategyResponse {
  const _$RecordingStrategyResponseImpl({
    required this.tenant,
    required this.scopeKind,
    required this.scopeId,
    required final Map<String, dynamic> strategy,
    required this.version,
    this.createdAt,
  }) : _strategy = strategy;

  factory _$RecordingStrategyResponseImpl.fromJson(Map<String, dynamic> json) =>
      _$$RecordingStrategyResponseImplFromJson(json);

  @override
  final String tenant;
  @override
  final String scopeKind;
  @override
  final String scopeId;
  final Map<String, dynamic> _strategy;
  @override
  Map<String, dynamic> get strategy {
    if (_strategy is EqualUnmodifiableMapView) return _strategy;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_strategy);
  }

  @override
  final int version;
  @override
  final String? createdAt;

  @override
  String toString() {
    return 'RecordingStrategyResponse(tenant: $tenant, scopeKind: $scopeKind, scopeId: $scopeId, strategy: $strategy, version: $version, createdAt: $createdAt)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$RecordingStrategyResponseImpl &&
            (identical(other.tenant, tenant) || other.tenant == tenant) &&
            (identical(other.scopeKind, scopeKind) ||
                other.scopeKind == scopeKind) &&
            (identical(other.scopeId, scopeId) || other.scopeId == scopeId) &&
            const DeepCollectionEquality().equals(other._strategy, _strategy) &&
            (identical(other.version, version) || other.version == version) &&
            (identical(other.createdAt, createdAt) ||
                other.createdAt == createdAt));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    tenant,
    scopeKind,
    scopeId,
    const DeepCollectionEquality().hash(_strategy),
    version,
    createdAt,
  );

  /// Create a copy of RecordingStrategyResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$RecordingStrategyResponseImplCopyWith<_$RecordingStrategyResponseImpl>
  get copyWith =>
      __$$RecordingStrategyResponseImplCopyWithImpl<
        _$RecordingStrategyResponseImpl
      >(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$RecordingStrategyResponseImplToJson(this);
  }
}

abstract class _RecordingStrategyResponse implements RecordingStrategyResponse {
  const factory _RecordingStrategyResponse({
    required final String tenant,
    required final String scopeKind,
    required final String scopeId,
    required final Map<String, dynamic> strategy,
    required final int version,
    final String? createdAt,
  }) = _$RecordingStrategyResponseImpl;

  factory _RecordingStrategyResponse.fromJson(Map<String, dynamic> json) =
      _$RecordingStrategyResponseImpl.fromJson;

  @override
  String get tenant;
  @override
  String get scopeKind;
  @override
  String get scopeId;
  @override
  Map<String, dynamic> get strategy;
  @override
  int get version;
  @override
  String? get createdAt;

  /// Create a copy of RecordingStrategyResponse
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$RecordingStrategyResponseImplCopyWith<_$RecordingStrategyResponseImpl>
  get copyWith => throw _privateConstructorUsedError;
}

// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'api_key.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

ApiKey _$ApiKeyFromJson(Map<String, dynamic> json) {
  return _ApiKey.fromJson(json);
}

/// @nodoc
mixin _$ApiKey {
  String get id => throw _privateConstructorUsedError;
  String get name => throw _privateConstructorUsedError;
  String get keyPrefix => throw _privateConstructorUsedError;
  String get role => throw _privateConstructorUsedError;
  String? get createdBy => throw _privateConstructorUsedError;
  String? get createdAt => throw _privateConstructorUsedError;
  String? get expiresAt => throw _privateConstructorUsedError;
  String? get lastUsedAt => throw _privateConstructorUsedError;
  String? get lastUsedIp => throw _privateConstructorUsedError;
  String get status => throw _privateConstructorUsedError;

  /// Serializes this ApiKey to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of ApiKey
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $ApiKeyCopyWith<ApiKey> get copyWith => throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $ApiKeyCopyWith<$Res> {
  factory $ApiKeyCopyWith(ApiKey value, $Res Function(ApiKey) then) =
      _$ApiKeyCopyWithImpl<$Res, ApiKey>;
  @useResult
  $Res call({
    String id,
    String name,
    String keyPrefix,
    String role,
    String? createdBy,
    String? createdAt,
    String? expiresAt,
    String? lastUsedAt,
    String? lastUsedIp,
    String status,
  });
}

/// @nodoc
class _$ApiKeyCopyWithImpl<$Res, $Val extends ApiKey>
    implements $ApiKeyCopyWith<$Res> {
  _$ApiKeyCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of ApiKey
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? name = null,
    Object? keyPrefix = null,
    Object? role = null,
    Object? createdBy = freezed,
    Object? createdAt = freezed,
    Object? expiresAt = freezed,
    Object? lastUsedAt = freezed,
    Object? lastUsedIp = freezed,
    Object? status = null,
  }) {
    return _then(
      _value.copyWith(
            id: null == id
                ? _value.id
                : id // ignore: cast_nullable_to_non_nullable
                      as String,
            name: null == name
                ? _value.name
                : name // ignore: cast_nullable_to_non_nullable
                      as String,
            keyPrefix: null == keyPrefix
                ? _value.keyPrefix
                : keyPrefix // ignore: cast_nullable_to_non_nullable
                      as String,
            role: null == role
                ? _value.role
                : role // ignore: cast_nullable_to_non_nullable
                      as String,
            createdBy: freezed == createdBy
                ? _value.createdBy
                : createdBy // ignore: cast_nullable_to_non_nullable
                      as String?,
            createdAt: freezed == createdAt
                ? _value.createdAt
                : createdAt // ignore: cast_nullable_to_non_nullable
                      as String?,
            expiresAt: freezed == expiresAt
                ? _value.expiresAt
                : expiresAt // ignore: cast_nullable_to_non_nullable
                      as String?,
            lastUsedAt: freezed == lastUsedAt
                ? _value.lastUsedAt
                : lastUsedAt // ignore: cast_nullable_to_non_nullable
                      as String?,
            lastUsedIp: freezed == lastUsedIp
                ? _value.lastUsedIp
                : lastUsedIp // ignore: cast_nullable_to_non_nullable
                      as String?,
            status: null == status
                ? _value.status
                : status // ignore: cast_nullable_to_non_nullable
                      as String,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$ApiKeyImplCopyWith<$Res> implements $ApiKeyCopyWith<$Res> {
  factory _$$ApiKeyImplCopyWith(
    _$ApiKeyImpl value,
    $Res Function(_$ApiKeyImpl) then,
  ) = __$$ApiKeyImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String id,
    String name,
    String keyPrefix,
    String role,
    String? createdBy,
    String? createdAt,
    String? expiresAt,
    String? lastUsedAt,
    String? lastUsedIp,
    String status,
  });
}

/// @nodoc
class __$$ApiKeyImplCopyWithImpl<$Res>
    extends _$ApiKeyCopyWithImpl<$Res, _$ApiKeyImpl>
    implements _$$ApiKeyImplCopyWith<$Res> {
  __$$ApiKeyImplCopyWithImpl(
    _$ApiKeyImpl _value,
    $Res Function(_$ApiKeyImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of ApiKey
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? name = null,
    Object? keyPrefix = null,
    Object? role = null,
    Object? createdBy = freezed,
    Object? createdAt = freezed,
    Object? expiresAt = freezed,
    Object? lastUsedAt = freezed,
    Object? lastUsedIp = freezed,
    Object? status = null,
  }) {
    return _then(
      _$ApiKeyImpl(
        id: null == id
            ? _value.id
            : id // ignore: cast_nullable_to_non_nullable
                  as String,
        name: null == name
            ? _value.name
            : name // ignore: cast_nullable_to_non_nullable
                  as String,
        keyPrefix: null == keyPrefix
            ? _value.keyPrefix
            : keyPrefix // ignore: cast_nullable_to_non_nullable
                  as String,
        role: null == role
            ? _value.role
            : role // ignore: cast_nullable_to_non_nullable
                  as String,
        createdBy: freezed == createdBy
            ? _value.createdBy
            : createdBy // ignore: cast_nullable_to_non_nullable
                  as String?,
        createdAt: freezed == createdAt
            ? _value.createdAt
            : createdAt // ignore: cast_nullable_to_non_nullable
                  as String?,
        expiresAt: freezed == expiresAt
            ? _value.expiresAt
            : expiresAt // ignore: cast_nullable_to_non_nullable
                  as String?,
        lastUsedAt: freezed == lastUsedAt
            ? _value.lastUsedAt
            : lastUsedAt // ignore: cast_nullable_to_non_nullable
                  as String?,
        lastUsedIp: freezed == lastUsedIp
            ? _value.lastUsedIp
            : lastUsedIp // ignore: cast_nullable_to_non_nullable
                  as String?,
        status: null == status
            ? _value.status
            : status // ignore: cast_nullable_to_non_nullable
                  as String,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$ApiKeyImpl implements _ApiKey {
  const _$ApiKeyImpl({
    required this.id,
    required this.name,
    required this.keyPrefix,
    required this.role,
    this.createdBy,
    this.createdAt,
    this.expiresAt,
    this.lastUsedAt,
    this.lastUsedIp,
    required this.status,
  });

  factory _$ApiKeyImpl.fromJson(Map<String, dynamic> json) =>
      _$$ApiKeyImplFromJson(json);

  @override
  final String id;
  @override
  final String name;
  @override
  final String keyPrefix;
  @override
  final String role;
  @override
  final String? createdBy;
  @override
  final String? createdAt;
  @override
  final String? expiresAt;
  @override
  final String? lastUsedAt;
  @override
  final String? lastUsedIp;
  @override
  final String status;

  @override
  String toString() {
    return 'ApiKey(id: $id, name: $name, keyPrefix: $keyPrefix, role: $role, createdBy: $createdBy, createdAt: $createdAt, expiresAt: $expiresAt, lastUsedAt: $lastUsedAt, lastUsedIp: $lastUsedIp, status: $status)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$ApiKeyImpl &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.name, name) || other.name == name) &&
            (identical(other.keyPrefix, keyPrefix) ||
                other.keyPrefix == keyPrefix) &&
            (identical(other.role, role) || other.role == role) &&
            (identical(other.createdBy, createdBy) ||
                other.createdBy == createdBy) &&
            (identical(other.createdAt, createdAt) ||
                other.createdAt == createdAt) &&
            (identical(other.expiresAt, expiresAt) ||
                other.expiresAt == expiresAt) &&
            (identical(other.lastUsedAt, lastUsedAt) ||
                other.lastUsedAt == lastUsedAt) &&
            (identical(other.lastUsedIp, lastUsedIp) ||
                other.lastUsedIp == lastUsedIp) &&
            (identical(other.status, status) || other.status == status));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    id,
    name,
    keyPrefix,
    role,
    createdBy,
    createdAt,
    expiresAt,
    lastUsedAt,
    lastUsedIp,
    status,
  );

  /// Create a copy of ApiKey
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$ApiKeyImplCopyWith<_$ApiKeyImpl> get copyWith =>
      __$$ApiKeyImplCopyWithImpl<_$ApiKeyImpl>(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$ApiKeyImplToJson(this);
  }
}

abstract class _ApiKey implements ApiKey {
  const factory _ApiKey({
    required final String id,
    required final String name,
    required final String keyPrefix,
    required final String role,
    final String? createdBy,
    final String? createdAt,
    final String? expiresAt,
    final String? lastUsedAt,
    final String? lastUsedIp,
    required final String status,
  }) = _$ApiKeyImpl;

  factory _ApiKey.fromJson(Map<String, dynamic> json) = _$ApiKeyImpl.fromJson;

  @override
  String get id;
  @override
  String get name;
  @override
  String get keyPrefix;
  @override
  String get role;
  @override
  String? get createdBy;
  @override
  String? get createdAt;
  @override
  String? get expiresAt;
  @override
  String? get lastUsedAt;
  @override
  String? get lastUsedIp;
  @override
  String get status;

  /// Create a copy of ApiKey
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$ApiKeyImplCopyWith<_$ApiKeyImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

ApiKeyCreateRequest _$ApiKeyCreateRequestFromJson(Map<String, dynamic> json) {
  return _ApiKeyCreateRequest.fromJson(json);
}

/// @nodoc
mixin _$ApiKeyCreateRequest {
  String get name => throw _privateConstructorUsedError;
  String get role => throw _privateConstructorUsedError;
  String? get expiresAt => throw _privateConstructorUsedError;

  /// Serializes this ApiKeyCreateRequest to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of ApiKeyCreateRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $ApiKeyCreateRequestCopyWith<ApiKeyCreateRequest> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $ApiKeyCreateRequestCopyWith<$Res> {
  factory $ApiKeyCreateRequestCopyWith(
    ApiKeyCreateRequest value,
    $Res Function(ApiKeyCreateRequest) then,
  ) = _$ApiKeyCreateRequestCopyWithImpl<$Res, ApiKeyCreateRequest>;
  @useResult
  $Res call({String name, String role, String? expiresAt});
}

/// @nodoc
class _$ApiKeyCreateRequestCopyWithImpl<$Res, $Val extends ApiKeyCreateRequest>
    implements $ApiKeyCreateRequestCopyWith<$Res> {
  _$ApiKeyCreateRequestCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of ApiKeyCreateRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? name = null,
    Object? role = null,
    Object? expiresAt = freezed,
  }) {
    return _then(
      _value.copyWith(
            name: null == name
                ? _value.name
                : name // ignore: cast_nullable_to_non_nullable
                      as String,
            role: null == role
                ? _value.role
                : role // ignore: cast_nullable_to_non_nullable
                      as String,
            expiresAt: freezed == expiresAt
                ? _value.expiresAt
                : expiresAt // ignore: cast_nullable_to_non_nullable
                      as String?,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$ApiKeyCreateRequestImplCopyWith<$Res>
    implements $ApiKeyCreateRequestCopyWith<$Res> {
  factory _$$ApiKeyCreateRequestImplCopyWith(
    _$ApiKeyCreateRequestImpl value,
    $Res Function(_$ApiKeyCreateRequestImpl) then,
  ) = __$$ApiKeyCreateRequestImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({String name, String role, String? expiresAt});
}

/// @nodoc
class __$$ApiKeyCreateRequestImplCopyWithImpl<$Res>
    extends _$ApiKeyCreateRequestCopyWithImpl<$Res, _$ApiKeyCreateRequestImpl>
    implements _$$ApiKeyCreateRequestImplCopyWith<$Res> {
  __$$ApiKeyCreateRequestImplCopyWithImpl(
    _$ApiKeyCreateRequestImpl _value,
    $Res Function(_$ApiKeyCreateRequestImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of ApiKeyCreateRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? name = null,
    Object? role = null,
    Object? expiresAt = freezed,
  }) {
    return _then(
      _$ApiKeyCreateRequestImpl(
        name: null == name
            ? _value.name
            : name // ignore: cast_nullable_to_non_nullable
                  as String,
        role: null == role
            ? _value.role
            : role // ignore: cast_nullable_to_non_nullable
                  as String,
        expiresAt: freezed == expiresAt
            ? _value.expiresAt
            : expiresAt // ignore: cast_nullable_to_non_nullable
                  as String?,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$ApiKeyCreateRequestImpl implements _ApiKeyCreateRequest {
  const _$ApiKeyCreateRequestImpl({
    required this.name,
    required this.role,
    this.expiresAt,
  });

  factory _$ApiKeyCreateRequestImpl.fromJson(Map<String, dynamic> json) =>
      _$$ApiKeyCreateRequestImplFromJson(json);

  @override
  final String name;
  @override
  final String role;
  @override
  final String? expiresAt;

  @override
  String toString() {
    return 'ApiKeyCreateRequest(name: $name, role: $role, expiresAt: $expiresAt)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$ApiKeyCreateRequestImpl &&
            (identical(other.name, name) || other.name == name) &&
            (identical(other.role, role) || other.role == role) &&
            (identical(other.expiresAt, expiresAt) ||
                other.expiresAt == expiresAt));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(runtimeType, name, role, expiresAt);

  /// Create a copy of ApiKeyCreateRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$ApiKeyCreateRequestImplCopyWith<_$ApiKeyCreateRequestImpl> get copyWith =>
      __$$ApiKeyCreateRequestImplCopyWithImpl<_$ApiKeyCreateRequestImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$ApiKeyCreateRequestImplToJson(this);
  }
}

abstract class _ApiKeyCreateRequest implements ApiKeyCreateRequest {
  const factory _ApiKeyCreateRequest({
    required final String name,
    required final String role,
    final String? expiresAt,
  }) = _$ApiKeyCreateRequestImpl;

  factory _ApiKeyCreateRequest.fromJson(Map<String, dynamic> json) =
      _$ApiKeyCreateRequestImpl.fromJson;

  @override
  String get name;
  @override
  String get role;
  @override
  String? get expiresAt;

  /// Create a copy of ApiKeyCreateRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$ApiKeyCreateRequestImplCopyWith<_$ApiKeyCreateRequestImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

ApiKeyCreated _$ApiKeyCreatedFromJson(Map<String, dynamic> json) {
  return _ApiKeyCreated.fromJson(json);
}

/// @nodoc
mixin _$ApiKeyCreated {
  String get id => throw _privateConstructorUsedError;
  String get name => throw _privateConstructorUsedError;
  String get keyPrefix => throw _privateConstructorUsedError;
  String get role => throw _privateConstructorUsedError;
  String? get createdAt => throw _privateConstructorUsedError;
  String? get expiresAt => throw _privateConstructorUsedError;
  String get secret => throw _privateConstructorUsedError;

  /// Serializes this ApiKeyCreated to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of ApiKeyCreated
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $ApiKeyCreatedCopyWith<ApiKeyCreated> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $ApiKeyCreatedCopyWith<$Res> {
  factory $ApiKeyCreatedCopyWith(
    ApiKeyCreated value,
    $Res Function(ApiKeyCreated) then,
  ) = _$ApiKeyCreatedCopyWithImpl<$Res, ApiKeyCreated>;
  @useResult
  $Res call({
    String id,
    String name,
    String keyPrefix,
    String role,
    String? createdAt,
    String? expiresAt,
    String secret,
  });
}

/// @nodoc
class _$ApiKeyCreatedCopyWithImpl<$Res, $Val extends ApiKeyCreated>
    implements $ApiKeyCreatedCopyWith<$Res> {
  _$ApiKeyCreatedCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of ApiKeyCreated
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? name = null,
    Object? keyPrefix = null,
    Object? role = null,
    Object? createdAt = freezed,
    Object? expiresAt = freezed,
    Object? secret = null,
  }) {
    return _then(
      _value.copyWith(
            id: null == id
                ? _value.id
                : id // ignore: cast_nullable_to_non_nullable
                      as String,
            name: null == name
                ? _value.name
                : name // ignore: cast_nullable_to_non_nullable
                      as String,
            keyPrefix: null == keyPrefix
                ? _value.keyPrefix
                : keyPrefix // ignore: cast_nullable_to_non_nullable
                      as String,
            role: null == role
                ? _value.role
                : role // ignore: cast_nullable_to_non_nullable
                      as String,
            createdAt: freezed == createdAt
                ? _value.createdAt
                : createdAt // ignore: cast_nullable_to_non_nullable
                      as String?,
            expiresAt: freezed == expiresAt
                ? _value.expiresAt
                : expiresAt // ignore: cast_nullable_to_non_nullable
                      as String?,
            secret: null == secret
                ? _value.secret
                : secret // ignore: cast_nullable_to_non_nullable
                      as String,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$ApiKeyCreatedImplCopyWith<$Res>
    implements $ApiKeyCreatedCopyWith<$Res> {
  factory _$$ApiKeyCreatedImplCopyWith(
    _$ApiKeyCreatedImpl value,
    $Res Function(_$ApiKeyCreatedImpl) then,
  ) = __$$ApiKeyCreatedImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String id,
    String name,
    String keyPrefix,
    String role,
    String? createdAt,
    String? expiresAt,
    String secret,
  });
}

/// @nodoc
class __$$ApiKeyCreatedImplCopyWithImpl<$Res>
    extends _$ApiKeyCreatedCopyWithImpl<$Res, _$ApiKeyCreatedImpl>
    implements _$$ApiKeyCreatedImplCopyWith<$Res> {
  __$$ApiKeyCreatedImplCopyWithImpl(
    _$ApiKeyCreatedImpl _value,
    $Res Function(_$ApiKeyCreatedImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of ApiKeyCreated
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? name = null,
    Object? keyPrefix = null,
    Object? role = null,
    Object? createdAt = freezed,
    Object? expiresAt = freezed,
    Object? secret = null,
  }) {
    return _then(
      _$ApiKeyCreatedImpl(
        id: null == id
            ? _value.id
            : id // ignore: cast_nullable_to_non_nullable
                  as String,
        name: null == name
            ? _value.name
            : name // ignore: cast_nullable_to_non_nullable
                  as String,
        keyPrefix: null == keyPrefix
            ? _value.keyPrefix
            : keyPrefix // ignore: cast_nullable_to_non_nullable
                  as String,
        role: null == role
            ? _value.role
            : role // ignore: cast_nullable_to_non_nullable
                  as String,
        createdAt: freezed == createdAt
            ? _value.createdAt
            : createdAt // ignore: cast_nullable_to_non_nullable
                  as String?,
        expiresAt: freezed == expiresAt
            ? _value.expiresAt
            : expiresAt // ignore: cast_nullable_to_non_nullable
                  as String?,
        secret: null == secret
            ? _value.secret
            : secret // ignore: cast_nullable_to_non_nullable
                  as String,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$ApiKeyCreatedImpl implements _ApiKeyCreated {
  const _$ApiKeyCreatedImpl({
    required this.id,
    required this.name,
    required this.keyPrefix,
    required this.role,
    this.createdAt,
    this.expiresAt,
    required this.secret,
  });

  factory _$ApiKeyCreatedImpl.fromJson(Map<String, dynamic> json) =>
      _$$ApiKeyCreatedImplFromJson(json);

  @override
  final String id;
  @override
  final String name;
  @override
  final String keyPrefix;
  @override
  final String role;
  @override
  final String? createdAt;
  @override
  final String? expiresAt;
  @override
  final String secret;

  @override
  String toString() {
    return 'ApiKeyCreated(id: $id, name: $name, keyPrefix: $keyPrefix, role: $role, createdAt: $createdAt, expiresAt: $expiresAt, secret: $secret)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$ApiKeyCreatedImpl &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.name, name) || other.name == name) &&
            (identical(other.keyPrefix, keyPrefix) ||
                other.keyPrefix == keyPrefix) &&
            (identical(other.role, role) || other.role == role) &&
            (identical(other.createdAt, createdAt) ||
                other.createdAt == createdAt) &&
            (identical(other.expiresAt, expiresAt) ||
                other.expiresAt == expiresAt) &&
            (identical(other.secret, secret) || other.secret == secret));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    id,
    name,
    keyPrefix,
    role,
    createdAt,
    expiresAt,
    secret,
  );

  /// Create a copy of ApiKeyCreated
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$ApiKeyCreatedImplCopyWith<_$ApiKeyCreatedImpl> get copyWith =>
      __$$ApiKeyCreatedImplCopyWithImpl<_$ApiKeyCreatedImpl>(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$ApiKeyCreatedImplToJson(this);
  }
}

abstract class _ApiKeyCreated implements ApiKeyCreated {
  const factory _ApiKeyCreated({
    required final String id,
    required final String name,
    required final String keyPrefix,
    required final String role,
    final String? createdAt,
    final String? expiresAt,
    required final String secret,
  }) = _$ApiKeyCreatedImpl;

  factory _ApiKeyCreated.fromJson(Map<String, dynamic> json) =
      _$ApiKeyCreatedImpl.fromJson;

  @override
  String get id;
  @override
  String get name;
  @override
  String get keyPrefix;
  @override
  String get role;
  @override
  String? get createdAt;
  @override
  String? get expiresAt;
  @override
  String get secret;

  /// Create a copy of ApiKeyCreated
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$ApiKeyCreatedImplCopyWith<_$ApiKeyCreatedImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

ApiKeyAuditEvent _$ApiKeyAuditEventFromJson(Map<String, dynamic> json) {
  return _ApiKeyAuditEvent.fromJson(json);
}

/// @nodoc
mixin _$ApiKeyAuditEvent {
  String get kind => throw _privateConstructorUsedError;
  String? get detail => throw _privateConstructorUsedError;
  String? get clientIp => throw _privateConstructorUsedError;
  String? get occurredAt => throw _privateConstructorUsedError;

  /// Serializes this ApiKeyAuditEvent to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of ApiKeyAuditEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $ApiKeyAuditEventCopyWith<ApiKeyAuditEvent> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $ApiKeyAuditEventCopyWith<$Res> {
  factory $ApiKeyAuditEventCopyWith(
    ApiKeyAuditEvent value,
    $Res Function(ApiKeyAuditEvent) then,
  ) = _$ApiKeyAuditEventCopyWithImpl<$Res, ApiKeyAuditEvent>;
  @useResult
  $Res call({
    String kind,
    String? detail,
    String? clientIp,
    String? occurredAt,
  });
}

/// @nodoc
class _$ApiKeyAuditEventCopyWithImpl<$Res, $Val extends ApiKeyAuditEvent>
    implements $ApiKeyAuditEventCopyWith<$Res> {
  _$ApiKeyAuditEventCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of ApiKeyAuditEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? kind = null,
    Object? detail = freezed,
    Object? clientIp = freezed,
    Object? occurredAt = freezed,
  }) {
    return _then(
      _value.copyWith(
            kind: null == kind
                ? _value.kind
                : kind // ignore: cast_nullable_to_non_nullable
                      as String,
            detail: freezed == detail
                ? _value.detail
                : detail // ignore: cast_nullable_to_non_nullable
                      as String?,
            clientIp: freezed == clientIp
                ? _value.clientIp
                : clientIp // ignore: cast_nullable_to_non_nullable
                      as String?,
            occurredAt: freezed == occurredAt
                ? _value.occurredAt
                : occurredAt // ignore: cast_nullable_to_non_nullable
                      as String?,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$ApiKeyAuditEventImplCopyWith<$Res>
    implements $ApiKeyAuditEventCopyWith<$Res> {
  factory _$$ApiKeyAuditEventImplCopyWith(
    _$ApiKeyAuditEventImpl value,
    $Res Function(_$ApiKeyAuditEventImpl) then,
  ) = __$$ApiKeyAuditEventImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String kind,
    String? detail,
    String? clientIp,
    String? occurredAt,
  });
}

/// @nodoc
class __$$ApiKeyAuditEventImplCopyWithImpl<$Res>
    extends _$ApiKeyAuditEventCopyWithImpl<$Res, _$ApiKeyAuditEventImpl>
    implements _$$ApiKeyAuditEventImplCopyWith<$Res> {
  __$$ApiKeyAuditEventImplCopyWithImpl(
    _$ApiKeyAuditEventImpl _value,
    $Res Function(_$ApiKeyAuditEventImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of ApiKeyAuditEvent
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? kind = null,
    Object? detail = freezed,
    Object? clientIp = freezed,
    Object? occurredAt = freezed,
  }) {
    return _then(
      _$ApiKeyAuditEventImpl(
        kind: null == kind
            ? _value.kind
            : kind // ignore: cast_nullable_to_non_nullable
                  as String,
        detail: freezed == detail
            ? _value.detail
            : detail // ignore: cast_nullable_to_non_nullable
                  as String?,
        clientIp: freezed == clientIp
            ? _value.clientIp
            : clientIp // ignore: cast_nullable_to_non_nullable
                  as String?,
        occurredAt: freezed == occurredAt
            ? _value.occurredAt
            : occurredAt // ignore: cast_nullable_to_non_nullable
                  as String?,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$ApiKeyAuditEventImpl implements _ApiKeyAuditEvent {
  const _$ApiKeyAuditEventImpl({
    required this.kind,
    this.detail,
    this.clientIp,
    this.occurredAt,
  });

  factory _$ApiKeyAuditEventImpl.fromJson(Map<String, dynamic> json) =>
      _$$ApiKeyAuditEventImplFromJson(json);

  @override
  final String kind;
  @override
  final String? detail;
  @override
  final String? clientIp;
  @override
  final String? occurredAt;

  @override
  String toString() {
    return 'ApiKeyAuditEvent(kind: $kind, detail: $detail, clientIp: $clientIp, occurredAt: $occurredAt)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$ApiKeyAuditEventImpl &&
            (identical(other.kind, kind) || other.kind == kind) &&
            (identical(other.detail, detail) || other.detail == detail) &&
            (identical(other.clientIp, clientIp) ||
                other.clientIp == clientIp) &&
            (identical(other.occurredAt, occurredAt) ||
                other.occurredAt == occurredAt));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode =>
      Object.hash(runtimeType, kind, detail, clientIp, occurredAt);

  /// Create a copy of ApiKeyAuditEvent
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$ApiKeyAuditEventImplCopyWith<_$ApiKeyAuditEventImpl> get copyWith =>
      __$$ApiKeyAuditEventImplCopyWithImpl<_$ApiKeyAuditEventImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$ApiKeyAuditEventImplToJson(this);
  }
}

abstract class _ApiKeyAuditEvent implements ApiKeyAuditEvent {
  const factory _ApiKeyAuditEvent({
    required final String kind,
    final String? detail,
    final String? clientIp,
    final String? occurredAt,
  }) = _$ApiKeyAuditEventImpl;

  factory _ApiKeyAuditEvent.fromJson(Map<String, dynamic> json) =
      _$ApiKeyAuditEventImpl.fromJson;

  @override
  String get kind;
  @override
  String? get detail;
  @override
  String? get clientIp;
  @override
  String? get occurredAt;

  /// Create a copy of ApiKeyAuditEvent
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$ApiKeyAuditEventImplCopyWith<_$ApiKeyAuditEventImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

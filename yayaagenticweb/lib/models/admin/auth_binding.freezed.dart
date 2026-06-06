// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'auth_binding.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

AuthBindingRequest _$AuthBindingRequestFromJson(Map<String, dynamic> json) {
  return _AuthBindingRequest.fromJson(json);
}

/// @nodoc
mixin _$AuthBindingRequest {
  String get tenant => throw _privateConstructorUsedError;
  String get id => throw _privateConstructorUsedError;
  String get authenticatorRef => throw _privateConstructorUsedError;
  List<String> get authorizerChain => throw _privateConstructorUsedError;

  /// Serializes this AuthBindingRequest to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of AuthBindingRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $AuthBindingRequestCopyWith<AuthBindingRequest> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $AuthBindingRequestCopyWith<$Res> {
  factory $AuthBindingRequestCopyWith(
    AuthBindingRequest value,
    $Res Function(AuthBindingRequest) then,
  ) = _$AuthBindingRequestCopyWithImpl<$Res, AuthBindingRequest>;
  @useResult
  $Res call({
    String tenant,
    String id,
    String authenticatorRef,
    List<String> authorizerChain,
  });
}

/// @nodoc
class _$AuthBindingRequestCopyWithImpl<$Res, $Val extends AuthBindingRequest>
    implements $AuthBindingRequestCopyWith<$Res> {
  _$AuthBindingRequestCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of AuthBindingRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenant = null,
    Object? id = null,
    Object? authenticatorRef = null,
    Object? authorizerChain = null,
  }) {
    return _then(
      _value.copyWith(
            tenant: null == tenant
                ? _value.tenant
                : tenant // ignore: cast_nullable_to_non_nullable
                      as String,
            id: null == id
                ? _value.id
                : id // ignore: cast_nullable_to_non_nullable
                      as String,
            authenticatorRef: null == authenticatorRef
                ? _value.authenticatorRef
                : authenticatorRef // ignore: cast_nullable_to_non_nullable
                      as String,
            authorizerChain: null == authorizerChain
                ? _value.authorizerChain
                : authorizerChain // ignore: cast_nullable_to_non_nullable
                      as List<String>,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$AuthBindingRequestImplCopyWith<$Res>
    implements $AuthBindingRequestCopyWith<$Res> {
  factory _$$AuthBindingRequestImplCopyWith(
    _$AuthBindingRequestImpl value,
    $Res Function(_$AuthBindingRequestImpl) then,
  ) = __$$AuthBindingRequestImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String tenant,
    String id,
    String authenticatorRef,
    List<String> authorizerChain,
  });
}

/// @nodoc
class __$$AuthBindingRequestImplCopyWithImpl<$Res>
    extends _$AuthBindingRequestCopyWithImpl<$Res, _$AuthBindingRequestImpl>
    implements _$$AuthBindingRequestImplCopyWith<$Res> {
  __$$AuthBindingRequestImplCopyWithImpl(
    _$AuthBindingRequestImpl _value,
    $Res Function(_$AuthBindingRequestImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of AuthBindingRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenant = null,
    Object? id = null,
    Object? authenticatorRef = null,
    Object? authorizerChain = null,
  }) {
    return _then(
      _$AuthBindingRequestImpl(
        tenant: null == tenant
            ? _value.tenant
            : tenant // ignore: cast_nullable_to_non_nullable
                  as String,
        id: null == id
            ? _value.id
            : id // ignore: cast_nullable_to_non_nullable
                  as String,
        authenticatorRef: null == authenticatorRef
            ? _value.authenticatorRef
            : authenticatorRef // ignore: cast_nullable_to_non_nullable
                  as String,
        authorizerChain: null == authorizerChain
            ? _value._authorizerChain
            : authorizerChain // ignore: cast_nullable_to_non_nullable
                  as List<String>,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$AuthBindingRequestImpl implements _AuthBindingRequest {
  const _$AuthBindingRequestImpl({
    required this.tenant,
    required this.id,
    required this.authenticatorRef,
    final List<String> authorizerChain = const <String>[],
  }) : _authorizerChain = authorizerChain;

  factory _$AuthBindingRequestImpl.fromJson(Map<String, dynamic> json) =>
      _$$AuthBindingRequestImplFromJson(json);

  @override
  final String tenant;
  @override
  final String id;
  @override
  final String authenticatorRef;
  final List<String> _authorizerChain;
  @override
  @JsonKey()
  List<String> get authorizerChain {
    if (_authorizerChain is EqualUnmodifiableListView) return _authorizerChain;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_authorizerChain);
  }

  @override
  String toString() {
    return 'AuthBindingRequest(tenant: $tenant, id: $id, authenticatorRef: $authenticatorRef, authorizerChain: $authorizerChain)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$AuthBindingRequestImpl &&
            (identical(other.tenant, tenant) || other.tenant == tenant) &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.authenticatorRef, authenticatorRef) ||
                other.authenticatorRef == authenticatorRef) &&
            const DeepCollectionEquality().equals(
              other._authorizerChain,
              _authorizerChain,
            ));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    tenant,
    id,
    authenticatorRef,
    const DeepCollectionEquality().hash(_authorizerChain),
  );

  /// Create a copy of AuthBindingRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$AuthBindingRequestImplCopyWith<_$AuthBindingRequestImpl> get copyWith =>
      __$$AuthBindingRequestImplCopyWithImpl<_$AuthBindingRequestImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$AuthBindingRequestImplToJson(this);
  }
}

abstract class _AuthBindingRequest implements AuthBindingRequest {
  const factory _AuthBindingRequest({
    required final String tenant,
    required final String id,
    required final String authenticatorRef,
    final List<String> authorizerChain,
  }) = _$AuthBindingRequestImpl;

  factory _AuthBindingRequest.fromJson(Map<String, dynamic> json) =
      _$AuthBindingRequestImpl.fromJson;

  @override
  String get tenant;
  @override
  String get id;
  @override
  String get authenticatorRef;
  @override
  List<String> get authorizerChain;

  /// Create a copy of AuthBindingRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$AuthBindingRequestImplCopyWith<_$AuthBindingRequestImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

AuthBindingResponse _$AuthBindingResponseFromJson(Map<String, dynamic> json) {
  return _AuthBindingResponse.fromJson(json);
}

/// @nodoc
mixin _$AuthBindingResponse {
  String get id => throw _privateConstructorUsedError;
  String get tenant => throw _privateConstructorUsedError;
  String get authenticatorRef => throw _privateConstructorUsedError;
  List<String> get authorizerChain => throw _privateConstructorUsedError;
  String? get createdAt => throw _privateConstructorUsedError;

  /// Serializes this AuthBindingResponse to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of AuthBindingResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $AuthBindingResponseCopyWith<AuthBindingResponse> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $AuthBindingResponseCopyWith<$Res> {
  factory $AuthBindingResponseCopyWith(
    AuthBindingResponse value,
    $Res Function(AuthBindingResponse) then,
  ) = _$AuthBindingResponseCopyWithImpl<$Res, AuthBindingResponse>;
  @useResult
  $Res call({
    String id,
    String tenant,
    String authenticatorRef,
    List<String> authorizerChain,
    String? createdAt,
  });
}

/// @nodoc
class _$AuthBindingResponseCopyWithImpl<$Res, $Val extends AuthBindingResponse>
    implements $AuthBindingResponseCopyWith<$Res> {
  _$AuthBindingResponseCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of AuthBindingResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? tenant = null,
    Object? authenticatorRef = null,
    Object? authorizerChain = null,
    Object? createdAt = freezed,
  }) {
    return _then(
      _value.copyWith(
            id: null == id
                ? _value.id
                : id // ignore: cast_nullable_to_non_nullable
                      as String,
            tenant: null == tenant
                ? _value.tenant
                : tenant // ignore: cast_nullable_to_non_nullable
                      as String,
            authenticatorRef: null == authenticatorRef
                ? _value.authenticatorRef
                : authenticatorRef // ignore: cast_nullable_to_non_nullable
                      as String,
            authorizerChain: null == authorizerChain
                ? _value.authorizerChain
                : authorizerChain // ignore: cast_nullable_to_non_nullable
                      as List<String>,
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
abstract class _$$AuthBindingResponseImplCopyWith<$Res>
    implements $AuthBindingResponseCopyWith<$Res> {
  factory _$$AuthBindingResponseImplCopyWith(
    _$AuthBindingResponseImpl value,
    $Res Function(_$AuthBindingResponseImpl) then,
  ) = __$$AuthBindingResponseImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String id,
    String tenant,
    String authenticatorRef,
    List<String> authorizerChain,
    String? createdAt,
  });
}

/// @nodoc
class __$$AuthBindingResponseImplCopyWithImpl<$Res>
    extends _$AuthBindingResponseCopyWithImpl<$Res, _$AuthBindingResponseImpl>
    implements _$$AuthBindingResponseImplCopyWith<$Res> {
  __$$AuthBindingResponseImplCopyWithImpl(
    _$AuthBindingResponseImpl _value,
    $Res Function(_$AuthBindingResponseImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of AuthBindingResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? tenant = null,
    Object? authenticatorRef = null,
    Object? authorizerChain = null,
    Object? createdAt = freezed,
  }) {
    return _then(
      _$AuthBindingResponseImpl(
        id: null == id
            ? _value.id
            : id // ignore: cast_nullable_to_non_nullable
                  as String,
        tenant: null == tenant
            ? _value.tenant
            : tenant // ignore: cast_nullable_to_non_nullable
                  as String,
        authenticatorRef: null == authenticatorRef
            ? _value.authenticatorRef
            : authenticatorRef // ignore: cast_nullable_to_non_nullable
                  as String,
        authorizerChain: null == authorizerChain
            ? _value._authorizerChain
            : authorizerChain // ignore: cast_nullable_to_non_nullable
                  as List<String>,
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
class _$AuthBindingResponseImpl implements _AuthBindingResponse {
  const _$AuthBindingResponseImpl({
    required this.id,
    required this.tenant,
    required this.authenticatorRef,
    final List<String> authorizerChain = const <String>[],
    this.createdAt,
  }) : _authorizerChain = authorizerChain;

  factory _$AuthBindingResponseImpl.fromJson(Map<String, dynamic> json) =>
      _$$AuthBindingResponseImplFromJson(json);

  @override
  final String id;
  @override
  final String tenant;
  @override
  final String authenticatorRef;
  final List<String> _authorizerChain;
  @override
  @JsonKey()
  List<String> get authorizerChain {
    if (_authorizerChain is EqualUnmodifiableListView) return _authorizerChain;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_authorizerChain);
  }

  @override
  final String? createdAt;

  @override
  String toString() {
    return 'AuthBindingResponse(id: $id, tenant: $tenant, authenticatorRef: $authenticatorRef, authorizerChain: $authorizerChain, createdAt: $createdAt)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$AuthBindingResponseImpl &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.tenant, tenant) || other.tenant == tenant) &&
            (identical(other.authenticatorRef, authenticatorRef) ||
                other.authenticatorRef == authenticatorRef) &&
            const DeepCollectionEquality().equals(
              other._authorizerChain,
              _authorizerChain,
            ) &&
            (identical(other.createdAt, createdAt) ||
                other.createdAt == createdAt));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    id,
    tenant,
    authenticatorRef,
    const DeepCollectionEquality().hash(_authorizerChain),
    createdAt,
  );

  /// Create a copy of AuthBindingResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$AuthBindingResponseImplCopyWith<_$AuthBindingResponseImpl> get copyWith =>
      __$$AuthBindingResponseImplCopyWithImpl<_$AuthBindingResponseImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$AuthBindingResponseImplToJson(this);
  }
}

abstract class _AuthBindingResponse implements AuthBindingResponse {
  const factory _AuthBindingResponse({
    required final String id,
    required final String tenant,
    required final String authenticatorRef,
    final List<String> authorizerChain,
    final String? createdAt,
  }) = _$AuthBindingResponseImpl;

  factory _AuthBindingResponse.fromJson(Map<String, dynamic> json) =
      _$AuthBindingResponseImpl.fromJson;

  @override
  String get id;
  @override
  String get tenant;
  @override
  String get authenticatorRef;
  @override
  List<String> get authorizerChain;
  @override
  String? get createdAt;

  /// Create a copy of AuthBindingResponse
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$AuthBindingResponseImplCopyWith<_$AuthBindingResponseImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

AuthAvailability _$AuthAvailabilityFromJson(Map<String, dynamic> json) {
  return _AuthAvailability.fromJson(json);
}

/// @nodoc
mixin _$AuthAvailability {
  List<String> get authenticators => throw _privateConstructorUsedError;
  List<String> get authorizers => throw _privateConstructorUsedError;

  /// Serializes this AuthAvailability to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of AuthAvailability
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $AuthAvailabilityCopyWith<AuthAvailability> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $AuthAvailabilityCopyWith<$Res> {
  factory $AuthAvailabilityCopyWith(
    AuthAvailability value,
    $Res Function(AuthAvailability) then,
  ) = _$AuthAvailabilityCopyWithImpl<$Res, AuthAvailability>;
  @useResult
  $Res call({List<String> authenticators, List<String> authorizers});
}

/// @nodoc
class _$AuthAvailabilityCopyWithImpl<$Res, $Val extends AuthAvailability>
    implements $AuthAvailabilityCopyWith<$Res> {
  _$AuthAvailabilityCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of AuthAvailability
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? authenticators = null, Object? authorizers = null}) {
    return _then(
      _value.copyWith(
            authenticators: null == authenticators
                ? _value.authenticators
                : authenticators // ignore: cast_nullable_to_non_nullable
                      as List<String>,
            authorizers: null == authorizers
                ? _value.authorizers
                : authorizers // ignore: cast_nullable_to_non_nullable
                      as List<String>,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$AuthAvailabilityImplCopyWith<$Res>
    implements $AuthAvailabilityCopyWith<$Res> {
  factory _$$AuthAvailabilityImplCopyWith(
    _$AuthAvailabilityImpl value,
    $Res Function(_$AuthAvailabilityImpl) then,
  ) = __$$AuthAvailabilityImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({List<String> authenticators, List<String> authorizers});
}

/// @nodoc
class __$$AuthAvailabilityImplCopyWithImpl<$Res>
    extends _$AuthAvailabilityCopyWithImpl<$Res, _$AuthAvailabilityImpl>
    implements _$$AuthAvailabilityImplCopyWith<$Res> {
  __$$AuthAvailabilityImplCopyWithImpl(
    _$AuthAvailabilityImpl _value,
    $Res Function(_$AuthAvailabilityImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of AuthAvailability
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? authenticators = null, Object? authorizers = null}) {
    return _then(
      _$AuthAvailabilityImpl(
        authenticators: null == authenticators
            ? _value._authenticators
            : authenticators // ignore: cast_nullable_to_non_nullable
                  as List<String>,
        authorizers: null == authorizers
            ? _value._authorizers
            : authorizers // ignore: cast_nullable_to_non_nullable
                  as List<String>,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$AuthAvailabilityImpl implements _AuthAvailability {
  const _$AuthAvailabilityImpl({
    final List<String> authenticators = const <String>[],
    final List<String> authorizers = const <String>[],
  }) : _authenticators = authenticators,
       _authorizers = authorizers;

  factory _$AuthAvailabilityImpl.fromJson(Map<String, dynamic> json) =>
      _$$AuthAvailabilityImplFromJson(json);

  final List<String> _authenticators;
  @override
  @JsonKey()
  List<String> get authenticators {
    if (_authenticators is EqualUnmodifiableListView) return _authenticators;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_authenticators);
  }

  final List<String> _authorizers;
  @override
  @JsonKey()
  List<String> get authorizers {
    if (_authorizers is EqualUnmodifiableListView) return _authorizers;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_authorizers);
  }

  @override
  String toString() {
    return 'AuthAvailability(authenticators: $authenticators, authorizers: $authorizers)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$AuthAvailabilityImpl &&
            const DeepCollectionEquality().equals(
              other._authenticators,
              _authenticators,
            ) &&
            const DeepCollectionEquality().equals(
              other._authorizers,
              _authorizers,
            ));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    const DeepCollectionEquality().hash(_authenticators),
    const DeepCollectionEquality().hash(_authorizers),
  );

  /// Create a copy of AuthAvailability
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$AuthAvailabilityImplCopyWith<_$AuthAvailabilityImpl> get copyWith =>
      __$$AuthAvailabilityImplCopyWithImpl<_$AuthAvailabilityImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$AuthAvailabilityImplToJson(this);
  }
}

abstract class _AuthAvailability implements AuthAvailability {
  const factory _AuthAvailability({
    final List<String> authenticators,
    final List<String> authorizers,
  }) = _$AuthAvailabilityImpl;

  factory _AuthAvailability.fromJson(Map<String, dynamic> json) =
      _$AuthAvailabilityImpl.fromJson;

  @override
  List<String> get authenticators;
  @override
  List<String> get authorizers;

  /// Create a copy of AuthAvailability
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$AuthAvailabilityImplCopyWith<_$AuthAvailabilityImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

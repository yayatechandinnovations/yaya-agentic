// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'profile.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

ProfileRequest _$ProfileRequestFromJson(Map<String, dynamic> json) {
  return _ProfileRequest.fromJson(json);
}

/// @nodoc
mixin _$ProfileRequest {
  String get tenant => throw _privateConstructorUsedError;
  String get id => throw _privateConstructorUsedError;
  String get displayName => throw _privateConstructorUsedError;
  String get introOneLiner => throw _privateConstructorUsedError;
  String get systemPromptFragment => throw _privateConstructorUsedError;
  List<String> get capabilities => throw _privateConstructorUsedError;
  String? get authBindingId => throw _privateConstructorUsedError;
  String get language => throw _privateConstructorUsedError;
  Map<String, dynamic> get metadata => throw _privateConstructorUsedError;

  /// Serializes this ProfileRequest to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of ProfileRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $ProfileRequestCopyWith<ProfileRequest> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $ProfileRequestCopyWith<$Res> {
  factory $ProfileRequestCopyWith(
    ProfileRequest value,
    $Res Function(ProfileRequest) then,
  ) = _$ProfileRequestCopyWithImpl<$Res, ProfileRequest>;
  @useResult
  $Res call({
    String tenant,
    String id,
    String displayName,
    String introOneLiner,
    String systemPromptFragment,
    List<String> capabilities,
    String? authBindingId,
    String language,
    Map<String, dynamic> metadata,
  });
}

/// @nodoc
class _$ProfileRequestCopyWithImpl<$Res, $Val extends ProfileRequest>
    implements $ProfileRequestCopyWith<$Res> {
  _$ProfileRequestCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of ProfileRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenant = null,
    Object? id = null,
    Object? displayName = null,
    Object? introOneLiner = null,
    Object? systemPromptFragment = null,
    Object? capabilities = null,
    Object? authBindingId = freezed,
    Object? language = null,
    Object? metadata = null,
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
            displayName: null == displayName
                ? _value.displayName
                : displayName // ignore: cast_nullable_to_non_nullable
                      as String,
            introOneLiner: null == introOneLiner
                ? _value.introOneLiner
                : introOneLiner // ignore: cast_nullable_to_non_nullable
                      as String,
            systemPromptFragment: null == systemPromptFragment
                ? _value.systemPromptFragment
                : systemPromptFragment // ignore: cast_nullable_to_non_nullable
                      as String,
            capabilities: null == capabilities
                ? _value.capabilities
                : capabilities // ignore: cast_nullable_to_non_nullable
                      as List<String>,
            authBindingId: freezed == authBindingId
                ? _value.authBindingId
                : authBindingId // ignore: cast_nullable_to_non_nullable
                      as String?,
            language: null == language
                ? _value.language
                : language // ignore: cast_nullable_to_non_nullable
                      as String,
            metadata: null == metadata
                ? _value.metadata
                : metadata // ignore: cast_nullable_to_non_nullable
                      as Map<String, dynamic>,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$ProfileRequestImplCopyWith<$Res>
    implements $ProfileRequestCopyWith<$Res> {
  factory _$$ProfileRequestImplCopyWith(
    _$ProfileRequestImpl value,
    $Res Function(_$ProfileRequestImpl) then,
  ) = __$$ProfileRequestImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String tenant,
    String id,
    String displayName,
    String introOneLiner,
    String systemPromptFragment,
    List<String> capabilities,
    String? authBindingId,
    String language,
    Map<String, dynamic> metadata,
  });
}

/// @nodoc
class __$$ProfileRequestImplCopyWithImpl<$Res>
    extends _$ProfileRequestCopyWithImpl<$Res, _$ProfileRequestImpl>
    implements _$$ProfileRequestImplCopyWith<$Res> {
  __$$ProfileRequestImplCopyWithImpl(
    _$ProfileRequestImpl _value,
    $Res Function(_$ProfileRequestImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of ProfileRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenant = null,
    Object? id = null,
    Object? displayName = null,
    Object? introOneLiner = null,
    Object? systemPromptFragment = null,
    Object? capabilities = null,
    Object? authBindingId = freezed,
    Object? language = null,
    Object? metadata = null,
  }) {
    return _then(
      _$ProfileRequestImpl(
        tenant: null == tenant
            ? _value.tenant
            : tenant // ignore: cast_nullable_to_non_nullable
                  as String,
        id: null == id
            ? _value.id
            : id // ignore: cast_nullable_to_non_nullable
                  as String,
        displayName: null == displayName
            ? _value.displayName
            : displayName // ignore: cast_nullable_to_non_nullable
                  as String,
        introOneLiner: null == introOneLiner
            ? _value.introOneLiner
            : introOneLiner // ignore: cast_nullable_to_non_nullable
                  as String,
        systemPromptFragment: null == systemPromptFragment
            ? _value.systemPromptFragment
            : systemPromptFragment // ignore: cast_nullable_to_non_nullable
                  as String,
        capabilities: null == capabilities
            ? _value._capabilities
            : capabilities // ignore: cast_nullable_to_non_nullable
                  as List<String>,
        authBindingId: freezed == authBindingId
            ? _value.authBindingId
            : authBindingId // ignore: cast_nullable_to_non_nullable
                  as String?,
        language: null == language
            ? _value.language
            : language // ignore: cast_nullable_to_non_nullable
                  as String,
        metadata: null == metadata
            ? _value._metadata
            : metadata // ignore: cast_nullable_to_non_nullable
                  as Map<String, dynamic>,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$ProfileRequestImpl implements _ProfileRequest {
  const _$ProfileRequestImpl({
    required this.tenant,
    required this.id,
    required this.displayName,
    required this.introOneLiner,
    required this.systemPromptFragment,
    final List<String> capabilities = const <String>[],
    this.authBindingId,
    this.language = 'en',
    final Map<String, dynamic> metadata = const <String, dynamic>{},
  }) : _capabilities = capabilities,
       _metadata = metadata;

  factory _$ProfileRequestImpl.fromJson(Map<String, dynamic> json) =>
      _$$ProfileRequestImplFromJson(json);

  @override
  final String tenant;
  @override
  final String id;
  @override
  final String displayName;
  @override
  final String introOneLiner;
  @override
  final String systemPromptFragment;
  final List<String> _capabilities;
  @override
  @JsonKey()
  List<String> get capabilities {
    if (_capabilities is EqualUnmodifiableListView) return _capabilities;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_capabilities);
  }

  @override
  final String? authBindingId;
  @override
  @JsonKey()
  final String language;
  final Map<String, dynamic> _metadata;
  @override
  @JsonKey()
  Map<String, dynamic> get metadata {
    if (_metadata is EqualUnmodifiableMapView) return _metadata;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_metadata);
  }

  @override
  String toString() {
    return 'ProfileRequest(tenant: $tenant, id: $id, displayName: $displayName, introOneLiner: $introOneLiner, systemPromptFragment: $systemPromptFragment, capabilities: $capabilities, authBindingId: $authBindingId, language: $language, metadata: $metadata)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$ProfileRequestImpl &&
            (identical(other.tenant, tenant) || other.tenant == tenant) &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.displayName, displayName) ||
                other.displayName == displayName) &&
            (identical(other.introOneLiner, introOneLiner) ||
                other.introOneLiner == introOneLiner) &&
            (identical(other.systemPromptFragment, systemPromptFragment) ||
                other.systemPromptFragment == systemPromptFragment) &&
            const DeepCollectionEquality().equals(
              other._capabilities,
              _capabilities,
            ) &&
            (identical(other.authBindingId, authBindingId) ||
                other.authBindingId == authBindingId) &&
            (identical(other.language, language) ||
                other.language == language) &&
            const DeepCollectionEquality().equals(other._metadata, _metadata));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    tenant,
    id,
    displayName,
    introOneLiner,
    systemPromptFragment,
    const DeepCollectionEquality().hash(_capabilities),
    authBindingId,
    language,
    const DeepCollectionEquality().hash(_metadata),
  );

  /// Create a copy of ProfileRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$ProfileRequestImplCopyWith<_$ProfileRequestImpl> get copyWith =>
      __$$ProfileRequestImplCopyWithImpl<_$ProfileRequestImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$ProfileRequestImplToJson(this);
  }
}

abstract class _ProfileRequest implements ProfileRequest {
  const factory _ProfileRequest({
    required final String tenant,
    required final String id,
    required final String displayName,
    required final String introOneLiner,
    required final String systemPromptFragment,
    final List<String> capabilities,
    final String? authBindingId,
    final String language,
    final Map<String, dynamic> metadata,
  }) = _$ProfileRequestImpl;

  factory _ProfileRequest.fromJson(Map<String, dynamic> json) =
      _$ProfileRequestImpl.fromJson;

  @override
  String get tenant;
  @override
  String get id;
  @override
  String get displayName;
  @override
  String get introOneLiner;
  @override
  String get systemPromptFragment;
  @override
  List<String> get capabilities;
  @override
  String? get authBindingId;
  @override
  String get language;
  @override
  Map<String, dynamic> get metadata;

  /// Create a copy of ProfileRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$ProfileRequestImplCopyWith<_$ProfileRequestImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

ProfileResponse _$ProfileResponseFromJson(Map<String, dynamic> json) {
  return _ProfileResponse.fromJson(json);
}

/// @nodoc
mixin _$ProfileResponse {
  String get id => throw _privateConstructorUsedError;
  int get version => throw _privateConstructorUsedError;
  String get tenant => throw _privateConstructorUsedError;
  String get displayName => throw _privateConstructorUsedError;
  String get introOneLiner => throw _privateConstructorUsedError;
  String get systemPromptFragment => throw _privateConstructorUsedError;
  List<String> get capabilities => throw _privateConstructorUsedError;
  String? get authBindingId => throw _privateConstructorUsedError;
  String get language => throw _privateConstructorUsedError;
  Map<String, dynamic> get metadata => throw _privateConstructorUsedError;
  String? get status => throw _privateConstructorUsedError;
  String? get createdAt => throw _privateConstructorUsedError;

  /// Serializes this ProfileResponse to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of ProfileResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $ProfileResponseCopyWith<ProfileResponse> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $ProfileResponseCopyWith<$Res> {
  factory $ProfileResponseCopyWith(
    ProfileResponse value,
    $Res Function(ProfileResponse) then,
  ) = _$ProfileResponseCopyWithImpl<$Res, ProfileResponse>;
  @useResult
  $Res call({
    String id,
    int version,
    String tenant,
    String displayName,
    String introOneLiner,
    String systemPromptFragment,
    List<String> capabilities,
    String? authBindingId,
    String language,
    Map<String, dynamic> metadata,
    String? status,
    String? createdAt,
  });
}

/// @nodoc
class _$ProfileResponseCopyWithImpl<$Res, $Val extends ProfileResponse>
    implements $ProfileResponseCopyWith<$Res> {
  _$ProfileResponseCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of ProfileResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? version = null,
    Object? tenant = null,
    Object? displayName = null,
    Object? introOneLiner = null,
    Object? systemPromptFragment = null,
    Object? capabilities = null,
    Object? authBindingId = freezed,
    Object? language = null,
    Object? metadata = null,
    Object? status = freezed,
    Object? createdAt = freezed,
  }) {
    return _then(
      _value.copyWith(
            id: null == id
                ? _value.id
                : id // ignore: cast_nullable_to_non_nullable
                      as String,
            version: null == version
                ? _value.version
                : version // ignore: cast_nullable_to_non_nullable
                      as int,
            tenant: null == tenant
                ? _value.tenant
                : tenant // ignore: cast_nullable_to_non_nullable
                      as String,
            displayName: null == displayName
                ? _value.displayName
                : displayName // ignore: cast_nullable_to_non_nullable
                      as String,
            introOneLiner: null == introOneLiner
                ? _value.introOneLiner
                : introOneLiner // ignore: cast_nullable_to_non_nullable
                      as String,
            systemPromptFragment: null == systemPromptFragment
                ? _value.systemPromptFragment
                : systemPromptFragment // ignore: cast_nullable_to_non_nullable
                      as String,
            capabilities: null == capabilities
                ? _value.capabilities
                : capabilities // ignore: cast_nullable_to_non_nullable
                      as List<String>,
            authBindingId: freezed == authBindingId
                ? _value.authBindingId
                : authBindingId // ignore: cast_nullable_to_non_nullable
                      as String?,
            language: null == language
                ? _value.language
                : language // ignore: cast_nullable_to_non_nullable
                      as String,
            metadata: null == metadata
                ? _value.metadata
                : metadata // ignore: cast_nullable_to_non_nullable
                      as Map<String, dynamic>,
            status: freezed == status
                ? _value.status
                : status // ignore: cast_nullable_to_non_nullable
                      as String?,
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
abstract class _$$ProfileResponseImplCopyWith<$Res>
    implements $ProfileResponseCopyWith<$Res> {
  factory _$$ProfileResponseImplCopyWith(
    _$ProfileResponseImpl value,
    $Res Function(_$ProfileResponseImpl) then,
  ) = __$$ProfileResponseImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String id,
    int version,
    String tenant,
    String displayName,
    String introOneLiner,
    String systemPromptFragment,
    List<String> capabilities,
    String? authBindingId,
    String language,
    Map<String, dynamic> metadata,
    String? status,
    String? createdAt,
  });
}

/// @nodoc
class __$$ProfileResponseImplCopyWithImpl<$Res>
    extends _$ProfileResponseCopyWithImpl<$Res, _$ProfileResponseImpl>
    implements _$$ProfileResponseImplCopyWith<$Res> {
  __$$ProfileResponseImplCopyWithImpl(
    _$ProfileResponseImpl _value,
    $Res Function(_$ProfileResponseImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of ProfileResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? version = null,
    Object? tenant = null,
    Object? displayName = null,
    Object? introOneLiner = null,
    Object? systemPromptFragment = null,
    Object? capabilities = null,
    Object? authBindingId = freezed,
    Object? language = null,
    Object? metadata = null,
    Object? status = freezed,
    Object? createdAt = freezed,
  }) {
    return _then(
      _$ProfileResponseImpl(
        id: null == id
            ? _value.id
            : id // ignore: cast_nullable_to_non_nullable
                  as String,
        version: null == version
            ? _value.version
            : version // ignore: cast_nullable_to_non_nullable
                  as int,
        tenant: null == tenant
            ? _value.tenant
            : tenant // ignore: cast_nullable_to_non_nullable
                  as String,
        displayName: null == displayName
            ? _value.displayName
            : displayName // ignore: cast_nullable_to_non_nullable
                  as String,
        introOneLiner: null == introOneLiner
            ? _value.introOneLiner
            : introOneLiner // ignore: cast_nullable_to_non_nullable
                  as String,
        systemPromptFragment: null == systemPromptFragment
            ? _value.systemPromptFragment
            : systemPromptFragment // ignore: cast_nullable_to_non_nullable
                  as String,
        capabilities: null == capabilities
            ? _value._capabilities
            : capabilities // ignore: cast_nullable_to_non_nullable
                  as List<String>,
        authBindingId: freezed == authBindingId
            ? _value.authBindingId
            : authBindingId // ignore: cast_nullable_to_non_nullable
                  as String?,
        language: null == language
            ? _value.language
            : language // ignore: cast_nullable_to_non_nullable
                  as String,
        metadata: null == metadata
            ? _value._metadata
            : metadata // ignore: cast_nullable_to_non_nullable
                  as Map<String, dynamic>,
        status: freezed == status
            ? _value.status
            : status // ignore: cast_nullable_to_non_nullable
                  as String?,
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
class _$ProfileResponseImpl implements _ProfileResponse {
  const _$ProfileResponseImpl({
    required this.id,
    required this.version,
    required this.tenant,
    required this.displayName,
    required this.introOneLiner,
    required this.systemPromptFragment,
    final List<String> capabilities = const <String>[],
    this.authBindingId,
    this.language = 'en',
    final Map<String, dynamic> metadata = const <String, dynamic>{},
    this.status,
    this.createdAt,
  }) : _capabilities = capabilities,
       _metadata = metadata;

  factory _$ProfileResponseImpl.fromJson(Map<String, dynamic> json) =>
      _$$ProfileResponseImplFromJson(json);

  @override
  final String id;
  @override
  final int version;
  @override
  final String tenant;
  @override
  final String displayName;
  @override
  final String introOneLiner;
  @override
  final String systemPromptFragment;
  final List<String> _capabilities;
  @override
  @JsonKey()
  List<String> get capabilities {
    if (_capabilities is EqualUnmodifiableListView) return _capabilities;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_capabilities);
  }

  @override
  final String? authBindingId;
  @override
  @JsonKey()
  final String language;
  final Map<String, dynamic> _metadata;
  @override
  @JsonKey()
  Map<String, dynamic> get metadata {
    if (_metadata is EqualUnmodifiableMapView) return _metadata;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_metadata);
  }

  @override
  final String? status;
  @override
  final String? createdAt;

  @override
  String toString() {
    return 'ProfileResponse(id: $id, version: $version, tenant: $tenant, displayName: $displayName, introOneLiner: $introOneLiner, systemPromptFragment: $systemPromptFragment, capabilities: $capabilities, authBindingId: $authBindingId, language: $language, metadata: $metadata, status: $status, createdAt: $createdAt)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$ProfileResponseImpl &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.version, version) || other.version == version) &&
            (identical(other.tenant, tenant) || other.tenant == tenant) &&
            (identical(other.displayName, displayName) ||
                other.displayName == displayName) &&
            (identical(other.introOneLiner, introOneLiner) ||
                other.introOneLiner == introOneLiner) &&
            (identical(other.systemPromptFragment, systemPromptFragment) ||
                other.systemPromptFragment == systemPromptFragment) &&
            const DeepCollectionEquality().equals(
              other._capabilities,
              _capabilities,
            ) &&
            (identical(other.authBindingId, authBindingId) ||
                other.authBindingId == authBindingId) &&
            (identical(other.language, language) ||
                other.language == language) &&
            const DeepCollectionEquality().equals(other._metadata, _metadata) &&
            (identical(other.status, status) || other.status == status) &&
            (identical(other.createdAt, createdAt) ||
                other.createdAt == createdAt));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    id,
    version,
    tenant,
    displayName,
    introOneLiner,
    systemPromptFragment,
    const DeepCollectionEquality().hash(_capabilities),
    authBindingId,
    language,
    const DeepCollectionEquality().hash(_metadata),
    status,
    createdAt,
  );

  /// Create a copy of ProfileResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$ProfileResponseImplCopyWith<_$ProfileResponseImpl> get copyWith =>
      __$$ProfileResponseImplCopyWithImpl<_$ProfileResponseImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$ProfileResponseImplToJson(this);
  }
}

abstract class _ProfileResponse implements ProfileResponse {
  const factory _ProfileResponse({
    required final String id,
    required final int version,
    required final String tenant,
    required final String displayName,
    required final String introOneLiner,
    required final String systemPromptFragment,
    final List<String> capabilities,
    final String? authBindingId,
    final String language,
    final Map<String, dynamic> metadata,
    final String? status,
    final String? createdAt,
  }) = _$ProfileResponseImpl;

  factory _ProfileResponse.fromJson(Map<String, dynamic> json) =
      _$ProfileResponseImpl.fromJson;

  @override
  String get id;
  @override
  int get version;
  @override
  String get tenant;
  @override
  String get displayName;
  @override
  String get introOneLiner;
  @override
  String get systemPromptFragment;
  @override
  List<String> get capabilities;
  @override
  String? get authBindingId;
  @override
  String get language;
  @override
  Map<String, dynamic> get metadata;
  @override
  String? get status;
  @override
  String? get createdAt;

  /// Create a copy of ProfileResponse
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$ProfileResponseImplCopyWith<_$ProfileResponseImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

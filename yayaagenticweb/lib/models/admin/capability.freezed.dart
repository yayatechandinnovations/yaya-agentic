// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'capability.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

CapabilityRequest _$CapabilityRequestFromJson(Map<String, dynamic> json) {
  return _CapabilityRequest.fromJson(json);
}

/// @nodoc
mixin _$CapabilityRequest {
  String get tenant => throw _privateConstructorUsedError;
  String get id => throw _privateConstructorUsedError;
  String get label => throw _privateConstructorUsedError;
  String? get description => throw _privateConstructorUsedError;
  String? get llmGuidance => throw _privateConstructorUsedError;
  List<String> get tools => throw _privateConstructorUsedError;

  /// Serializes this CapabilityRequest to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of CapabilityRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $CapabilityRequestCopyWith<CapabilityRequest> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $CapabilityRequestCopyWith<$Res> {
  factory $CapabilityRequestCopyWith(
    CapabilityRequest value,
    $Res Function(CapabilityRequest) then,
  ) = _$CapabilityRequestCopyWithImpl<$Res, CapabilityRequest>;
  @useResult
  $Res call({
    String tenant,
    String id,
    String label,
    String? description,
    String? llmGuidance,
    List<String> tools,
  });
}

/// @nodoc
class _$CapabilityRequestCopyWithImpl<$Res, $Val extends CapabilityRequest>
    implements $CapabilityRequestCopyWith<$Res> {
  _$CapabilityRequestCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of CapabilityRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenant = null,
    Object? id = null,
    Object? label = null,
    Object? description = freezed,
    Object? llmGuidance = freezed,
    Object? tools = null,
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
            label: null == label
                ? _value.label
                : label // ignore: cast_nullable_to_non_nullable
                      as String,
            description: freezed == description
                ? _value.description
                : description // ignore: cast_nullable_to_non_nullable
                      as String?,
            llmGuidance: freezed == llmGuidance
                ? _value.llmGuidance
                : llmGuidance // ignore: cast_nullable_to_non_nullable
                      as String?,
            tools: null == tools
                ? _value.tools
                : tools // ignore: cast_nullable_to_non_nullable
                      as List<String>,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$CapabilityRequestImplCopyWith<$Res>
    implements $CapabilityRequestCopyWith<$Res> {
  factory _$$CapabilityRequestImplCopyWith(
    _$CapabilityRequestImpl value,
    $Res Function(_$CapabilityRequestImpl) then,
  ) = __$$CapabilityRequestImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String tenant,
    String id,
    String label,
    String? description,
    String? llmGuidance,
    List<String> tools,
  });
}

/// @nodoc
class __$$CapabilityRequestImplCopyWithImpl<$Res>
    extends _$CapabilityRequestCopyWithImpl<$Res, _$CapabilityRequestImpl>
    implements _$$CapabilityRequestImplCopyWith<$Res> {
  __$$CapabilityRequestImplCopyWithImpl(
    _$CapabilityRequestImpl _value,
    $Res Function(_$CapabilityRequestImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of CapabilityRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenant = null,
    Object? id = null,
    Object? label = null,
    Object? description = freezed,
    Object? llmGuidance = freezed,
    Object? tools = null,
  }) {
    return _then(
      _$CapabilityRequestImpl(
        tenant: null == tenant
            ? _value.tenant
            : tenant // ignore: cast_nullable_to_non_nullable
                  as String,
        id: null == id
            ? _value.id
            : id // ignore: cast_nullable_to_non_nullable
                  as String,
        label: null == label
            ? _value.label
            : label // ignore: cast_nullable_to_non_nullable
                  as String,
        description: freezed == description
            ? _value.description
            : description // ignore: cast_nullable_to_non_nullable
                  as String?,
        llmGuidance: freezed == llmGuidance
            ? _value.llmGuidance
            : llmGuidance // ignore: cast_nullable_to_non_nullable
                  as String?,
        tools: null == tools
            ? _value._tools
            : tools // ignore: cast_nullable_to_non_nullable
                  as List<String>,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$CapabilityRequestImpl implements _CapabilityRequest {
  const _$CapabilityRequestImpl({
    required this.tenant,
    required this.id,
    required this.label,
    this.description,
    this.llmGuidance,
    final List<String> tools = const <String>[],
  }) : _tools = tools;

  factory _$CapabilityRequestImpl.fromJson(Map<String, dynamic> json) =>
      _$$CapabilityRequestImplFromJson(json);

  @override
  final String tenant;
  @override
  final String id;
  @override
  final String label;
  @override
  final String? description;
  @override
  final String? llmGuidance;
  final List<String> _tools;
  @override
  @JsonKey()
  List<String> get tools {
    if (_tools is EqualUnmodifiableListView) return _tools;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_tools);
  }

  @override
  String toString() {
    return 'CapabilityRequest(tenant: $tenant, id: $id, label: $label, description: $description, llmGuidance: $llmGuidance, tools: $tools)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$CapabilityRequestImpl &&
            (identical(other.tenant, tenant) || other.tenant == tenant) &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.label, label) || other.label == label) &&
            (identical(other.description, description) ||
                other.description == description) &&
            (identical(other.llmGuidance, llmGuidance) ||
                other.llmGuidance == llmGuidance) &&
            const DeepCollectionEquality().equals(other._tools, _tools));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    tenant,
    id,
    label,
    description,
    llmGuidance,
    const DeepCollectionEquality().hash(_tools),
  );

  /// Create a copy of CapabilityRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$CapabilityRequestImplCopyWith<_$CapabilityRequestImpl> get copyWith =>
      __$$CapabilityRequestImplCopyWithImpl<_$CapabilityRequestImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$CapabilityRequestImplToJson(this);
  }
}

abstract class _CapabilityRequest implements CapabilityRequest {
  const factory _CapabilityRequest({
    required final String tenant,
    required final String id,
    required final String label,
    final String? description,
    final String? llmGuidance,
    final List<String> tools,
  }) = _$CapabilityRequestImpl;

  factory _CapabilityRequest.fromJson(Map<String, dynamic> json) =
      _$CapabilityRequestImpl.fromJson;

  @override
  String get tenant;
  @override
  String get id;
  @override
  String get label;
  @override
  String? get description;
  @override
  String? get llmGuidance;
  @override
  List<String> get tools;

  /// Create a copy of CapabilityRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$CapabilityRequestImplCopyWith<_$CapabilityRequestImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

CapabilityResponse _$CapabilityResponseFromJson(Map<String, dynamic> json) {
  return _CapabilityResponse.fromJson(json);
}

/// @nodoc
mixin _$CapabilityResponse {
  String get id => throw _privateConstructorUsedError;
  int get version => throw _privateConstructorUsedError;
  String get tenant => throw _privateConstructorUsedError;
  String get label => throw _privateConstructorUsedError;
  String? get description => throw _privateConstructorUsedError;
  String? get llmGuidance => throw _privateConstructorUsedError;
  List<String> get tools => throw _privateConstructorUsedError;
  String? get createdAt => throw _privateConstructorUsedError;

  /// Serializes this CapabilityResponse to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of CapabilityResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $CapabilityResponseCopyWith<CapabilityResponse> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $CapabilityResponseCopyWith<$Res> {
  factory $CapabilityResponseCopyWith(
    CapabilityResponse value,
    $Res Function(CapabilityResponse) then,
  ) = _$CapabilityResponseCopyWithImpl<$Res, CapabilityResponse>;
  @useResult
  $Res call({
    String id,
    int version,
    String tenant,
    String label,
    String? description,
    String? llmGuidance,
    List<String> tools,
    String? createdAt,
  });
}

/// @nodoc
class _$CapabilityResponseCopyWithImpl<$Res, $Val extends CapabilityResponse>
    implements $CapabilityResponseCopyWith<$Res> {
  _$CapabilityResponseCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of CapabilityResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? version = null,
    Object? tenant = null,
    Object? label = null,
    Object? description = freezed,
    Object? llmGuidance = freezed,
    Object? tools = null,
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
            label: null == label
                ? _value.label
                : label // ignore: cast_nullable_to_non_nullable
                      as String,
            description: freezed == description
                ? _value.description
                : description // ignore: cast_nullable_to_non_nullable
                      as String?,
            llmGuidance: freezed == llmGuidance
                ? _value.llmGuidance
                : llmGuidance // ignore: cast_nullable_to_non_nullable
                      as String?,
            tools: null == tools
                ? _value.tools
                : tools // ignore: cast_nullable_to_non_nullable
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
abstract class _$$CapabilityResponseImplCopyWith<$Res>
    implements $CapabilityResponseCopyWith<$Res> {
  factory _$$CapabilityResponseImplCopyWith(
    _$CapabilityResponseImpl value,
    $Res Function(_$CapabilityResponseImpl) then,
  ) = __$$CapabilityResponseImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String id,
    int version,
    String tenant,
    String label,
    String? description,
    String? llmGuidance,
    List<String> tools,
    String? createdAt,
  });
}

/// @nodoc
class __$$CapabilityResponseImplCopyWithImpl<$Res>
    extends _$CapabilityResponseCopyWithImpl<$Res, _$CapabilityResponseImpl>
    implements _$$CapabilityResponseImplCopyWith<$Res> {
  __$$CapabilityResponseImplCopyWithImpl(
    _$CapabilityResponseImpl _value,
    $Res Function(_$CapabilityResponseImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of CapabilityResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? version = null,
    Object? tenant = null,
    Object? label = null,
    Object? description = freezed,
    Object? llmGuidance = freezed,
    Object? tools = null,
    Object? createdAt = freezed,
  }) {
    return _then(
      _$CapabilityResponseImpl(
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
        label: null == label
            ? _value.label
            : label // ignore: cast_nullable_to_non_nullable
                  as String,
        description: freezed == description
            ? _value.description
            : description // ignore: cast_nullable_to_non_nullable
                  as String?,
        llmGuidance: freezed == llmGuidance
            ? _value.llmGuidance
            : llmGuidance // ignore: cast_nullable_to_non_nullable
                  as String?,
        tools: null == tools
            ? _value._tools
            : tools // ignore: cast_nullable_to_non_nullable
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
class _$CapabilityResponseImpl implements _CapabilityResponse {
  const _$CapabilityResponseImpl({
    required this.id,
    required this.version,
    required this.tenant,
    required this.label,
    this.description,
    this.llmGuidance,
    final List<String> tools = const <String>[],
    this.createdAt,
  }) : _tools = tools;

  factory _$CapabilityResponseImpl.fromJson(Map<String, dynamic> json) =>
      _$$CapabilityResponseImplFromJson(json);

  @override
  final String id;
  @override
  final int version;
  @override
  final String tenant;
  @override
  final String label;
  @override
  final String? description;
  @override
  final String? llmGuidance;
  final List<String> _tools;
  @override
  @JsonKey()
  List<String> get tools {
    if (_tools is EqualUnmodifiableListView) return _tools;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_tools);
  }

  @override
  final String? createdAt;

  @override
  String toString() {
    return 'CapabilityResponse(id: $id, version: $version, tenant: $tenant, label: $label, description: $description, llmGuidance: $llmGuidance, tools: $tools, createdAt: $createdAt)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$CapabilityResponseImpl &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.version, version) || other.version == version) &&
            (identical(other.tenant, tenant) || other.tenant == tenant) &&
            (identical(other.label, label) || other.label == label) &&
            (identical(other.description, description) ||
                other.description == description) &&
            (identical(other.llmGuidance, llmGuidance) ||
                other.llmGuidance == llmGuidance) &&
            const DeepCollectionEquality().equals(other._tools, _tools) &&
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
    label,
    description,
    llmGuidance,
    const DeepCollectionEquality().hash(_tools),
    createdAt,
  );

  /// Create a copy of CapabilityResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$CapabilityResponseImplCopyWith<_$CapabilityResponseImpl> get copyWith =>
      __$$CapabilityResponseImplCopyWithImpl<_$CapabilityResponseImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$CapabilityResponseImplToJson(this);
  }
}

abstract class _CapabilityResponse implements CapabilityResponse {
  const factory _CapabilityResponse({
    required final String id,
    required final int version,
    required final String tenant,
    required final String label,
    final String? description,
    final String? llmGuidance,
    final List<String> tools,
    final String? createdAt,
  }) = _$CapabilityResponseImpl;

  factory _CapabilityResponse.fromJson(Map<String, dynamic> json) =
      _$CapabilityResponseImpl.fromJson;

  @override
  String get id;
  @override
  int get version;
  @override
  String get tenant;
  @override
  String get label;
  @override
  String? get description;
  @override
  String? get llmGuidance;
  @override
  List<String> get tools;
  @override
  String? get createdAt;

  /// Create a copy of CapabilityResponse
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$CapabilityResponseImplCopyWith<_$CapabilityResponseImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'tenant.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

TenantRequest _$TenantRequestFromJson(Map<String, dynamic> json) {
  return _TenantRequest.fromJson(json);
}

/// @nodoc
mixin _$TenantRequest {
  String? get id => throw _privateConstructorUsedError;
  String? get displayName => throw _privateConstructorUsedError;
  String? get hostBaseUrl => throw _privateConstructorUsedError;
  List<String> get hostBaseUrlAllowlist => throw _privateConstructorUsedError;
  List<String> get inboundOriginAllowlist => throw _privateConstructorUsedError;
  bool? get requireHttps => throw _privateConstructorUsedError;
  String? get defaultAuthenticatorBindingId =>
      throw _privateConstructorUsedError;
  int? get defaultRecordingStrategyId => throw _privateConstructorUsedError;
  Map<String, dynamic> get settings => throw _privateConstructorUsedError;

  /// Serializes this TenantRequest to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of TenantRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $TenantRequestCopyWith<TenantRequest> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $TenantRequestCopyWith<$Res> {
  factory $TenantRequestCopyWith(
    TenantRequest value,
    $Res Function(TenantRequest) then,
  ) = _$TenantRequestCopyWithImpl<$Res, TenantRequest>;
  @useResult
  $Res call({
    String? id,
    String? displayName,
    String? hostBaseUrl,
    List<String> hostBaseUrlAllowlist,
    List<String> inboundOriginAllowlist,
    bool? requireHttps,
    String? defaultAuthenticatorBindingId,
    int? defaultRecordingStrategyId,
    Map<String, dynamic> settings,
  });
}

/// @nodoc
class _$TenantRequestCopyWithImpl<$Res, $Val extends TenantRequest>
    implements $TenantRequestCopyWith<$Res> {
  _$TenantRequestCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of TenantRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = freezed,
    Object? displayName = freezed,
    Object? hostBaseUrl = freezed,
    Object? hostBaseUrlAllowlist = null,
    Object? inboundOriginAllowlist = null,
    Object? requireHttps = freezed,
    Object? defaultAuthenticatorBindingId = freezed,
    Object? defaultRecordingStrategyId = freezed,
    Object? settings = null,
  }) {
    return _then(
      _value.copyWith(
            id: freezed == id
                ? _value.id
                : id // ignore: cast_nullable_to_non_nullable
                      as String?,
            displayName: freezed == displayName
                ? _value.displayName
                : displayName // ignore: cast_nullable_to_non_nullable
                      as String?,
            hostBaseUrl: freezed == hostBaseUrl
                ? _value.hostBaseUrl
                : hostBaseUrl // ignore: cast_nullable_to_non_nullable
                      as String?,
            hostBaseUrlAllowlist: null == hostBaseUrlAllowlist
                ? _value.hostBaseUrlAllowlist
                : hostBaseUrlAllowlist // ignore: cast_nullable_to_non_nullable
                      as List<String>,
            inboundOriginAllowlist: null == inboundOriginAllowlist
                ? _value.inboundOriginAllowlist
                : inboundOriginAllowlist // ignore: cast_nullable_to_non_nullable
                      as List<String>,
            requireHttps: freezed == requireHttps
                ? _value.requireHttps
                : requireHttps // ignore: cast_nullable_to_non_nullable
                      as bool?,
            defaultAuthenticatorBindingId:
                freezed == defaultAuthenticatorBindingId
                ? _value.defaultAuthenticatorBindingId
                : defaultAuthenticatorBindingId // ignore: cast_nullable_to_non_nullable
                      as String?,
            defaultRecordingStrategyId: freezed == defaultRecordingStrategyId
                ? _value.defaultRecordingStrategyId
                : defaultRecordingStrategyId // ignore: cast_nullable_to_non_nullable
                      as int?,
            settings: null == settings
                ? _value.settings
                : settings // ignore: cast_nullable_to_non_nullable
                      as Map<String, dynamic>,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$TenantRequestImplCopyWith<$Res>
    implements $TenantRequestCopyWith<$Res> {
  factory _$$TenantRequestImplCopyWith(
    _$TenantRequestImpl value,
    $Res Function(_$TenantRequestImpl) then,
  ) = __$$TenantRequestImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String? id,
    String? displayName,
    String? hostBaseUrl,
    List<String> hostBaseUrlAllowlist,
    List<String> inboundOriginAllowlist,
    bool? requireHttps,
    String? defaultAuthenticatorBindingId,
    int? defaultRecordingStrategyId,
    Map<String, dynamic> settings,
  });
}

/// @nodoc
class __$$TenantRequestImplCopyWithImpl<$Res>
    extends _$TenantRequestCopyWithImpl<$Res, _$TenantRequestImpl>
    implements _$$TenantRequestImplCopyWith<$Res> {
  __$$TenantRequestImplCopyWithImpl(
    _$TenantRequestImpl _value,
    $Res Function(_$TenantRequestImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of TenantRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = freezed,
    Object? displayName = freezed,
    Object? hostBaseUrl = freezed,
    Object? hostBaseUrlAllowlist = null,
    Object? inboundOriginAllowlist = null,
    Object? requireHttps = freezed,
    Object? defaultAuthenticatorBindingId = freezed,
    Object? defaultRecordingStrategyId = freezed,
    Object? settings = null,
  }) {
    return _then(
      _$TenantRequestImpl(
        id: freezed == id
            ? _value.id
            : id // ignore: cast_nullable_to_non_nullable
                  as String?,
        displayName: freezed == displayName
            ? _value.displayName
            : displayName // ignore: cast_nullable_to_non_nullable
                  as String?,
        hostBaseUrl: freezed == hostBaseUrl
            ? _value.hostBaseUrl
            : hostBaseUrl // ignore: cast_nullable_to_non_nullable
                  as String?,
        hostBaseUrlAllowlist: null == hostBaseUrlAllowlist
            ? _value._hostBaseUrlAllowlist
            : hostBaseUrlAllowlist // ignore: cast_nullable_to_non_nullable
                  as List<String>,
        inboundOriginAllowlist: null == inboundOriginAllowlist
            ? _value._inboundOriginAllowlist
            : inboundOriginAllowlist // ignore: cast_nullable_to_non_nullable
                  as List<String>,
        requireHttps: freezed == requireHttps
            ? _value.requireHttps
            : requireHttps // ignore: cast_nullable_to_non_nullable
                  as bool?,
        defaultAuthenticatorBindingId: freezed == defaultAuthenticatorBindingId
            ? _value.defaultAuthenticatorBindingId
            : defaultAuthenticatorBindingId // ignore: cast_nullable_to_non_nullable
                  as String?,
        defaultRecordingStrategyId: freezed == defaultRecordingStrategyId
            ? _value.defaultRecordingStrategyId
            : defaultRecordingStrategyId // ignore: cast_nullable_to_non_nullable
                  as int?,
        settings: null == settings
            ? _value._settings
            : settings // ignore: cast_nullable_to_non_nullable
                  as Map<String, dynamic>,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$TenantRequestImpl implements _TenantRequest {
  const _$TenantRequestImpl({
    this.id,
    this.displayName,
    this.hostBaseUrl,
    final List<String> hostBaseUrlAllowlist = const <String>[],
    final List<String> inboundOriginAllowlist = const <String>[],
    this.requireHttps = true,
    this.defaultAuthenticatorBindingId,
    this.defaultRecordingStrategyId,
    final Map<String, dynamic> settings = const <String, dynamic>{},
  }) : _hostBaseUrlAllowlist = hostBaseUrlAllowlist,
       _inboundOriginAllowlist = inboundOriginAllowlist,
       _settings = settings;

  factory _$TenantRequestImpl.fromJson(Map<String, dynamic> json) =>
      _$$TenantRequestImplFromJson(json);

  @override
  final String? id;
  @override
  final String? displayName;
  @override
  final String? hostBaseUrl;
  final List<String> _hostBaseUrlAllowlist;
  @override
  @JsonKey()
  List<String> get hostBaseUrlAllowlist {
    if (_hostBaseUrlAllowlist is EqualUnmodifiableListView)
      return _hostBaseUrlAllowlist;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_hostBaseUrlAllowlist);
  }

  final List<String> _inboundOriginAllowlist;
  @override
  @JsonKey()
  List<String> get inboundOriginAllowlist {
    if (_inboundOriginAllowlist is EqualUnmodifiableListView)
      return _inboundOriginAllowlist;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_inboundOriginAllowlist);
  }

  @override
  @JsonKey()
  final bool? requireHttps;
  @override
  final String? defaultAuthenticatorBindingId;
  @override
  final int? defaultRecordingStrategyId;
  final Map<String, dynamic> _settings;
  @override
  @JsonKey()
  Map<String, dynamic> get settings {
    if (_settings is EqualUnmodifiableMapView) return _settings;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_settings);
  }

  @override
  String toString() {
    return 'TenantRequest(id: $id, displayName: $displayName, hostBaseUrl: $hostBaseUrl, hostBaseUrlAllowlist: $hostBaseUrlAllowlist, inboundOriginAllowlist: $inboundOriginAllowlist, requireHttps: $requireHttps, defaultAuthenticatorBindingId: $defaultAuthenticatorBindingId, defaultRecordingStrategyId: $defaultRecordingStrategyId, settings: $settings)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$TenantRequestImpl &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.displayName, displayName) ||
                other.displayName == displayName) &&
            (identical(other.hostBaseUrl, hostBaseUrl) ||
                other.hostBaseUrl == hostBaseUrl) &&
            const DeepCollectionEquality().equals(
              other._hostBaseUrlAllowlist,
              _hostBaseUrlAllowlist,
            ) &&
            const DeepCollectionEquality().equals(
              other._inboundOriginAllowlist,
              _inboundOriginAllowlist,
            ) &&
            (identical(other.requireHttps, requireHttps) ||
                other.requireHttps == requireHttps) &&
            (identical(
                  other.defaultAuthenticatorBindingId,
                  defaultAuthenticatorBindingId,
                ) ||
                other.defaultAuthenticatorBindingId ==
                    defaultAuthenticatorBindingId) &&
            (identical(
                  other.defaultRecordingStrategyId,
                  defaultRecordingStrategyId,
                ) ||
                other.defaultRecordingStrategyId ==
                    defaultRecordingStrategyId) &&
            const DeepCollectionEquality().equals(other._settings, _settings));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    id,
    displayName,
    hostBaseUrl,
    const DeepCollectionEquality().hash(_hostBaseUrlAllowlist),
    const DeepCollectionEquality().hash(_inboundOriginAllowlist),
    requireHttps,
    defaultAuthenticatorBindingId,
    defaultRecordingStrategyId,
    const DeepCollectionEquality().hash(_settings),
  );

  /// Create a copy of TenantRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$TenantRequestImplCopyWith<_$TenantRequestImpl> get copyWith =>
      __$$TenantRequestImplCopyWithImpl<_$TenantRequestImpl>(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$TenantRequestImplToJson(this);
  }
}

abstract class _TenantRequest implements TenantRequest {
  const factory _TenantRequest({
    final String? id,
    final String? displayName,
    final String? hostBaseUrl,
    final List<String> hostBaseUrlAllowlist,
    final List<String> inboundOriginAllowlist,
    final bool? requireHttps,
    final String? defaultAuthenticatorBindingId,
    final int? defaultRecordingStrategyId,
    final Map<String, dynamic> settings,
  }) = _$TenantRequestImpl;

  factory _TenantRequest.fromJson(Map<String, dynamic> json) =
      _$TenantRequestImpl.fromJson;

  @override
  String? get id;
  @override
  String? get displayName;
  @override
  String? get hostBaseUrl;
  @override
  List<String> get hostBaseUrlAllowlist;
  @override
  List<String> get inboundOriginAllowlist;
  @override
  bool? get requireHttps;
  @override
  String? get defaultAuthenticatorBindingId;
  @override
  int? get defaultRecordingStrategyId;
  @override
  Map<String, dynamic> get settings;

  /// Create a copy of TenantRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$TenantRequestImplCopyWith<_$TenantRequestImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

TenantResponse _$TenantResponseFromJson(Map<String, dynamic> json) {
  return _TenantResponse.fromJson(json);
}

/// @nodoc
mixin _$TenantResponse {
  String get id => throw _privateConstructorUsedError;
  String get displayName => throw _privateConstructorUsedError;
  String get status =>
      throw _privateConstructorUsedError; // ACTIVE | SUSPENDED | ARCHIVED
  String? get hostBaseUrl => throw _privateConstructorUsedError;
  List<String> get hostBaseUrlAllowlist => throw _privateConstructorUsedError;
  List<String> get inboundOriginAllowlist => throw _privateConstructorUsedError;
  bool get requireHttps => throw _privateConstructorUsedError;
  String? get defaultAuthenticatorBindingId =>
      throw _privateConstructorUsedError;
  int? get defaultRecordingStrategyId => throw _privateConstructorUsedError;
  Map<String, dynamic> get settings => throw _privateConstructorUsedError;
  String? get createdAt => throw _privateConstructorUsedError;
  String? get updatedAt => throw _privateConstructorUsedError;
  String? get archivedAt => throw _privateConstructorUsedError;
  String? get createdBy => throw _privateConstructorUsedError;

  /// Serializes this TenantResponse to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of TenantResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $TenantResponseCopyWith<TenantResponse> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $TenantResponseCopyWith<$Res> {
  factory $TenantResponseCopyWith(
    TenantResponse value,
    $Res Function(TenantResponse) then,
  ) = _$TenantResponseCopyWithImpl<$Res, TenantResponse>;
  @useResult
  $Res call({
    String id,
    String displayName,
    String status,
    String? hostBaseUrl,
    List<String> hostBaseUrlAllowlist,
    List<String> inboundOriginAllowlist,
    bool requireHttps,
    String? defaultAuthenticatorBindingId,
    int? defaultRecordingStrategyId,
    Map<String, dynamic> settings,
    String? createdAt,
    String? updatedAt,
    String? archivedAt,
    String? createdBy,
  });
}

/// @nodoc
class _$TenantResponseCopyWithImpl<$Res, $Val extends TenantResponse>
    implements $TenantResponseCopyWith<$Res> {
  _$TenantResponseCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of TenantResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? displayName = null,
    Object? status = null,
    Object? hostBaseUrl = freezed,
    Object? hostBaseUrlAllowlist = null,
    Object? inboundOriginAllowlist = null,
    Object? requireHttps = null,
    Object? defaultAuthenticatorBindingId = freezed,
    Object? defaultRecordingStrategyId = freezed,
    Object? settings = null,
    Object? createdAt = freezed,
    Object? updatedAt = freezed,
    Object? archivedAt = freezed,
    Object? createdBy = freezed,
  }) {
    return _then(
      _value.copyWith(
            id: null == id
                ? _value.id
                : id // ignore: cast_nullable_to_non_nullable
                      as String,
            displayName: null == displayName
                ? _value.displayName
                : displayName // ignore: cast_nullable_to_non_nullable
                      as String,
            status: null == status
                ? _value.status
                : status // ignore: cast_nullable_to_non_nullable
                      as String,
            hostBaseUrl: freezed == hostBaseUrl
                ? _value.hostBaseUrl
                : hostBaseUrl // ignore: cast_nullable_to_non_nullable
                      as String?,
            hostBaseUrlAllowlist: null == hostBaseUrlAllowlist
                ? _value.hostBaseUrlAllowlist
                : hostBaseUrlAllowlist // ignore: cast_nullable_to_non_nullable
                      as List<String>,
            inboundOriginAllowlist: null == inboundOriginAllowlist
                ? _value.inboundOriginAllowlist
                : inboundOriginAllowlist // ignore: cast_nullable_to_non_nullable
                      as List<String>,
            requireHttps: null == requireHttps
                ? _value.requireHttps
                : requireHttps // ignore: cast_nullable_to_non_nullable
                      as bool,
            defaultAuthenticatorBindingId:
                freezed == defaultAuthenticatorBindingId
                ? _value.defaultAuthenticatorBindingId
                : defaultAuthenticatorBindingId // ignore: cast_nullable_to_non_nullable
                      as String?,
            defaultRecordingStrategyId: freezed == defaultRecordingStrategyId
                ? _value.defaultRecordingStrategyId
                : defaultRecordingStrategyId // ignore: cast_nullable_to_non_nullable
                      as int?,
            settings: null == settings
                ? _value.settings
                : settings // ignore: cast_nullable_to_non_nullable
                      as Map<String, dynamic>,
            createdAt: freezed == createdAt
                ? _value.createdAt
                : createdAt // ignore: cast_nullable_to_non_nullable
                      as String?,
            updatedAt: freezed == updatedAt
                ? _value.updatedAt
                : updatedAt // ignore: cast_nullable_to_non_nullable
                      as String?,
            archivedAt: freezed == archivedAt
                ? _value.archivedAt
                : archivedAt // ignore: cast_nullable_to_non_nullable
                      as String?,
            createdBy: freezed == createdBy
                ? _value.createdBy
                : createdBy // ignore: cast_nullable_to_non_nullable
                      as String?,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$TenantResponseImplCopyWith<$Res>
    implements $TenantResponseCopyWith<$Res> {
  factory _$$TenantResponseImplCopyWith(
    _$TenantResponseImpl value,
    $Res Function(_$TenantResponseImpl) then,
  ) = __$$TenantResponseImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String id,
    String displayName,
    String status,
    String? hostBaseUrl,
    List<String> hostBaseUrlAllowlist,
    List<String> inboundOriginAllowlist,
    bool requireHttps,
    String? defaultAuthenticatorBindingId,
    int? defaultRecordingStrategyId,
    Map<String, dynamic> settings,
    String? createdAt,
    String? updatedAt,
    String? archivedAt,
    String? createdBy,
  });
}

/// @nodoc
class __$$TenantResponseImplCopyWithImpl<$Res>
    extends _$TenantResponseCopyWithImpl<$Res, _$TenantResponseImpl>
    implements _$$TenantResponseImplCopyWith<$Res> {
  __$$TenantResponseImplCopyWithImpl(
    _$TenantResponseImpl _value,
    $Res Function(_$TenantResponseImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of TenantResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? displayName = null,
    Object? status = null,
    Object? hostBaseUrl = freezed,
    Object? hostBaseUrlAllowlist = null,
    Object? inboundOriginAllowlist = null,
    Object? requireHttps = null,
    Object? defaultAuthenticatorBindingId = freezed,
    Object? defaultRecordingStrategyId = freezed,
    Object? settings = null,
    Object? createdAt = freezed,
    Object? updatedAt = freezed,
    Object? archivedAt = freezed,
    Object? createdBy = freezed,
  }) {
    return _then(
      _$TenantResponseImpl(
        id: null == id
            ? _value.id
            : id // ignore: cast_nullable_to_non_nullable
                  as String,
        displayName: null == displayName
            ? _value.displayName
            : displayName // ignore: cast_nullable_to_non_nullable
                  as String,
        status: null == status
            ? _value.status
            : status // ignore: cast_nullable_to_non_nullable
                  as String,
        hostBaseUrl: freezed == hostBaseUrl
            ? _value.hostBaseUrl
            : hostBaseUrl // ignore: cast_nullable_to_non_nullable
                  as String?,
        hostBaseUrlAllowlist: null == hostBaseUrlAllowlist
            ? _value._hostBaseUrlAllowlist
            : hostBaseUrlAllowlist // ignore: cast_nullable_to_non_nullable
                  as List<String>,
        inboundOriginAllowlist: null == inboundOriginAllowlist
            ? _value._inboundOriginAllowlist
            : inboundOriginAllowlist // ignore: cast_nullable_to_non_nullable
                  as List<String>,
        requireHttps: null == requireHttps
            ? _value.requireHttps
            : requireHttps // ignore: cast_nullable_to_non_nullable
                  as bool,
        defaultAuthenticatorBindingId: freezed == defaultAuthenticatorBindingId
            ? _value.defaultAuthenticatorBindingId
            : defaultAuthenticatorBindingId // ignore: cast_nullable_to_non_nullable
                  as String?,
        defaultRecordingStrategyId: freezed == defaultRecordingStrategyId
            ? _value.defaultRecordingStrategyId
            : defaultRecordingStrategyId // ignore: cast_nullable_to_non_nullable
                  as int?,
        settings: null == settings
            ? _value._settings
            : settings // ignore: cast_nullable_to_non_nullable
                  as Map<String, dynamic>,
        createdAt: freezed == createdAt
            ? _value.createdAt
            : createdAt // ignore: cast_nullable_to_non_nullable
                  as String?,
        updatedAt: freezed == updatedAt
            ? _value.updatedAt
            : updatedAt // ignore: cast_nullable_to_non_nullable
                  as String?,
        archivedAt: freezed == archivedAt
            ? _value.archivedAt
            : archivedAt // ignore: cast_nullable_to_non_nullable
                  as String?,
        createdBy: freezed == createdBy
            ? _value.createdBy
            : createdBy // ignore: cast_nullable_to_non_nullable
                  as String?,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$TenantResponseImpl implements _TenantResponse {
  const _$TenantResponseImpl({
    required this.id,
    required this.displayName,
    required this.status,
    this.hostBaseUrl,
    final List<String> hostBaseUrlAllowlist = const <String>[],
    final List<String> inboundOriginAllowlist = const <String>[],
    this.requireHttps = true,
    this.defaultAuthenticatorBindingId,
    this.defaultRecordingStrategyId,
    final Map<String, dynamic> settings = const <String, dynamic>{},
    this.createdAt,
    this.updatedAt,
    this.archivedAt,
    this.createdBy,
  }) : _hostBaseUrlAllowlist = hostBaseUrlAllowlist,
       _inboundOriginAllowlist = inboundOriginAllowlist,
       _settings = settings;

  factory _$TenantResponseImpl.fromJson(Map<String, dynamic> json) =>
      _$$TenantResponseImplFromJson(json);

  @override
  final String id;
  @override
  final String displayName;
  @override
  final String status;
  // ACTIVE | SUSPENDED | ARCHIVED
  @override
  final String? hostBaseUrl;
  final List<String> _hostBaseUrlAllowlist;
  @override
  @JsonKey()
  List<String> get hostBaseUrlAllowlist {
    if (_hostBaseUrlAllowlist is EqualUnmodifiableListView)
      return _hostBaseUrlAllowlist;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_hostBaseUrlAllowlist);
  }

  final List<String> _inboundOriginAllowlist;
  @override
  @JsonKey()
  List<String> get inboundOriginAllowlist {
    if (_inboundOriginAllowlist is EqualUnmodifiableListView)
      return _inboundOriginAllowlist;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_inboundOriginAllowlist);
  }

  @override
  @JsonKey()
  final bool requireHttps;
  @override
  final String? defaultAuthenticatorBindingId;
  @override
  final int? defaultRecordingStrategyId;
  final Map<String, dynamic> _settings;
  @override
  @JsonKey()
  Map<String, dynamic> get settings {
    if (_settings is EqualUnmodifiableMapView) return _settings;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_settings);
  }

  @override
  final String? createdAt;
  @override
  final String? updatedAt;
  @override
  final String? archivedAt;
  @override
  final String? createdBy;

  @override
  String toString() {
    return 'TenantResponse(id: $id, displayName: $displayName, status: $status, hostBaseUrl: $hostBaseUrl, hostBaseUrlAllowlist: $hostBaseUrlAllowlist, inboundOriginAllowlist: $inboundOriginAllowlist, requireHttps: $requireHttps, defaultAuthenticatorBindingId: $defaultAuthenticatorBindingId, defaultRecordingStrategyId: $defaultRecordingStrategyId, settings: $settings, createdAt: $createdAt, updatedAt: $updatedAt, archivedAt: $archivedAt, createdBy: $createdBy)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$TenantResponseImpl &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.displayName, displayName) ||
                other.displayName == displayName) &&
            (identical(other.status, status) || other.status == status) &&
            (identical(other.hostBaseUrl, hostBaseUrl) ||
                other.hostBaseUrl == hostBaseUrl) &&
            const DeepCollectionEquality().equals(
              other._hostBaseUrlAllowlist,
              _hostBaseUrlAllowlist,
            ) &&
            const DeepCollectionEquality().equals(
              other._inboundOriginAllowlist,
              _inboundOriginAllowlist,
            ) &&
            (identical(other.requireHttps, requireHttps) ||
                other.requireHttps == requireHttps) &&
            (identical(
                  other.defaultAuthenticatorBindingId,
                  defaultAuthenticatorBindingId,
                ) ||
                other.defaultAuthenticatorBindingId ==
                    defaultAuthenticatorBindingId) &&
            (identical(
                  other.defaultRecordingStrategyId,
                  defaultRecordingStrategyId,
                ) ||
                other.defaultRecordingStrategyId ==
                    defaultRecordingStrategyId) &&
            const DeepCollectionEquality().equals(other._settings, _settings) &&
            (identical(other.createdAt, createdAt) ||
                other.createdAt == createdAt) &&
            (identical(other.updatedAt, updatedAt) ||
                other.updatedAt == updatedAt) &&
            (identical(other.archivedAt, archivedAt) ||
                other.archivedAt == archivedAt) &&
            (identical(other.createdBy, createdBy) ||
                other.createdBy == createdBy));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    id,
    displayName,
    status,
    hostBaseUrl,
    const DeepCollectionEquality().hash(_hostBaseUrlAllowlist),
    const DeepCollectionEquality().hash(_inboundOriginAllowlist),
    requireHttps,
    defaultAuthenticatorBindingId,
    defaultRecordingStrategyId,
    const DeepCollectionEquality().hash(_settings),
    createdAt,
    updatedAt,
    archivedAt,
    createdBy,
  );

  /// Create a copy of TenantResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$TenantResponseImplCopyWith<_$TenantResponseImpl> get copyWith =>
      __$$TenantResponseImplCopyWithImpl<_$TenantResponseImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$TenantResponseImplToJson(this);
  }
}

abstract class _TenantResponse implements TenantResponse {
  const factory _TenantResponse({
    required final String id,
    required final String displayName,
    required final String status,
    final String? hostBaseUrl,
    final List<String> hostBaseUrlAllowlist,
    final List<String> inboundOriginAllowlist,
    final bool requireHttps,
    final String? defaultAuthenticatorBindingId,
    final int? defaultRecordingStrategyId,
    final Map<String, dynamic> settings,
    final String? createdAt,
    final String? updatedAt,
    final String? archivedAt,
    final String? createdBy,
  }) = _$TenantResponseImpl;

  factory _TenantResponse.fromJson(Map<String, dynamic> json) =
      _$TenantResponseImpl.fromJson;

  @override
  String get id;
  @override
  String get displayName;
  @override
  String get status; // ACTIVE | SUSPENDED | ARCHIVED
  @override
  String? get hostBaseUrl;
  @override
  List<String> get hostBaseUrlAllowlist;
  @override
  List<String> get inboundOriginAllowlist;
  @override
  bool get requireHttps;
  @override
  String? get defaultAuthenticatorBindingId;
  @override
  int? get defaultRecordingStrategyId;
  @override
  Map<String, dynamic> get settings;
  @override
  String? get createdAt;
  @override
  String? get updatedAt;
  @override
  String? get archivedAt;
  @override
  String? get createdBy;

  /// Create a copy of TenantResponse
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$TenantResponseImplCopyWith<_$TenantResponseImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

TenantHealthResponse _$TenantHealthResponseFromJson(Map<String, dynamic> json) {
  return _TenantHealthResponse.fromJson(json);
}

/// @nodoc
mixin _$TenantHealthResponse {
  String get tenantId => throw _privateConstructorUsedError;
  String get status => throw _privateConstructorUsedError;
  bool get hostBaseUrlSet => throw _privateConstructorUsedError;
  bool get authBindingResolves => throw _privateConstructorUsedError;
  bool get recordingStrategyResolves => throw _privateConstructorUsedError;
  int get dependencyCount => throw _privateConstructorUsedError;
  List<String> get warnings => throw _privateConstructorUsedError;

  /// Serializes this TenantHealthResponse to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of TenantHealthResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $TenantHealthResponseCopyWith<TenantHealthResponse> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $TenantHealthResponseCopyWith<$Res> {
  factory $TenantHealthResponseCopyWith(
    TenantHealthResponse value,
    $Res Function(TenantHealthResponse) then,
  ) = _$TenantHealthResponseCopyWithImpl<$Res, TenantHealthResponse>;
  @useResult
  $Res call({
    String tenantId,
    String status,
    bool hostBaseUrlSet,
    bool authBindingResolves,
    bool recordingStrategyResolves,
    int dependencyCount,
    List<String> warnings,
  });
}

/// @nodoc
class _$TenantHealthResponseCopyWithImpl<
  $Res,
  $Val extends TenantHealthResponse
>
    implements $TenantHealthResponseCopyWith<$Res> {
  _$TenantHealthResponseCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of TenantHealthResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenantId = null,
    Object? status = null,
    Object? hostBaseUrlSet = null,
    Object? authBindingResolves = null,
    Object? recordingStrategyResolves = null,
    Object? dependencyCount = null,
    Object? warnings = null,
  }) {
    return _then(
      _value.copyWith(
            tenantId: null == tenantId
                ? _value.tenantId
                : tenantId // ignore: cast_nullable_to_non_nullable
                      as String,
            status: null == status
                ? _value.status
                : status // ignore: cast_nullable_to_non_nullable
                      as String,
            hostBaseUrlSet: null == hostBaseUrlSet
                ? _value.hostBaseUrlSet
                : hostBaseUrlSet // ignore: cast_nullable_to_non_nullable
                      as bool,
            authBindingResolves: null == authBindingResolves
                ? _value.authBindingResolves
                : authBindingResolves // ignore: cast_nullable_to_non_nullable
                      as bool,
            recordingStrategyResolves: null == recordingStrategyResolves
                ? _value.recordingStrategyResolves
                : recordingStrategyResolves // ignore: cast_nullable_to_non_nullable
                      as bool,
            dependencyCount: null == dependencyCount
                ? _value.dependencyCount
                : dependencyCount // ignore: cast_nullable_to_non_nullable
                      as int,
            warnings: null == warnings
                ? _value.warnings
                : warnings // ignore: cast_nullable_to_non_nullable
                      as List<String>,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$TenantHealthResponseImplCopyWith<$Res>
    implements $TenantHealthResponseCopyWith<$Res> {
  factory _$$TenantHealthResponseImplCopyWith(
    _$TenantHealthResponseImpl value,
    $Res Function(_$TenantHealthResponseImpl) then,
  ) = __$$TenantHealthResponseImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String tenantId,
    String status,
    bool hostBaseUrlSet,
    bool authBindingResolves,
    bool recordingStrategyResolves,
    int dependencyCount,
    List<String> warnings,
  });
}

/// @nodoc
class __$$TenantHealthResponseImplCopyWithImpl<$Res>
    extends _$TenantHealthResponseCopyWithImpl<$Res, _$TenantHealthResponseImpl>
    implements _$$TenantHealthResponseImplCopyWith<$Res> {
  __$$TenantHealthResponseImplCopyWithImpl(
    _$TenantHealthResponseImpl _value,
    $Res Function(_$TenantHealthResponseImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of TenantHealthResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenantId = null,
    Object? status = null,
    Object? hostBaseUrlSet = null,
    Object? authBindingResolves = null,
    Object? recordingStrategyResolves = null,
    Object? dependencyCount = null,
    Object? warnings = null,
  }) {
    return _then(
      _$TenantHealthResponseImpl(
        tenantId: null == tenantId
            ? _value.tenantId
            : tenantId // ignore: cast_nullable_to_non_nullable
                  as String,
        status: null == status
            ? _value.status
            : status // ignore: cast_nullable_to_non_nullable
                  as String,
        hostBaseUrlSet: null == hostBaseUrlSet
            ? _value.hostBaseUrlSet
            : hostBaseUrlSet // ignore: cast_nullable_to_non_nullable
                  as bool,
        authBindingResolves: null == authBindingResolves
            ? _value.authBindingResolves
            : authBindingResolves // ignore: cast_nullable_to_non_nullable
                  as bool,
        recordingStrategyResolves: null == recordingStrategyResolves
            ? _value.recordingStrategyResolves
            : recordingStrategyResolves // ignore: cast_nullable_to_non_nullable
                  as bool,
        dependencyCount: null == dependencyCount
            ? _value.dependencyCount
            : dependencyCount // ignore: cast_nullable_to_non_nullable
                  as int,
        warnings: null == warnings
            ? _value._warnings
            : warnings // ignore: cast_nullable_to_non_nullable
                  as List<String>,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$TenantHealthResponseImpl implements _TenantHealthResponse {
  const _$TenantHealthResponseImpl({
    required this.tenantId,
    required this.status,
    required this.hostBaseUrlSet,
    required this.authBindingResolves,
    required this.recordingStrategyResolves,
    required this.dependencyCount,
    final List<String> warnings = const <String>[],
  }) : _warnings = warnings;

  factory _$TenantHealthResponseImpl.fromJson(Map<String, dynamic> json) =>
      _$$TenantHealthResponseImplFromJson(json);

  @override
  final String tenantId;
  @override
  final String status;
  @override
  final bool hostBaseUrlSet;
  @override
  final bool authBindingResolves;
  @override
  final bool recordingStrategyResolves;
  @override
  final int dependencyCount;
  final List<String> _warnings;
  @override
  @JsonKey()
  List<String> get warnings {
    if (_warnings is EqualUnmodifiableListView) return _warnings;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_warnings);
  }

  @override
  String toString() {
    return 'TenantHealthResponse(tenantId: $tenantId, status: $status, hostBaseUrlSet: $hostBaseUrlSet, authBindingResolves: $authBindingResolves, recordingStrategyResolves: $recordingStrategyResolves, dependencyCount: $dependencyCount, warnings: $warnings)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$TenantHealthResponseImpl &&
            (identical(other.tenantId, tenantId) ||
                other.tenantId == tenantId) &&
            (identical(other.status, status) || other.status == status) &&
            (identical(other.hostBaseUrlSet, hostBaseUrlSet) ||
                other.hostBaseUrlSet == hostBaseUrlSet) &&
            (identical(other.authBindingResolves, authBindingResolves) ||
                other.authBindingResolves == authBindingResolves) &&
            (identical(
                  other.recordingStrategyResolves,
                  recordingStrategyResolves,
                ) ||
                other.recordingStrategyResolves == recordingStrategyResolves) &&
            (identical(other.dependencyCount, dependencyCount) ||
                other.dependencyCount == dependencyCount) &&
            const DeepCollectionEquality().equals(other._warnings, _warnings));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    tenantId,
    status,
    hostBaseUrlSet,
    authBindingResolves,
    recordingStrategyResolves,
    dependencyCount,
    const DeepCollectionEquality().hash(_warnings),
  );

  /// Create a copy of TenantHealthResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$TenantHealthResponseImplCopyWith<_$TenantHealthResponseImpl>
  get copyWith =>
      __$$TenantHealthResponseImplCopyWithImpl<_$TenantHealthResponseImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$TenantHealthResponseImplToJson(this);
  }
}

abstract class _TenantHealthResponse implements TenantHealthResponse {
  const factory _TenantHealthResponse({
    required final String tenantId,
    required final String status,
    required final bool hostBaseUrlSet,
    required final bool authBindingResolves,
    required final bool recordingStrategyResolves,
    required final int dependencyCount,
    final List<String> warnings,
  }) = _$TenantHealthResponseImpl;

  factory _TenantHealthResponse.fromJson(Map<String, dynamic> json) =
      _$TenantHealthResponseImpl.fromJson;

  @override
  String get tenantId;
  @override
  String get status;
  @override
  bool get hostBaseUrlSet;
  @override
  bool get authBindingResolves;
  @override
  bool get recordingStrategyResolves;
  @override
  int get dependencyCount;
  @override
  List<String> get warnings;

  /// Create a copy of TenantHealthResponse
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$TenantHealthResponseImplCopyWith<_$TenantHealthResponseImpl>
  get copyWith => throw _privateConstructorUsedError;
}

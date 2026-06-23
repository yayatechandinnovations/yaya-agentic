// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'workspace.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

Workspace _$WorkspaceFromJson(Map<String, dynamic> json) {
  return _Workspace.fromJson(json);
}

/// @nodoc
mixin _$Workspace {
  String get id => throw _privateConstructorUsedError;
  String get code => throw _privateConstructorUsedError;
  String get displayName => throw _privateConstructorUsedError;
  String? get status => throw _privateConstructorUsedError;
  String? get hostBaseUrl => throw _privateConstructorUsedError;
  List<String> get hostBaseUrlAllowlist => throw _privateConstructorUsedError;
  String? get archivedAt => throw _privateConstructorUsedError;
  String? get createdAt => throw _privateConstructorUsedError;

  /// Serializes this Workspace to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of Workspace
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $WorkspaceCopyWith<Workspace> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $WorkspaceCopyWith<$Res> {
  factory $WorkspaceCopyWith(Workspace value, $Res Function(Workspace) then) =
      _$WorkspaceCopyWithImpl<$Res, Workspace>;
  @useResult
  $Res call({
    String id,
    String code,
    String displayName,
    String? status,
    String? hostBaseUrl,
    List<String> hostBaseUrlAllowlist,
    String? archivedAt,
    String? createdAt,
  });
}

/// @nodoc
class _$WorkspaceCopyWithImpl<$Res, $Val extends Workspace>
    implements $WorkspaceCopyWith<$Res> {
  _$WorkspaceCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of Workspace
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? code = null,
    Object? displayName = null,
    Object? status = freezed,
    Object? hostBaseUrl = freezed,
    Object? hostBaseUrlAllowlist = null,
    Object? archivedAt = freezed,
    Object? createdAt = freezed,
  }) {
    return _then(
      _value.copyWith(
            id: null == id
                ? _value.id
                : id // ignore: cast_nullable_to_non_nullable
                      as String,
            code: null == code
                ? _value.code
                : code // ignore: cast_nullable_to_non_nullable
                      as String,
            displayName: null == displayName
                ? _value.displayName
                : displayName // ignore: cast_nullable_to_non_nullable
                      as String,
            status: freezed == status
                ? _value.status
                : status // ignore: cast_nullable_to_non_nullable
                      as String?,
            hostBaseUrl: freezed == hostBaseUrl
                ? _value.hostBaseUrl
                : hostBaseUrl // ignore: cast_nullable_to_non_nullable
                      as String?,
            hostBaseUrlAllowlist: null == hostBaseUrlAllowlist
                ? _value.hostBaseUrlAllowlist
                : hostBaseUrlAllowlist // ignore: cast_nullable_to_non_nullable
                      as List<String>,
            archivedAt: freezed == archivedAt
                ? _value.archivedAt
                : archivedAt // ignore: cast_nullable_to_non_nullable
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
abstract class _$$WorkspaceImplCopyWith<$Res>
    implements $WorkspaceCopyWith<$Res> {
  factory _$$WorkspaceImplCopyWith(
    _$WorkspaceImpl value,
    $Res Function(_$WorkspaceImpl) then,
  ) = __$$WorkspaceImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String id,
    String code,
    String displayName,
    String? status,
    String? hostBaseUrl,
    List<String> hostBaseUrlAllowlist,
    String? archivedAt,
    String? createdAt,
  });
}

/// @nodoc
class __$$WorkspaceImplCopyWithImpl<$Res>
    extends _$WorkspaceCopyWithImpl<$Res, _$WorkspaceImpl>
    implements _$$WorkspaceImplCopyWith<$Res> {
  __$$WorkspaceImplCopyWithImpl(
    _$WorkspaceImpl _value,
    $Res Function(_$WorkspaceImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of Workspace
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? code = null,
    Object? displayName = null,
    Object? status = freezed,
    Object? hostBaseUrl = freezed,
    Object? hostBaseUrlAllowlist = null,
    Object? archivedAt = freezed,
    Object? createdAt = freezed,
  }) {
    return _then(
      _$WorkspaceImpl(
        id: null == id
            ? _value.id
            : id // ignore: cast_nullable_to_non_nullable
                  as String,
        code: null == code
            ? _value.code
            : code // ignore: cast_nullable_to_non_nullable
                  as String,
        displayName: null == displayName
            ? _value.displayName
            : displayName // ignore: cast_nullable_to_non_nullable
                  as String,
        status: freezed == status
            ? _value.status
            : status // ignore: cast_nullable_to_non_nullable
                  as String?,
        hostBaseUrl: freezed == hostBaseUrl
            ? _value.hostBaseUrl
            : hostBaseUrl // ignore: cast_nullable_to_non_nullable
                  as String?,
        hostBaseUrlAllowlist: null == hostBaseUrlAllowlist
            ? _value._hostBaseUrlAllowlist
            : hostBaseUrlAllowlist // ignore: cast_nullable_to_non_nullable
                  as List<String>,
        archivedAt: freezed == archivedAt
            ? _value.archivedAt
            : archivedAt // ignore: cast_nullable_to_non_nullable
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
class _$WorkspaceImpl implements _Workspace {
  const _$WorkspaceImpl({
    required this.id,
    required this.code,
    required this.displayName,
    this.status,
    this.hostBaseUrl,
    final List<String> hostBaseUrlAllowlist = const <String>[],
    this.archivedAt,
    this.createdAt,
  }) : _hostBaseUrlAllowlist = hostBaseUrlAllowlist;

  factory _$WorkspaceImpl.fromJson(Map<String, dynamic> json) =>
      _$$WorkspaceImplFromJson(json);

  @override
  final String id;
  @override
  final String code;
  @override
  final String displayName;
  @override
  final String? status;
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

  @override
  final String? archivedAt;
  @override
  final String? createdAt;

  @override
  String toString() {
    return 'Workspace(id: $id, code: $code, displayName: $displayName, status: $status, hostBaseUrl: $hostBaseUrl, hostBaseUrlAllowlist: $hostBaseUrlAllowlist, archivedAt: $archivedAt, createdAt: $createdAt)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$WorkspaceImpl &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.code, code) || other.code == code) &&
            (identical(other.displayName, displayName) ||
                other.displayName == displayName) &&
            (identical(other.status, status) || other.status == status) &&
            (identical(other.hostBaseUrl, hostBaseUrl) ||
                other.hostBaseUrl == hostBaseUrl) &&
            const DeepCollectionEquality().equals(
              other._hostBaseUrlAllowlist,
              _hostBaseUrlAllowlist,
            ) &&
            (identical(other.archivedAt, archivedAt) ||
                other.archivedAt == archivedAt) &&
            (identical(other.createdAt, createdAt) ||
                other.createdAt == createdAt));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    id,
    code,
    displayName,
    status,
    hostBaseUrl,
    const DeepCollectionEquality().hash(_hostBaseUrlAllowlist),
    archivedAt,
    createdAt,
  );

  /// Create a copy of Workspace
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$WorkspaceImplCopyWith<_$WorkspaceImpl> get copyWith =>
      __$$WorkspaceImplCopyWithImpl<_$WorkspaceImpl>(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$WorkspaceImplToJson(this);
  }
}

abstract class _Workspace implements Workspace {
  const factory _Workspace({
    required final String id,
    required final String code,
    required final String displayName,
    final String? status,
    final String? hostBaseUrl,
    final List<String> hostBaseUrlAllowlist,
    final String? archivedAt,
    final String? createdAt,
  }) = _$WorkspaceImpl;

  factory _Workspace.fromJson(Map<String, dynamic> json) =
      _$WorkspaceImpl.fromJson;

  @override
  String get id;
  @override
  String get code;
  @override
  String get displayName;
  @override
  String? get status;
  @override
  String? get hostBaseUrl;
  @override
  List<String> get hostBaseUrlAllowlist;
  @override
  String? get archivedAt;
  @override
  String? get createdAt;

  /// Create a copy of Workspace
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$WorkspaceImplCopyWith<_$WorkspaceImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

WorkspaceListItem _$WorkspaceListItemFromJson(Map<String, dynamic> json) {
  return _WorkspaceListItem.fromJson(json);
}

/// @nodoc
mixin _$WorkspaceListItem {
  Workspace get workspace => throw _privateConstructorUsedError;
  String get role => throw _privateConstructorUsedError;

  /// Serializes this WorkspaceListItem to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of WorkspaceListItem
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $WorkspaceListItemCopyWith<WorkspaceListItem> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $WorkspaceListItemCopyWith<$Res> {
  factory $WorkspaceListItemCopyWith(
    WorkspaceListItem value,
    $Res Function(WorkspaceListItem) then,
  ) = _$WorkspaceListItemCopyWithImpl<$Res, WorkspaceListItem>;
  @useResult
  $Res call({Workspace workspace, String role});

  $WorkspaceCopyWith<$Res> get workspace;
}

/// @nodoc
class _$WorkspaceListItemCopyWithImpl<$Res, $Val extends WorkspaceListItem>
    implements $WorkspaceListItemCopyWith<$Res> {
  _$WorkspaceListItemCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of WorkspaceListItem
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? workspace = null, Object? role = null}) {
    return _then(
      _value.copyWith(
            workspace: null == workspace
                ? _value.workspace
                : workspace // ignore: cast_nullable_to_non_nullable
                      as Workspace,
            role: null == role
                ? _value.role
                : role // ignore: cast_nullable_to_non_nullable
                      as String,
          )
          as $Val,
    );
  }

  /// Create a copy of WorkspaceListItem
  /// with the given fields replaced by the non-null parameter values.
  @override
  @pragma('vm:prefer-inline')
  $WorkspaceCopyWith<$Res> get workspace {
    return $WorkspaceCopyWith<$Res>(_value.workspace, (value) {
      return _then(_value.copyWith(workspace: value) as $Val);
    });
  }
}

/// @nodoc
abstract class _$$WorkspaceListItemImplCopyWith<$Res>
    implements $WorkspaceListItemCopyWith<$Res> {
  factory _$$WorkspaceListItemImplCopyWith(
    _$WorkspaceListItemImpl value,
    $Res Function(_$WorkspaceListItemImpl) then,
  ) = __$$WorkspaceListItemImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({Workspace workspace, String role});

  @override
  $WorkspaceCopyWith<$Res> get workspace;
}

/// @nodoc
class __$$WorkspaceListItemImplCopyWithImpl<$Res>
    extends _$WorkspaceListItemCopyWithImpl<$Res, _$WorkspaceListItemImpl>
    implements _$$WorkspaceListItemImplCopyWith<$Res> {
  __$$WorkspaceListItemImplCopyWithImpl(
    _$WorkspaceListItemImpl _value,
    $Res Function(_$WorkspaceListItemImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of WorkspaceListItem
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? workspace = null, Object? role = null}) {
    return _then(
      _$WorkspaceListItemImpl(
        workspace: null == workspace
            ? _value.workspace
            : workspace // ignore: cast_nullable_to_non_nullable
                  as Workspace,
        role: null == role
            ? _value.role
            : role // ignore: cast_nullable_to_non_nullable
                  as String,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$WorkspaceListItemImpl implements _WorkspaceListItem {
  const _$WorkspaceListItemImpl({required this.workspace, required this.role});

  factory _$WorkspaceListItemImpl.fromJson(Map<String, dynamic> json) =>
      _$$WorkspaceListItemImplFromJson(json);

  @override
  final Workspace workspace;
  @override
  final String role;

  @override
  String toString() {
    return 'WorkspaceListItem(workspace: $workspace, role: $role)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$WorkspaceListItemImpl &&
            (identical(other.workspace, workspace) ||
                other.workspace == workspace) &&
            (identical(other.role, role) || other.role == role));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(runtimeType, workspace, role);

  /// Create a copy of WorkspaceListItem
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$WorkspaceListItemImplCopyWith<_$WorkspaceListItemImpl> get copyWith =>
      __$$WorkspaceListItemImplCopyWithImpl<_$WorkspaceListItemImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$WorkspaceListItemImplToJson(this);
  }
}

abstract class _WorkspaceListItem implements WorkspaceListItem {
  const factory _WorkspaceListItem({
    required final Workspace workspace,
    required final String role,
  }) = _$WorkspaceListItemImpl;

  factory _WorkspaceListItem.fromJson(Map<String, dynamic> json) =
      _$WorkspaceListItemImpl.fromJson;

  @override
  Workspace get workspace;
  @override
  String get role;

  /// Create a copy of WorkspaceListItem
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$WorkspaceListItemImplCopyWith<_$WorkspaceListItemImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

WorkspaceCreateRequest _$WorkspaceCreateRequestFromJson(
  Map<String, dynamic> json,
) {
  return _WorkspaceCreateRequest.fromJson(json);
}

/// @nodoc
mixin _$WorkspaceCreateRequest {
  String get displayName => throw _privateConstructorUsedError;

  /// Serializes this WorkspaceCreateRequest to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of WorkspaceCreateRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $WorkspaceCreateRequestCopyWith<WorkspaceCreateRequest> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $WorkspaceCreateRequestCopyWith<$Res> {
  factory $WorkspaceCreateRequestCopyWith(
    WorkspaceCreateRequest value,
    $Res Function(WorkspaceCreateRequest) then,
  ) = _$WorkspaceCreateRequestCopyWithImpl<$Res, WorkspaceCreateRequest>;
  @useResult
  $Res call({String displayName});
}

/// @nodoc
class _$WorkspaceCreateRequestCopyWithImpl<
  $Res,
  $Val extends WorkspaceCreateRequest
>
    implements $WorkspaceCreateRequestCopyWith<$Res> {
  _$WorkspaceCreateRequestCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of WorkspaceCreateRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? displayName = null}) {
    return _then(
      _value.copyWith(
            displayName: null == displayName
                ? _value.displayName
                : displayName // ignore: cast_nullable_to_non_nullable
                      as String,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$WorkspaceCreateRequestImplCopyWith<$Res>
    implements $WorkspaceCreateRequestCopyWith<$Res> {
  factory _$$WorkspaceCreateRequestImplCopyWith(
    _$WorkspaceCreateRequestImpl value,
    $Res Function(_$WorkspaceCreateRequestImpl) then,
  ) = __$$WorkspaceCreateRequestImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({String displayName});
}

/// @nodoc
class __$$WorkspaceCreateRequestImplCopyWithImpl<$Res>
    extends
        _$WorkspaceCreateRequestCopyWithImpl<$Res, _$WorkspaceCreateRequestImpl>
    implements _$$WorkspaceCreateRequestImplCopyWith<$Res> {
  __$$WorkspaceCreateRequestImplCopyWithImpl(
    _$WorkspaceCreateRequestImpl _value,
    $Res Function(_$WorkspaceCreateRequestImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of WorkspaceCreateRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? displayName = null}) {
    return _then(
      _$WorkspaceCreateRequestImpl(
        displayName: null == displayName
            ? _value.displayName
            : displayName // ignore: cast_nullable_to_non_nullable
                  as String,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$WorkspaceCreateRequestImpl implements _WorkspaceCreateRequest {
  const _$WorkspaceCreateRequestImpl({required this.displayName});

  factory _$WorkspaceCreateRequestImpl.fromJson(Map<String, dynamic> json) =>
      _$$WorkspaceCreateRequestImplFromJson(json);

  @override
  final String displayName;

  @override
  String toString() {
    return 'WorkspaceCreateRequest(displayName: $displayName)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$WorkspaceCreateRequestImpl &&
            (identical(other.displayName, displayName) ||
                other.displayName == displayName));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(runtimeType, displayName);

  /// Create a copy of WorkspaceCreateRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$WorkspaceCreateRequestImplCopyWith<_$WorkspaceCreateRequestImpl>
  get copyWith =>
      __$$WorkspaceCreateRequestImplCopyWithImpl<_$WorkspaceCreateRequestImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$WorkspaceCreateRequestImplToJson(this);
  }
}

abstract class _WorkspaceCreateRequest implements WorkspaceCreateRequest {
  const factory _WorkspaceCreateRequest({required final String displayName}) =
      _$WorkspaceCreateRequestImpl;

  factory _WorkspaceCreateRequest.fromJson(Map<String, dynamic> json) =
      _$WorkspaceCreateRequestImpl.fromJson;

  @override
  String get displayName;

  /// Create a copy of WorkspaceCreateRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$WorkspaceCreateRequestImplCopyWith<_$WorkspaceCreateRequestImpl>
  get copyWith => throw _privateConstructorUsedError;
}

WorkspacePatchRequest _$WorkspacePatchRequestFromJson(
  Map<String, dynamic> json,
) {
  return _WorkspacePatchRequest.fromJson(json);
}

/// @nodoc
mixin _$WorkspacePatchRequest {
  String? get displayName => throw _privateConstructorUsedError;
  String? get hostBaseUrl => throw _privateConstructorUsedError;
  List<String>? get hostBaseUrlAllowlist => throw _privateConstructorUsedError;

  /// Serializes this WorkspacePatchRequest to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of WorkspacePatchRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $WorkspacePatchRequestCopyWith<WorkspacePatchRequest> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $WorkspacePatchRequestCopyWith<$Res> {
  factory $WorkspacePatchRequestCopyWith(
    WorkspacePatchRequest value,
    $Res Function(WorkspacePatchRequest) then,
  ) = _$WorkspacePatchRequestCopyWithImpl<$Res, WorkspacePatchRequest>;
  @useResult
  $Res call({
    String? displayName,
    String? hostBaseUrl,
    List<String>? hostBaseUrlAllowlist,
  });
}

/// @nodoc
class _$WorkspacePatchRequestCopyWithImpl<
  $Res,
  $Val extends WorkspacePatchRequest
>
    implements $WorkspacePatchRequestCopyWith<$Res> {
  _$WorkspacePatchRequestCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of WorkspacePatchRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? displayName = freezed,
    Object? hostBaseUrl = freezed,
    Object? hostBaseUrlAllowlist = freezed,
  }) {
    return _then(
      _value.copyWith(
            displayName: freezed == displayName
                ? _value.displayName
                : displayName // ignore: cast_nullable_to_non_nullable
                      as String?,
            hostBaseUrl: freezed == hostBaseUrl
                ? _value.hostBaseUrl
                : hostBaseUrl // ignore: cast_nullable_to_non_nullable
                      as String?,
            hostBaseUrlAllowlist: freezed == hostBaseUrlAllowlist
                ? _value.hostBaseUrlAllowlist
                : hostBaseUrlAllowlist // ignore: cast_nullable_to_non_nullable
                      as List<String>?,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$WorkspacePatchRequestImplCopyWith<$Res>
    implements $WorkspacePatchRequestCopyWith<$Res> {
  factory _$$WorkspacePatchRequestImplCopyWith(
    _$WorkspacePatchRequestImpl value,
    $Res Function(_$WorkspacePatchRequestImpl) then,
  ) = __$$WorkspacePatchRequestImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String? displayName,
    String? hostBaseUrl,
    List<String>? hostBaseUrlAllowlist,
  });
}

/// @nodoc
class __$$WorkspacePatchRequestImplCopyWithImpl<$Res>
    extends
        _$WorkspacePatchRequestCopyWithImpl<$Res, _$WorkspacePatchRequestImpl>
    implements _$$WorkspacePatchRequestImplCopyWith<$Res> {
  __$$WorkspacePatchRequestImplCopyWithImpl(
    _$WorkspacePatchRequestImpl _value,
    $Res Function(_$WorkspacePatchRequestImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of WorkspacePatchRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? displayName = freezed,
    Object? hostBaseUrl = freezed,
    Object? hostBaseUrlAllowlist = freezed,
  }) {
    return _then(
      _$WorkspacePatchRequestImpl(
        displayName: freezed == displayName
            ? _value.displayName
            : displayName // ignore: cast_nullable_to_non_nullable
                  as String?,
        hostBaseUrl: freezed == hostBaseUrl
            ? _value.hostBaseUrl
            : hostBaseUrl // ignore: cast_nullable_to_non_nullable
                  as String?,
        hostBaseUrlAllowlist: freezed == hostBaseUrlAllowlist
            ? _value._hostBaseUrlAllowlist
            : hostBaseUrlAllowlist // ignore: cast_nullable_to_non_nullable
                  as List<String>?,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$WorkspacePatchRequestImpl implements _WorkspacePatchRequest {
  const _$WorkspacePatchRequestImpl({
    this.displayName,
    this.hostBaseUrl,
    final List<String>? hostBaseUrlAllowlist,
  }) : _hostBaseUrlAllowlist = hostBaseUrlAllowlist;

  factory _$WorkspacePatchRequestImpl.fromJson(Map<String, dynamic> json) =>
      _$$WorkspacePatchRequestImplFromJson(json);

  @override
  final String? displayName;
  @override
  final String? hostBaseUrl;
  final List<String>? _hostBaseUrlAllowlist;
  @override
  List<String>? get hostBaseUrlAllowlist {
    final value = _hostBaseUrlAllowlist;
    if (value == null) return null;
    if (_hostBaseUrlAllowlist is EqualUnmodifiableListView)
      return _hostBaseUrlAllowlist;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(value);
  }

  @override
  String toString() {
    return 'WorkspacePatchRequest(displayName: $displayName, hostBaseUrl: $hostBaseUrl, hostBaseUrlAllowlist: $hostBaseUrlAllowlist)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$WorkspacePatchRequestImpl &&
            (identical(other.displayName, displayName) ||
                other.displayName == displayName) &&
            (identical(other.hostBaseUrl, hostBaseUrl) ||
                other.hostBaseUrl == hostBaseUrl) &&
            const DeepCollectionEquality().equals(
              other._hostBaseUrlAllowlist,
              _hostBaseUrlAllowlist,
            ));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    displayName,
    hostBaseUrl,
    const DeepCollectionEquality().hash(_hostBaseUrlAllowlist),
  );

  /// Create a copy of WorkspacePatchRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$WorkspacePatchRequestImplCopyWith<_$WorkspacePatchRequestImpl>
  get copyWith =>
      __$$WorkspacePatchRequestImplCopyWithImpl<_$WorkspacePatchRequestImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$WorkspacePatchRequestImplToJson(this);
  }
}

abstract class _WorkspacePatchRequest implements WorkspacePatchRequest {
  const factory _WorkspacePatchRequest({
    final String? displayName,
    final String? hostBaseUrl,
    final List<String>? hostBaseUrlAllowlist,
  }) = _$WorkspacePatchRequestImpl;

  factory _WorkspacePatchRequest.fromJson(Map<String, dynamic> json) =
      _$WorkspacePatchRequestImpl.fromJson;

  @override
  String? get displayName;
  @override
  String? get hostBaseUrl;
  @override
  List<String>? get hostBaseUrlAllowlist;

  /// Create a copy of WorkspacePatchRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$WorkspacePatchRequestImplCopyWith<_$WorkspacePatchRequestImpl>
  get copyWith => throw _privateConstructorUsedError;
}

WorkspaceArchiveRequest _$WorkspaceArchiveRequestFromJson(
  Map<String, dynamic> json,
) {
  return _WorkspaceArchiveRequest.fromJson(json);
}

/// @nodoc
mixin _$WorkspaceArchiveRequest {
  String get confirmCode => throw _privateConstructorUsedError;

  /// Serializes this WorkspaceArchiveRequest to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of WorkspaceArchiveRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $WorkspaceArchiveRequestCopyWith<WorkspaceArchiveRequest> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $WorkspaceArchiveRequestCopyWith<$Res> {
  factory $WorkspaceArchiveRequestCopyWith(
    WorkspaceArchiveRequest value,
    $Res Function(WorkspaceArchiveRequest) then,
  ) = _$WorkspaceArchiveRequestCopyWithImpl<$Res, WorkspaceArchiveRequest>;
  @useResult
  $Res call({String confirmCode});
}

/// @nodoc
class _$WorkspaceArchiveRequestCopyWithImpl<
  $Res,
  $Val extends WorkspaceArchiveRequest
>
    implements $WorkspaceArchiveRequestCopyWith<$Res> {
  _$WorkspaceArchiveRequestCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of WorkspaceArchiveRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? confirmCode = null}) {
    return _then(
      _value.copyWith(
            confirmCode: null == confirmCode
                ? _value.confirmCode
                : confirmCode // ignore: cast_nullable_to_non_nullable
                      as String,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$WorkspaceArchiveRequestImplCopyWith<$Res>
    implements $WorkspaceArchiveRequestCopyWith<$Res> {
  factory _$$WorkspaceArchiveRequestImplCopyWith(
    _$WorkspaceArchiveRequestImpl value,
    $Res Function(_$WorkspaceArchiveRequestImpl) then,
  ) = __$$WorkspaceArchiveRequestImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({String confirmCode});
}

/// @nodoc
class __$$WorkspaceArchiveRequestImplCopyWithImpl<$Res>
    extends
        _$WorkspaceArchiveRequestCopyWithImpl<
          $Res,
          _$WorkspaceArchiveRequestImpl
        >
    implements _$$WorkspaceArchiveRequestImplCopyWith<$Res> {
  __$$WorkspaceArchiveRequestImplCopyWithImpl(
    _$WorkspaceArchiveRequestImpl _value,
    $Res Function(_$WorkspaceArchiveRequestImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of WorkspaceArchiveRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? confirmCode = null}) {
    return _then(
      _$WorkspaceArchiveRequestImpl(
        confirmCode: null == confirmCode
            ? _value.confirmCode
            : confirmCode // ignore: cast_nullable_to_non_nullable
                  as String,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$WorkspaceArchiveRequestImpl implements _WorkspaceArchiveRequest {
  const _$WorkspaceArchiveRequestImpl({required this.confirmCode});

  factory _$WorkspaceArchiveRequestImpl.fromJson(Map<String, dynamic> json) =>
      _$$WorkspaceArchiveRequestImplFromJson(json);

  @override
  final String confirmCode;

  @override
  String toString() {
    return 'WorkspaceArchiveRequest(confirmCode: $confirmCode)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$WorkspaceArchiveRequestImpl &&
            (identical(other.confirmCode, confirmCode) ||
                other.confirmCode == confirmCode));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(runtimeType, confirmCode);

  /// Create a copy of WorkspaceArchiveRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$WorkspaceArchiveRequestImplCopyWith<_$WorkspaceArchiveRequestImpl>
  get copyWith =>
      __$$WorkspaceArchiveRequestImplCopyWithImpl<
        _$WorkspaceArchiveRequestImpl
      >(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$WorkspaceArchiveRequestImplToJson(this);
  }
}

abstract class _WorkspaceArchiveRequest implements WorkspaceArchiveRequest {
  const factory _WorkspaceArchiveRequest({required final String confirmCode}) =
      _$WorkspaceArchiveRequestImpl;

  factory _WorkspaceArchiveRequest.fromJson(Map<String, dynamic> json) =
      _$WorkspaceArchiveRequestImpl.fromJson;

  @override
  String get confirmCode;

  /// Create a copy of WorkspaceArchiveRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$WorkspaceArchiveRequestImplCopyWith<_$WorkspaceArchiveRequestImpl>
  get copyWith => throw _privateConstructorUsedError;
}

// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'audit.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

AuthzAuditEntry _$AuthzAuditEntryFromJson(Map<String, dynamic> json) {
  return _AuthzAuditEntry.fromJson(json);
}

/// @nodoc
mixin _$AuthzAuditEntry {
  int get id => throw _privateConstructorUsedError;
  String get tenant => throw _privateConstructorUsedError;
  String? get sessionId => throw _privateConstructorUsedError;
  String? get turnId => throw _privateConstructorUsedError;
  String? get principal => throw _privateConstructorUsedError;
  String? get toolId => throw _privateConstructorUsedError;
  String get decision => throw _privateConstructorUsedError;
  String? get userReason => throw _privateConstructorUsedError;
  String? get auditReason => throw _privateConstructorUsedError;
  Map<String, dynamic> get policyTrace => throw _privateConstructorUsedError;
  String? get createdAt => throw _privateConstructorUsedError;

  /// Serializes this AuthzAuditEntry to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of AuthzAuditEntry
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $AuthzAuditEntryCopyWith<AuthzAuditEntry> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $AuthzAuditEntryCopyWith<$Res> {
  factory $AuthzAuditEntryCopyWith(
    AuthzAuditEntry value,
    $Res Function(AuthzAuditEntry) then,
  ) = _$AuthzAuditEntryCopyWithImpl<$Res, AuthzAuditEntry>;
  @useResult
  $Res call({
    int id,
    String tenant,
    String? sessionId,
    String? turnId,
    String? principal,
    String? toolId,
    String decision,
    String? userReason,
    String? auditReason,
    Map<String, dynamic> policyTrace,
    String? createdAt,
  });
}

/// @nodoc
class _$AuthzAuditEntryCopyWithImpl<$Res, $Val extends AuthzAuditEntry>
    implements $AuthzAuditEntryCopyWith<$Res> {
  _$AuthzAuditEntryCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of AuthzAuditEntry
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? tenant = null,
    Object? sessionId = freezed,
    Object? turnId = freezed,
    Object? principal = freezed,
    Object? toolId = freezed,
    Object? decision = null,
    Object? userReason = freezed,
    Object? auditReason = freezed,
    Object? policyTrace = null,
    Object? createdAt = freezed,
  }) {
    return _then(
      _value.copyWith(
            id: null == id
                ? _value.id
                : id // ignore: cast_nullable_to_non_nullable
                      as int,
            tenant: null == tenant
                ? _value.tenant
                : tenant // ignore: cast_nullable_to_non_nullable
                      as String,
            sessionId: freezed == sessionId
                ? _value.sessionId
                : sessionId // ignore: cast_nullable_to_non_nullable
                      as String?,
            turnId: freezed == turnId
                ? _value.turnId
                : turnId // ignore: cast_nullable_to_non_nullable
                      as String?,
            principal: freezed == principal
                ? _value.principal
                : principal // ignore: cast_nullable_to_non_nullable
                      as String?,
            toolId: freezed == toolId
                ? _value.toolId
                : toolId // ignore: cast_nullable_to_non_nullable
                      as String?,
            decision: null == decision
                ? _value.decision
                : decision // ignore: cast_nullable_to_non_nullable
                      as String,
            userReason: freezed == userReason
                ? _value.userReason
                : userReason // ignore: cast_nullable_to_non_nullable
                      as String?,
            auditReason: freezed == auditReason
                ? _value.auditReason
                : auditReason // ignore: cast_nullable_to_non_nullable
                      as String?,
            policyTrace: null == policyTrace
                ? _value.policyTrace
                : policyTrace // ignore: cast_nullable_to_non_nullable
                      as Map<String, dynamic>,
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
abstract class _$$AuthzAuditEntryImplCopyWith<$Res>
    implements $AuthzAuditEntryCopyWith<$Res> {
  factory _$$AuthzAuditEntryImplCopyWith(
    _$AuthzAuditEntryImpl value,
    $Res Function(_$AuthzAuditEntryImpl) then,
  ) = __$$AuthzAuditEntryImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    int id,
    String tenant,
    String? sessionId,
    String? turnId,
    String? principal,
    String? toolId,
    String decision,
    String? userReason,
    String? auditReason,
    Map<String, dynamic> policyTrace,
    String? createdAt,
  });
}

/// @nodoc
class __$$AuthzAuditEntryImplCopyWithImpl<$Res>
    extends _$AuthzAuditEntryCopyWithImpl<$Res, _$AuthzAuditEntryImpl>
    implements _$$AuthzAuditEntryImplCopyWith<$Res> {
  __$$AuthzAuditEntryImplCopyWithImpl(
    _$AuthzAuditEntryImpl _value,
    $Res Function(_$AuthzAuditEntryImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of AuthzAuditEntry
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? tenant = null,
    Object? sessionId = freezed,
    Object? turnId = freezed,
    Object? principal = freezed,
    Object? toolId = freezed,
    Object? decision = null,
    Object? userReason = freezed,
    Object? auditReason = freezed,
    Object? policyTrace = null,
    Object? createdAt = freezed,
  }) {
    return _then(
      _$AuthzAuditEntryImpl(
        id: null == id
            ? _value.id
            : id // ignore: cast_nullable_to_non_nullable
                  as int,
        tenant: null == tenant
            ? _value.tenant
            : tenant // ignore: cast_nullable_to_non_nullable
                  as String,
        sessionId: freezed == sessionId
            ? _value.sessionId
            : sessionId // ignore: cast_nullable_to_non_nullable
                  as String?,
        turnId: freezed == turnId
            ? _value.turnId
            : turnId // ignore: cast_nullable_to_non_nullable
                  as String?,
        principal: freezed == principal
            ? _value.principal
            : principal // ignore: cast_nullable_to_non_nullable
                  as String?,
        toolId: freezed == toolId
            ? _value.toolId
            : toolId // ignore: cast_nullable_to_non_nullable
                  as String?,
        decision: null == decision
            ? _value.decision
            : decision // ignore: cast_nullable_to_non_nullable
                  as String,
        userReason: freezed == userReason
            ? _value.userReason
            : userReason // ignore: cast_nullable_to_non_nullable
                  as String?,
        auditReason: freezed == auditReason
            ? _value.auditReason
            : auditReason // ignore: cast_nullable_to_non_nullable
                  as String?,
        policyTrace: null == policyTrace
            ? _value._policyTrace
            : policyTrace // ignore: cast_nullable_to_non_nullable
                  as Map<String, dynamic>,
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
class _$AuthzAuditEntryImpl implements _AuthzAuditEntry {
  const _$AuthzAuditEntryImpl({
    required this.id,
    required this.tenant,
    this.sessionId,
    this.turnId,
    this.principal,
    this.toolId,
    required this.decision,
    this.userReason,
    this.auditReason,
    final Map<String, dynamic> policyTrace = const <String, dynamic>{},
    this.createdAt,
  }) : _policyTrace = policyTrace;

  factory _$AuthzAuditEntryImpl.fromJson(Map<String, dynamic> json) =>
      _$$AuthzAuditEntryImplFromJson(json);

  @override
  final int id;
  @override
  final String tenant;
  @override
  final String? sessionId;
  @override
  final String? turnId;
  @override
  final String? principal;
  @override
  final String? toolId;
  @override
  final String decision;
  @override
  final String? userReason;
  @override
  final String? auditReason;
  final Map<String, dynamic> _policyTrace;
  @override
  @JsonKey()
  Map<String, dynamic> get policyTrace {
    if (_policyTrace is EqualUnmodifiableMapView) return _policyTrace;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_policyTrace);
  }

  @override
  final String? createdAt;

  @override
  String toString() {
    return 'AuthzAuditEntry(id: $id, tenant: $tenant, sessionId: $sessionId, turnId: $turnId, principal: $principal, toolId: $toolId, decision: $decision, userReason: $userReason, auditReason: $auditReason, policyTrace: $policyTrace, createdAt: $createdAt)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$AuthzAuditEntryImpl &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.tenant, tenant) || other.tenant == tenant) &&
            (identical(other.sessionId, sessionId) ||
                other.sessionId == sessionId) &&
            (identical(other.turnId, turnId) || other.turnId == turnId) &&
            (identical(other.principal, principal) ||
                other.principal == principal) &&
            (identical(other.toolId, toolId) || other.toolId == toolId) &&
            (identical(other.decision, decision) ||
                other.decision == decision) &&
            (identical(other.userReason, userReason) ||
                other.userReason == userReason) &&
            (identical(other.auditReason, auditReason) ||
                other.auditReason == auditReason) &&
            const DeepCollectionEquality().equals(
              other._policyTrace,
              _policyTrace,
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
    sessionId,
    turnId,
    principal,
    toolId,
    decision,
    userReason,
    auditReason,
    const DeepCollectionEquality().hash(_policyTrace),
    createdAt,
  );

  /// Create a copy of AuthzAuditEntry
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$AuthzAuditEntryImplCopyWith<_$AuthzAuditEntryImpl> get copyWith =>
      __$$AuthzAuditEntryImplCopyWithImpl<_$AuthzAuditEntryImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$AuthzAuditEntryImplToJson(this);
  }
}

abstract class _AuthzAuditEntry implements AuthzAuditEntry {
  const factory _AuthzAuditEntry({
    required final int id,
    required final String tenant,
    final String? sessionId,
    final String? turnId,
    final String? principal,
    final String? toolId,
    required final String decision,
    final String? userReason,
    final String? auditReason,
    final Map<String, dynamic> policyTrace,
    final String? createdAt,
  }) = _$AuthzAuditEntryImpl;

  factory _AuthzAuditEntry.fromJson(Map<String, dynamic> json) =
      _$AuthzAuditEntryImpl.fromJson;

  @override
  int get id;
  @override
  String get tenant;
  @override
  String? get sessionId;
  @override
  String? get turnId;
  @override
  String? get principal;
  @override
  String? get toolId;
  @override
  String get decision;
  @override
  String? get userReason;
  @override
  String? get auditReason;
  @override
  Map<String, dynamic> get policyTrace;
  @override
  String? get createdAt;

  /// Create a copy of AuthzAuditEntry
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$AuthzAuditEntryImplCopyWith<_$AuthzAuditEntryImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

AuthzAuditPage _$AuthzAuditPageFromJson(Map<String, dynamic> json) {
  return _AuthzAuditPage.fromJson(json);
}

/// @nodoc
mixin _$AuthzAuditPage {
  List<AuthzAuditEntry> get items => throw _privateConstructorUsedError;
  int get page => throw _privateConstructorUsedError;
  int get pageSize => throw _privateConstructorUsedError;
  int get total => throw _privateConstructorUsedError;

  /// Serializes this AuthzAuditPage to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of AuthzAuditPage
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $AuthzAuditPageCopyWith<AuthzAuditPage> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $AuthzAuditPageCopyWith<$Res> {
  factory $AuthzAuditPageCopyWith(
    AuthzAuditPage value,
    $Res Function(AuthzAuditPage) then,
  ) = _$AuthzAuditPageCopyWithImpl<$Res, AuthzAuditPage>;
  @useResult
  $Res call({List<AuthzAuditEntry> items, int page, int pageSize, int total});
}

/// @nodoc
class _$AuthzAuditPageCopyWithImpl<$Res, $Val extends AuthzAuditPage>
    implements $AuthzAuditPageCopyWith<$Res> {
  _$AuthzAuditPageCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of AuthzAuditPage
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? items = null,
    Object? page = null,
    Object? pageSize = null,
    Object? total = null,
  }) {
    return _then(
      _value.copyWith(
            items: null == items
                ? _value.items
                : items // ignore: cast_nullable_to_non_nullable
                      as List<AuthzAuditEntry>,
            page: null == page
                ? _value.page
                : page // ignore: cast_nullable_to_non_nullable
                      as int,
            pageSize: null == pageSize
                ? _value.pageSize
                : pageSize // ignore: cast_nullable_to_non_nullable
                      as int,
            total: null == total
                ? _value.total
                : total // ignore: cast_nullable_to_non_nullable
                      as int,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$AuthzAuditPageImplCopyWith<$Res>
    implements $AuthzAuditPageCopyWith<$Res> {
  factory _$$AuthzAuditPageImplCopyWith(
    _$AuthzAuditPageImpl value,
    $Res Function(_$AuthzAuditPageImpl) then,
  ) = __$$AuthzAuditPageImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({List<AuthzAuditEntry> items, int page, int pageSize, int total});
}

/// @nodoc
class __$$AuthzAuditPageImplCopyWithImpl<$Res>
    extends _$AuthzAuditPageCopyWithImpl<$Res, _$AuthzAuditPageImpl>
    implements _$$AuthzAuditPageImplCopyWith<$Res> {
  __$$AuthzAuditPageImplCopyWithImpl(
    _$AuthzAuditPageImpl _value,
    $Res Function(_$AuthzAuditPageImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of AuthzAuditPage
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? items = null,
    Object? page = null,
    Object? pageSize = null,
    Object? total = null,
  }) {
    return _then(
      _$AuthzAuditPageImpl(
        items: null == items
            ? _value._items
            : items // ignore: cast_nullable_to_non_nullable
                  as List<AuthzAuditEntry>,
        page: null == page
            ? _value.page
            : page // ignore: cast_nullable_to_non_nullable
                  as int,
        pageSize: null == pageSize
            ? _value.pageSize
            : pageSize // ignore: cast_nullable_to_non_nullable
                  as int,
        total: null == total
            ? _value.total
            : total // ignore: cast_nullable_to_non_nullable
                  as int,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$AuthzAuditPageImpl implements _AuthzAuditPage {
  const _$AuthzAuditPageImpl({
    final List<AuthzAuditEntry> items = const <AuthzAuditEntry>[],
    required this.page,
    required this.pageSize,
    required this.total,
  }) : _items = items;

  factory _$AuthzAuditPageImpl.fromJson(Map<String, dynamic> json) =>
      _$$AuthzAuditPageImplFromJson(json);

  final List<AuthzAuditEntry> _items;
  @override
  @JsonKey()
  List<AuthzAuditEntry> get items {
    if (_items is EqualUnmodifiableListView) return _items;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_items);
  }

  @override
  final int page;
  @override
  final int pageSize;
  @override
  final int total;

  @override
  String toString() {
    return 'AuthzAuditPage(items: $items, page: $page, pageSize: $pageSize, total: $total)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$AuthzAuditPageImpl &&
            const DeepCollectionEquality().equals(other._items, _items) &&
            (identical(other.page, page) || other.page == page) &&
            (identical(other.pageSize, pageSize) ||
                other.pageSize == pageSize) &&
            (identical(other.total, total) || other.total == total));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    const DeepCollectionEquality().hash(_items),
    page,
    pageSize,
    total,
  );

  /// Create a copy of AuthzAuditPage
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$AuthzAuditPageImplCopyWith<_$AuthzAuditPageImpl> get copyWith =>
      __$$AuthzAuditPageImplCopyWithImpl<_$AuthzAuditPageImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$AuthzAuditPageImplToJson(this);
  }
}

abstract class _AuthzAuditPage implements AuthzAuditPage {
  const factory _AuthzAuditPage({
    final List<AuthzAuditEntry> items,
    required final int page,
    required final int pageSize,
    required final int total,
  }) = _$AuthzAuditPageImpl;

  factory _AuthzAuditPage.fromJson(Map<String, dynamic> json) =
      _$AuthzAuditPageImpl.fromJson;

  @override
  List<AuthzAuditEntry> get items;
  @override
  int get page;
  @override
  int get pageSize;
  @override
  int get total;

  /// Create a copy of AuthzAuditPage
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$AuthzAuditPageImplCopyWith<_$AuthzAuditPageImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

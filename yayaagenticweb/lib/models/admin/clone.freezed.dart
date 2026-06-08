// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'clone.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

CloneRequest _$CloneRequestFromJson(Map<String, dynamic> json) {
  return _CloneRequest.fromJson(json);
}

/// @nodoc
mixin _$CloneRequest {
  String get destinationTenant => throw _privateConstructorUsedError;
  String? get destinationProfileId => throw _privateConstructorUsedError;
  String get conflictPolicy =>
      throw _privateConstructorUsedError; // FAIL | SKIP | NEW_VERSION
  String get knowledgeLocationStrategy =>
      throw _privateConstructorUsedError; // RETAIN | TEMPLATE | OMIT
  String get personalityPolicy =>
      throw _privateConstructorUsedError; // AUTO | ALWAYS | NEVER
  bool get dryRun => throw _privateConstructorUsedError;

  /// Serializes this CloneRequest to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of CloneRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $CloneRequestCopyWith<CloneRequest> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $CloneRequestCopyWith<$Res> {
  factory $CloneRequestCopyWith(
    CloneRequest value,
    $Res Function(CloneRequest) then,
  ) = _$CloneRequestCopyWithImpl<$Res, CloneRequest>;
  @useResult
  $Res call({
    String destinationTenant,
    String? destinationProfileId,
    String conflictPolicy,
    String knowledgeLocationStrategy,
    String personalityPolicy,
    bool dryRun,
  });
}

/// @nodoc
class _$CloneRequestCopyWithImpl<$Res, $Val extends CloneRequest>
    implements $CloneRequestCopyWith<$Res> {
  _$CloneRequestCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of CloneRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? destinationTenant = null,
    Object? destinationProfileId = freezed,
    Object? conflictPolicy = null,
    Object? knowledgeLocationStrategy = null,
    Object? personalityPolicy = null,
    Object? dryRun = null,
  }) {
    return _then(
      _value.copyWith(
            destinationTenant: null == destinationTenant
                ? _value.destinationTenant
                : destinationTenant // ignore: cast_nullable_to_non_nullable
                      as String,
            destinationProfileId: freezed == destinationProfileId
                ? _value.destinationProfileId
                : destinationProfileId // ignore: cast_nullable_to_non_nullable
                      as String?,
            conflictPolicy: null == conflictPolicy
                ? _value.conflictPolicy
                : conflictPolicy // ignore: cast_nullable_to_non_nullable
                      as String,
            knowledgeLocationStrategy: null == knowledgeLocationStrategy
                ? _value.knowledgeLocationStrategy
                : knowledgeLocationStrategy // ignore: cast_nullable_to_non_nullable
                      as String,
            personalityPolicy: null == personalityPolicy
                ? _value.personalityPolicy
                : personalityPolicy // ignore: cast_nullable_to_non_nullable
                      as String,
            dryRun: null == dryRun
                ? _value.dryRun
                : dryRun // ignore: cast_nullable_to_non_nullable
                      as bool,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$CloneRequestImplCopyWith<$Res>
    implements $CloneRequestCopyWith<$Res> {
  factory _$$CloneRequestImplCopyWith(
    _$CloneRequestImpl value,
    $Res Function(_$CloneRequestImpl) then,
  ) = __$$CloneRequestImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String destinationTenant,
    String? destinationProfileId,
    String conflictPolicy,
    String knowledgeLocationStrategy,
    String personalityPolicy,
    bool dryRun,
  });
}

/// @nodoc
class __$$CloneRequestImplCopyWithImpl<$Res>
    extends _$CloneRequestCopyWithImpl<$Res, _$CloneRequestImpl>
    implements _$$CloneRequestImplCopyWith<$Res> {
  __$$CloneRequestImplCopyWithImpl(
    _$CloneRequestImpl _value,
    $Res Function(_$CloneRequestImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of CloneRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? destinationTenant = null,
    Object? destinationProfileId = freezed,
    Object? conflictPolicy = null,
    Object? knowledgeLocationStrategy = null,
    Object? personalityPolicy = null,
    Object? dryRun = null,
  }) {
    return _then(
      _$CloneRequestImpl(
        destinationTenant: null == destinationTenant
            ? _value.destinationTenant
            : destinationTenant // ignore: cast_nullable_to_non_nullable
                  as String,
        destinationProfileId: freezed == destinationProfileId
            ? _value.destinationProfileId
            : destinationProfileId // ignore: cast_nullable_to_non_nullable
                  as String?,
        conflictPolicy: null == conflictPolicy
            ? _value.conflictPolicy
            : conflictPolicy // ignore: cast_nullable_to_non_nullable
                  as String,
        knowledgeLocationStrategy: null == knowledgeLocationStrategy
            ? _value.knowledgeLocationStrategy
            : knowledgeLocationStrategy // ignore: cast_nullable_to_non_nullable
                  as String,
        personalityPolicy: null == personalityPolicy
            ? _value.personalityPolicy
            : personalityPolicy // ignore: cast_nullable_to_non_nullable
                  as String,
        dryRun: null == dryRun
            ? _value.dryRun
            : dryRun // ignore: cast_nullable_to_non_nullable
                  as bool,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$CloneRequestImpl implements _CloneRequest {
  const _$CloneRequestImpl({
    required this.destinationTenant,
    this.destinationProfileId,
    this.conflictPolicy = 'FAIL',
    this.knowledgeLocationStrategy = 'RETAIN',
    this.personalityPolicy = 'AUTO',
    this.dryRun = true,
  });

  factory _$CloneRequestImpl.fromJson(Map<String, dynamic> json) =>
      _$$CloneRequestImplFromJson(json);

  @override
  final String destinationTenant;
  @override
  final String? destinationProfileId;
  @override
  @JsonKey()
  final String conflictPolicy;
  // FAIL | SKIP | NEW_VERSION
  @override
  @JsonKey()
  final String knowledgeLocationStrategy;
  // RETAIN | TEMPLATE | OMIT
  @override
  @JsonKey()
  final String personalityPolicy;
  // AUTO | ALWAYS | NEVER
  @override
  @JsonKey()
  final bool dryRun;

  @override
  String toString() {
    return 'CloneRequest(destinationTenant: $destinationTenant, destinationProfileId: $destinationProfileId, conflictPolicy: $conflictPolicy, knowledgeLocationStrategy: $knowledgeLocationStrategy, personalityPolicy: $personalityPolicy, dryRun: $dryRun)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$CloneRequestImpl &&
            (identical(other.destinationTenant, destinationTenant) ||
                other.destinationTenant == destinationTenant) &&
            (identical(other.destinationProfileId, destinationProfileId) ||
                other.destinationProfileId == destinationProfileId) &&
            (identical(other.conflictPolicy, conflictPolicy) ||
                other.conflictPolicy == conflictPolicy) &&
            (identical(
                  other.knowledgeLocationStrategy,
                  knowledgeLocationStrategy,
                ) ||
                other.knowledgeLocationStrategy == knowledgeLocationStrategy) &&
            (identical(other.personalityPolicy, personalityPolicy) ||
                other.personalityPolicy == personalityPolicy) &&
            (identical(other.dryRun, dryRun) || other.dryRun == dryRun));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    destinationTenant,
    destinationProfileId,
    conflictPolicy,
    knowledgeLocationStrategy,
    personalityPolicy,
    dryRun,
  );

  /// Create a copy of CloneRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$CloneRequestImplCopyWith<_$CloneRequestImpl> get copyWith =>
      __$$CloneRequestImplCopyWithImpl<_$CloneRequestImpl>(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$CloneRequestImplToJson(this);
  }
}

abstract class _CloneRequest implements CloneRequest {
  const factory _CloneRequest({
    required final String destinationTenant,
    final String? destinationProfileId,
    final String conflictPolicy,
    final String knowledgeLocationStrategy,
    final String personalityPolicy,
    final bool dryRun,
  }) = _$CloneRequestImpl;

  factory _CloneRequest.fromJson(Map<String, dynamic> json) =
      _$CloneRequestImpl.fromJson;

  @override
  String get destinationTenant;
  @override
  String? get destinationProfileId;
  @override
  String get conflictPolicy; // FAIL | SKIP | NEW_VERSION
  @override
  String get knowledgeLocationStrategy; // RETAIN | TEMPLATE | OMIT
  @override
  String get personalityPolicy; // AUTO | ALWAYS | NEVER
  @override
  bool get dryRun;

  /// Create a copy of CloneRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$CloneRequestImplCopyWith<_$CloneRequestImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

ResourceAction _$ResourceActionFromJson(Map<String, dynamic> json) {
  return _ResourceAction.fromJson(json);
}

/// @nodoc
mixin _$ResourceAction {
  String get id => throw _privateConstructorUsedError;
  String get action => throw _privateConstructorUsedError;
  int? get fromVersion => throw _privateConstructorUsedError;
  int? get toVersion => throw _privateConstructorUsedError;
  List<String> get notes => throw _privateConstructorUsedError;

  /// Serializes this ResourceAction to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of ResourceAction
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $ResourceActionCopyWith<ResourceAction> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $ResourceActionCopyWith<$Res> {
  factory $ResourceActionCopyWith(
    ResourceAction value,
    $Res Function(ResourceAction) then,
  ) = _$ResourceActionCopyWithImpl<$Res, ResourceAction>;
  @useResult
  $Res call({
    String id,
    String action,
    int? fromVersion,
    int? toVersion,
    List<String> notes,
  });
}

/// @nodoc
class _$ResourceActionCopyWithImpl<$Res, $Val extends ResourceAction>
    implements $ResourceActionCopyWith<$Res> {
  _$ResourceActionCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of ResourceAction
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? action = null,
    Object? fromVersion = freezed,
    Object? toVersion = freezed,
    Object? notes = null,
  }) {
    return _then(
      _value.copyWith(
            id: null == id
                ? _value.id
                : id // ignore: cast_nullable_to_non_nullable
                      as String,
            action: null == action
                ? _value.action
                : action // ignore: cast_nullable_to_non_nullable
                      as String,
            fromVersion: freezed == fromVersion
                ? _value.fromVersion
                : fromVersion // ignore: cast_nullable_to_non_nullable
                      as int?,
            toVersion: freezed == toVersion
                ? _value.toVersion
                : toVersion // ignore: cast_nullable_to_non_nullable
                      as int?,
            notes: null == notes
                ? _value.notes
                : notes // ignore: cast_nullable_to_non_nullable
                      as List<String>,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$ResourceActionImplCopyWith<$Res>
    implements $ResourceActionCopyWith<$Res> {
  factory _$$ResourceActionImplCopyWith(
    _$ResourceActionImpl value,
    $Res Function(_$ResourceActionImpl) then,
  ) = __$$ResourceActionImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String id,
    String action,
    int? fromVersion,
    int? toVersion,
    List<String> notes,
  });
}

/// @nodoc
class __$$ResourceActionImplCopyWithImpl<$Res>
    extends _$ResourceActionCopyWithImpl<$Res, _$ResourceActionImpl>
    implements _$$ResourceActionImplCopyWith<$Res> {
  __$$ResourceActionImplCopyWithImpl(
    _$ResourceActionImpl _value,
    $Res Function(_$ResourceActionImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of ResourceAction
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? action = null,
    Object? fromVersion = freezed,
    Object? toVersion = freezed,
    Object? notes = null,
  }) {
    return _then(
      _$ResourceActionImpl(
        id: null == id
            ? _value.id
            : id // ignore: cast_nullable_to_non_nullable
                  as String,
        action: null == action
            ? _value.action
            : action // ignore: cast_nullable_to_non_nullable
                  as String,
        fromVersion: freezed == fromVersion
            ? _value.fromVersion
            : fromVersion // ignore: cast_nullable_to_non_nullable
                  as int?,
        toVersion: freezed == toVersion
            ? _value.toVersion
            : toVersion // ignore: cast_nullable_to_non_nullable
                  as int?,
        notes: null == notes
            ? _value._notes
            : notes // ignore: cast_nullable_to_non_nullable
                  as List<String>,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$ResourceActionImpl implements _ResourceAction {
  const _$ResourceActionImpl({
    required this.id,
    required this.action,
    this.fromVersion,
    this.toVersion,
    final List<String> notes = const <String>[],
  }) : _notes = notes;

  factory _$ResourceActionImpl.fromJson(Map<String, dynamic> json) =>
      _$$ResourceActionImplFromJson(json);

  @override
  final String id;
  @override
  final String action;
  @override
  final int? fromVersion;
  @override
  final int? toVersion;
  final List<String> _notes;
  @override
  @JsonKey()
  List<String> get notes {
    if (_notes is EqualUnmodifiableListView) return _notes;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_notes);
  }

  @override
  String toString() {
    return 'ResourceAction(id: $id, action: $action, fromVersion: $fromVersion, toVersion: $toVersion, notes: $notes)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$ResourceActionImpl &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.action, action) || other.action == action) &&
            (identical(other.fromVersion, fromVersion) ||
                other.fromVersion == fromVersion) &&
            (identical(other.toVersion, toVersion) ||
                other.toVersion == toVersion) &&
            const DeepCollectionEquality().equals(other._notes, _notes));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    id,
    action,
    fromVersion,
    toVersion,
    const DeepCollectionEquality().hash(_notes),
  );

  /// Create a copy of ResourceAction
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$ResourceActionImplCopyWith<_$ResourceActionImpl> get copyWith =>
      __$$ResourceActionImplCopyWithImpl<_$ResourceActionImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$ResourceActionImplToJson(this);
  }
}

abstract class _ResourceAction implements ResourceAction {
  const factory _ResourceAction({
    required final String id,
    required final String action,
    final int? fromVersion,
    final int? toVersion,
    final List<String> notes,
  }) = _$ResourceActionImpl;

  factory _ResourceAction.fromJson(Map<String, dynamic> json) =
      _$ResourceActionImpl.fromJson;

  @override
  String get id;
  @override
  String get action;
  @override
  int? get fromVersion;
  @override
  int? get toVersion;
  @override
  List<String> get notes;

  /// Create a copy of ResourceAction
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$ResourceActionImplCopyWith<_$ResourceActionImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

KnowledgeAction _$KnowledgeActionFromJson(Map<String, dynamic> json) {
  return _KnowledgeAction.fromJson(json);
}

/// @nodoc
mixin _$KnowledgeAction {
  String get id => throw _privateConstructorUsedError;
  String get action => throw _privateConstructorUsedError;
  int? get fromVersion => throw _privateConstructorUsedError;
  int? get toVersion => throw _privateConstructorUsedError;
  String? get locationKind => throw _privateConstructorUsedError;
  Map<String, dynamic> get location => throw _privateConstructorUsedError;
  List<String> get notes => throw _privateConstructorUsedError;

  /// Serializes this KnowledgeAction to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of KnowledgeAction
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $KnowledgeActionCopyWith<KnowledgeAction> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $KnowledgeActionCopyWith<$Res> {
  factory $KnowledgeActionCopyWith(
    KnowledgeAction value,
    $Res Function(KnowledgeAction) then,
  ) = _$KnowledgeActionCopyWithImpl<$Res, KnowledgeAction>;
  @useResult
  $Res call({
    String id,
    String action,
    int? fromVersion,
    int? toVersion,
    String? locationKind,
    Map<String, dynamic> location,
    List<String> notes,
  });
}

/// @nodoc
class _$KnowledgeActionCopyWithImpl<$Res, $Val extends KnowledgeAction>
    implements $KnowledgeActionCopyWith<$Res> {
  _$KnowledgeActionCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of KnowledgeAction
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? action = null,
    Object? fromVersion = freezed,
    Object? toVersion = freezed,
    Object? locationKind = freezed,
    Object? location = null,
    Object? notes = null,
  }) {
    return _then(
      _value.copyWith(
            id: null == id
                ? _value.id
                : id // ignore: cast_nullable_to_non_nullable
                      as String,
            action: null == action
                ? _value.action
                : action // ignore: cast_nullable_to_non_nullable
                      as String,
            fromVersion: freezed == fromVersion
                ? _value.fromVersion
                : fromVersion // ignore: cast_nullable_to_non_nullable
                      as int?,
            toVersion: freezed == toVersion
                ? _value.toVersion
                : toVersion // ignore: cast_nullable_to_non_nullable
                      as int?,
            locationKind: freezed == locationKind
                ? _value.locationKind
                : locationKind // ignore: cast_nullable_to_non_nullable
                      as String?,
            location: null == location
                ? _value.location
                : location // ignore: cast_nullable_to_non_nullable
                      as Map<String, dynamic>,
            notes: null == notes
                ? _value.notes
                : notes // ignore: cast_nullable_to_non_nullable
                      as List<String>,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$KnowledgeActionImplCopyWith<$Res>
    implements $KnowledgeActionCopyWith<$Res> {
  factory _$$KnowledgeActionImplCopyWith(
    _$KnowledgeActionImpl value,
    $Res Function(_$KnowledgeActionImpl) then,
  ) = __$$KnowledgeActionImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String id,
    String action,
    int? fromVersion,
    int? toVersion,
    String? locationKind,
    Map<String, dynamic> location,
    List<String> notes,
  });
}

/// @nodoc
class __$$KnowledgeActionImplCopyWithImpl<$Res>
    extends _$KnowledgeActionCopyWithImpl<$Res, _$KnowledgeActionImpl>
    implements _$$KnowledgeActionImplCopyWith<$Res> {
  __$$KnowledgeActionImplCopyWithImpl(
    _$KnowledgeActionImpl _value,
    $Res Function(_$KnowledgeActionImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of KnowledgeAction
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? action = null,
    Object? fromVersion = freezed,
    Object? toVersion = freezed,
    Object? locationKind = freezed,
    Object? location = null,
    Object? notes = null,
  }) {
    return _then(
      _$KnowledgeActionImpl(
        id: null == id
            ? _value.id
            : id // ignore: cast_nullable_to_non_nullable
                  as String,
        action: null == action
            ? _value.action
            : action // ignore: cast_nullable_to_non_nullable
                  as String,
        fromVersion: freezed == fromVersion
            ? _value.fromVersion
            : fromVersion // ignore: cast_nullable_to_non_nullable
                  as int?,
        toVersion: freezed == toVersion
            ? _value.toVersion
            : toVersion // ignore: cast_nullable_to_non_nullable
                  as int?,
        locationKind: freezed == locationKind
            ? _value.locationKind
            : locationKind // ignore: cast_nullable_to_non_nullable
                  as String?,
        location: null == location
            ? _value._location
            : location // ignore: cast_nullable_to_non_nullable
                  as Map<String, dynamic>,
        notes: null == notes
            ? _value._notes
            : notes // ignore: cast_nullable_to_non_nullable
                  as List<String>,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$KnowledgeActionImpl implements _KnowledgeAction {
  const _$KnowledgeActionImpl({
    required this.id,
    required this.action,
    this.fromVersion,
    this.toVersion,
    this.locationKind,
    final Map<String, dynamic> location = const <String, dynamic>{},
    final List<String> notes = const <String>[],
  }) : _location = location,
       _notes = notes;

  factory _$KnowledgeActionImpl.fromJson(Map<String, dynamic> json) =>
      _$$KnowledgeActionImplFromJson(json);

  @override
  final String id;
  @override
  final String action;
  @override
  final int? fromVersion;
  @override
  final int? toVersion;
  @override
  final String? locationKind;
  final Map<String, dynamic> _location;
  @override
  @JsonKey()
  Map<String, dynamic> get location {
    if (_location is EqualUnmodifiableMapView) return _location;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_location);
  }

  final List<String> _notes;
  @override
  @JsonKey()
  List<String> get notes {
    if (_notes is EqualUnmodifiableListView) return _notes;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_notes);
  }

  @override
  String toString() {
    return 'KnowledgeAction(id: $id, action: $action, fromVersion: $fromVersion, toVersion: $toVersion, locationKind: $locationKind, location: $location, notes: $notes)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$KnowledgeActionImpl &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.action, action) || other.action == action) &&
            (identical(other.fromVersion, fromVersion) ||
                other.fromVersion == fromVersion) &&
            (identical(other.toVersion, toVersion) ||
                other.toVersion == toVersion) &&
            (identical(other.locationKind, locationKind) ||
                other.locationKind == locationKind) &&
            const DeepCollectionEquality().equals(other._location, _location) &&
            const DeepCollectionEquality().equals(other._notes, _notes));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    id,
    action,
    fromVersion,
    toVersion,
    locationKind,
    const DeepCollectionEquality().hash(_location),
    const DeepCollectionEquality().hash(_notes),
  );

  /// Create a copy of KnowledgeAction
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$KnowledgeActionImplCopyWith<_$KnowledgeActionImpl> get copyWith =>
      __$$KnowledgeActionImplCopyWithImpl<_$KnowledgeActionImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$KnowledgeActionImplToJson(this);
  }
}

abstract class _KnowledgeAction implements KnowledgeAction {
  const factory _KnowledgeAction({
    required final String id,
    required final String action,
    final int? fromVersion,
    final int? toVersion,
    final String? locationKind,
    final Map<String, dynamic> location,
    final List<String> notes,
  }) = _$KnowledgeActionImpl;

  factory _KnowledgeAction.fromJson(Map<String, dynamic> json) =
      _$KnowledgeActionImpl.fromJson;

  @override
  String get id;
  @override
  String get action;
  @override
  int? get fromVersion;
  @override
  int? get toVersion;
  @override
  String? get locationKind;
  @override
  Map<String, dynamic> get location;
  @override
  List<String> get notes;

  /// Create a copy of KnowledgeAction
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$KnowledgeActionImplCopyWith<_$KnowledgeActionImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

PersonalityAction _$PersonalityActionFromJson(Map<String, dynamic> json) {
  return _PersonalityAction.fromJson(json);
}

/// @nodoc
mixin _$PersonalityAction {
  String? get locale => throw _privateConstructorUsedError;
  String get action => throw _privateConstructorUsedError;
  int? get fromVersion => throw _privateConstructorUsedError;
  int? get toVersion => throw _privateConstructorUsedError;
  List<String> get notes => throw _privateConstructorUsedError;

  /// Serializes this PersonalityAction to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of PersonalityAction
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $PersonalityActionCopyWith<PersonalityAction> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $PersonalityActionCopyWith<$Res> {
  factory $PersonalityActionCopyWith(
    PersonalityAction value,
    $Res Function(PersonalityAction) then,
  ) = _$PersonalityActionCopyWithImpl<$Res, PersonalityAction>;
  @useResult
  $Res call({
    String? locale,
    String action,
    int? fromVersion,
    int? toVersion,
    List<String> notes,
  });
}

/// @nodoc
class _$PersonalityActionCopyWithImpl<$Res, $Val extends PersonalityAction>
    implements $PersonalityActionCopyWith<$Res> {
  _$PersonalityActionCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of PersonalityAction
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? locale = freezed,
    Object? action = null,
    Object? fromVersion = freezed,
    Object? toVersion = freezed,
    Object? notes = null,
  }) {
    return _then(
      _value.copyWith(
            locale: freezed == locale
                ? _value.locale
                : locale // ignore: cast_nullable_to_non_nullable
                      as String?,
            action: null == action
                ? _value.action
                : action // ignore: cast_nullable_to_non_nullable
                      as String,
            fromVersion: freezed == fromVersion
                ? _value.fromVersion
                : fromVersion // ignore: cast_nullable_to_non_nullable
                      as int?,
            toVersion: freezed == toVersion
                ? _value.toVersion
                : toVersion // ignore: cast_nullable_to_non_nullable
                      as int?,
            notes: null == notes
                ? _value.notes
                : notes // ignore: cast_nullable_to_non_nullable
                      as List<String>,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$PersonalityActionImplCopyWith<$Res>
    implements $PersonalityActionCopyWith<$Res> {
  factory _$$PersonalityActionImplCopyWith(
    _$PersonalityActionImpl value,
    $Res Function(_$PersonalityActionImpl) then,
  ) = __$$PersonalityActionImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String? locale,
    String action,
    int? fromVersion,
    int? toVersion,
    List<String> notes,
  });
}

/// @nodoc
class __$$PersonalityActionImplCopyWithImpl<$Res>
    extends _$PersonalityActionCopyWithImpl<$Res, _$PersonalityActionImpl>
    implements _$$PersonalityActionImplCopyWith<$Res> {
  __$$PersonalityActionImplCopyWithImpl(
    _$PersonalityActionImpl _value,
    $Res Function(_$PersonalityActionImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of PersonalityAction
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? locale = freezed,
    Object? action = null,
    Object? fromVersion = freezed,
    Object? toVersion = freezed,
    Object? notes = null,
  }) {
    return _then(
      _$PersonalityActionImpl(
        locale: freezed == locale
            ? _value.locale
            : locale // ignore: cast_nullable_to_non_nullable
                  as String?,
        action: null == action
            ? _value.action
            : action // ignore: cast_nullable_to_non_nullable
                  as String,
        fromVersion: freezed == fromVersion
            ? _value.fromVersion
            : fromVersion // ignore: cast_nullable_to_non_nullable
                  as int?,
        toVersion: freezed == toVersion
            ? _value.toVersion
            : toVersion // ignore: cast_nullable_to_non_nullable
                  as int?,
        notes: null == notes
            ? _value._notes
            : notes // ignore: cast_nullable_to_non_nullable
                  as List<String>,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$PersonalityActionImpl implements _PersonalityAction {
  const _$PersonalityActionImpl({
    this.locale,
    required this.action,
    this.fromVersion,
    this.toVersion,
    final List<String> notes = const <String>[],
  }) : _notes = notes;

  factory _$PersonalityActionImpl.fromJson(Map<String, dynamic> json) =>
      _$$PersonalityActionImplFromJson(json);

  @override
  final String? locale;
  @override
  final String action;
  @override
  final int? fromVersion;
  @override
  final int? toVersion;
  final List<String> _notes;
  @override
  @JsonKey()
  List<String> get notes {
    if (_notes is EqualUnmodifiableListView) return _notes;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_notes);
  }

  @override
  String toString() {
    return 'PersonalityAction(locale: $locale, action: $action, fromVersion: $fromVersion, toVersion: $toVersion, notes: $notes)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$PersonalityActionImpl &&
            (identical(other.locale, locale) || other.locale == locale) &&
            (identical(other.action, action) || other.action == action) &&
            (identical(other.fromVersion, fromVersion) ||
                other.fromVersion == fromVersion) &&
            (identical(other.toVersion, toVersion) ||
                other.toVersion == toVersion) &&
            const DeepCollectionEquality().equals(other._notes, _notes));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    locale,
    action,
    fromVersion,
    toVersion,
    const DeepCollectionEquality().hash(_notes),
  );

  /// Create a copy of PersonalityAction
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$PersonalityActionImplCopyWith<_$PersonalityActionImpl> get copyWith =>
      __$$PersonalityActionImplCopyWithImpl<_$PersonalityActionImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$PersonalityActionImplToJson(this);
  }
}

abstract class _PersonalityAction implements PersonalityAction {
  const factory _PersonalityAction({
    final String? locale,
    required final String action,
    final int? fromVersion,
    final int? toVersion,
    final List<String> notes,
  }) = _$PersonalityActionImpl;

  factory _PersonalityAction.fromJson(Map<String, dynamic> json) =
      _$PersonalityActionImpl.fromJson;

  @override
  String? get locale;
  @override
  String get action;
  @override
  int? get fromVersion;
  @override
  int? get toVersion;
  @override
  List<String> get notes;

  /// Create a copy of PersonalityAction
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$PersonalityActionImplCopyWith<_$PersonalityActionImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

ClonePlan _$ClonePlanFromJson(Map<String, dynamic> json) {
  return _ClonePlan.fromJson(json);
}

/// @nodoc
mixin _$ClonePlan {
  String get sourceTenant => throw _privateConstructorUsedError;
  String get destinationTenant => throw _privateConstructorUsedError;
  String get destinationProfileId => throw _privateConstructorUsedError;
  ResourceAction get profile => throw _privateConstructorUsedError;
  List<ResourceAction> get capabilities => throw _privateConstructorUsedError;
  List<ResourceAction> get tools => throw _privateConstructorUsedError;
  List<KnowledgeAction> get knowledgeSources =>
      throw _privateConstructorUsedError;
  List<ResourceAction> get authBindings => throw _privateConstructorUsedError;
  List<ResourceAction> get recordingStrategies =>
      throw _privateConstructorUsedError;
  List<PersonalityAction> get personality => throw _privateConstructorUsedError;
  List<String> get warnings => throw _privateConstructorUsedError;

  /// Serializes this ClonePlan to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of ClonePlan
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $ClonePlanCopyWith<ClonePlan> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $ClonePlanCopyWith<$Res> {
  factory $ClonePlanCopyWith(ClonePlan value, $Res Function(ClonePlan) then) =
      _$ClonePlanCopyWithImpl<$Res, ClonePlan>;
  @useResult
  $Res call({
    String sourceTenant,
    String destinationTenant,
    String destinationProfileId,
    ResourceAction profile,
    List<ResourceAction> capabilities,
    List<ResourceAction> tools,
    List<KnowledgeAction> knowledgeSources,
    List<ResourceAction> authBindings,
    List<ResourceAction> recordingStrategies,
    List<PersonalityAction> personality,
    List<String> warnings,
  });

  $ResourceActionCopyWith<$Res> get profile;
}

/// @nodoc
class _$ClonePlanCopyWithImpl<$Res, $Val extends ClonePlan>
    implements $ClonePlanCopyWith<$Res> {
  _$ClonePlanCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of ClonePlan
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? sourceTenant = null,
    Object? destinationTenant = null,
    Object? destinationProfileId = null,
    Object? profile = null,
    Object? capabilities = null,
    Object? tools = null,
    Object? knowledgeSources = null,
    Object? authBindings = null,
    Object? recordingStrategies = null,
    Object? personality = null,
    Object? warnings = null,
  }) {
    return _then(
      _value.copyWith(
            sourceTenant: null == sourceTenant
                ? _value.sourceTenant
                : sourceTenant // ignore: cast_nullable_to_non_nullable
                      as String,
            destinationTenant: null == destinationTenant
                ? _value.destinationTenant
                : destinationTenant // ignore: cast_nullable_to_non_nullable
                      as String,
            destinationProfileId: null == destinationProfileId
                ? _value.destinationProfileId
                : destinationProfileId // ignore: cast_nullable_to_non_nullable
                      as String,
            profile: null == profile
                ? _value.profile
                : profile // ignore: cast_nullable_to_non_nullable
                      as ResourceAction,
            capabilities: null == capabilities
                ? _value.capabilities
                : capabilities // ignore: cast_nullable_to_non_nullable
                      as List<ResourceAction>,
            tools: null == tools
                ? _value.tools
                : tools // ignore: cast_nullable_to_non_nullable
                      as List<ResourceAction>,
            knowledgeSources: null == knowledgeSources
                ? _value.knowledgeSources
                : knowledgeSources // ignore: cast_nullable_to_non_nullable
                      as List<KnowledgeAction>,
            authBindings: null == authBindings
                ? _value.authBindings
                : authBindings // ignore: cast_nullable_to_non_nullable
                      as List<ResourceAction>,
            recordingStrategies: null == recordingStrategies
                ? _value.recordingStrategies
                : recordingStrategies // ignore: cast_nullable_to_non_nullable
                      as List<ResourceAction>,
            personality: null == personality
                ? _value.personality
                : personality // ignore: cast_nullable_to_non_nullable
                      as List<PersonalityAction>,
            warnings: null == warnings
                ? _value.warnings
                : warnings // ignore: cast_nullable_to_non_nullable
                      as List<String>,
          )
          as $Val,
    );
  }

  /// Create a copy of ClonePlan
  /// with the given fields replaced by the non-null parameter values.
  @override
  @pragma('vm:prefer-inline')
  $ResourceActionCopyWith<$Res> get profile {
    return $ResourceActionCopyWith<$Res>(_value.profile, (value) {
      return _then(_value.copyWith(profile: value) as $Val);
    });
  }
}

/// @nodoc
abstract class _$$ClonePlanImplCopyWith<$Res>
    implements $ClonePlanCopyWith<$Res> {
  factory _$$ClonePlanImplCopyWith(
    _$ClonePlanImpl value,
    $Res Function(_$ClonePlanImpl) then,
  ) = __$$ClonePlanImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String sourceTenant,
    String destinationTenant,
    String destinationProfileId,
    ResourceAction profile,
    List<ResourceAction> capabilities,
    List<ResourceAction> tools,
    List<KnowledgeAction> knowledgeSources,
    List<ResourceAction> authBindings,
    List<ResourceAction> recordingStrategies,
    List<PersonalityAction> personality,
    List<String> warnings,
  });

  @override
  $ResourceActionCopyWith<$Res> get profile;
}

/// @nodoc
class __$$ClonePlanImplCopyWithImpl<$Res>
    extends _$ClonePlanCopyWithImpl<$Res, _$ClonePlanImpl>
    implements _$$ClonePlanImplCopyWith<$Res> {
  __$$ClonePlanImplCopyWithImpl(
    _$ClonePlanImpl _value,
    $Res Function(_$ClonePlanImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of ClonePlan
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? sourceTenant = null,
    Object? destinationTenant = null,
    Object? destinationProfileId = null,
    Object? profile = null,
    Object? capabilities = null,
    Object? tools = null,
    Object? knowledgeSources = null,
    Object? authBindings = null,
    Object? recordingStrategies = null,
    Object? personality = null,
    Object? warnings = null,
  }) {
    return _then(
      _$ClonePlanImpl(
        sourceTenant: null == sourceTenant
            ? _value.sourceTenant
            : sourceTenant // ignore: cast_nullable_to_non_nullable
                  as String,
        destinationTenant: null == destinationTenant
            ? _value.destinationTenant
            : destinationTenant // ignore: cast_nullable_to_non_nullable
                  as String,
        destinationProfileId: null == destinationProfileId
            ? _value.destinationProfileId
            : destinationProfileId // ignore: cast_nullable_to_non_nullable
                  as String,
        profile: null == profile
            ? _value.profile
            : profile // ignore: cast_nullable_to_non_nullable
                  as ResourceAction,
        capabilities: null == capabilities
            ? _value._capabilities
            : capabilities // ignore: cast_nullable_to_non_nullable
                  as List<ResourceAction>,
        tools: null == tools
            ? _value._tools
            : tools // ignore: cast_nullable_to_non_nullable
                  as List<ResourceAction>,
        knowledgeSources: null == knowledgeSources
            ? _value._knowledgeSources
            : knowledgeSources // ignore: cast_nullable_to_non_nullable
                  as List<KnowledgeAction>,
        authBindings: null == authBindings
            ? _value._authBindings
            : authBindings // ignore: cast_nullable_to_non_nullable
                  as List<ResourceAction>,
        recordingStrategies: null == recordingStrategies
            ? _value._recordingStrategies
            : recordingStrategies // ignore: cast_nullable_to_non_nullable
                  as List<ResourceAction>,
        personality: null == personality
            ? _value._personality
            : personality // ignore: cast_nullable_to_non_nullable
                  as List<PersonalityAction>,
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
class _$ClonePlanImpl implements _ClonePlan {
  const _$ClonePlanImpl({
    required this.sourceTenant,
    required this.destinationTenant,
    required this.destinationProfileId,
    required this.profile,
    final List<ResourceAction> capabilities = const <ResourceAction>[],
    final List<ResourceAction> tools = const <ResourceAction>[],
    final List<KnowledgeAction> knowledgeSources = const <KnowledgeAction>[],
    final List<ResourceAction> authBindings = const <ResourceAction>[],
    final List<ResourceAction> recordingStrategies = const <ResourceAction>[],
    final List<PersonalityAction> personality = const <PersonalityAction>[],
    final List<String> warnings = const <String>[],
  }) : _capabilities = capabilities,
       _tools = tools,
       _knowledgeSources = knowledgeSources,
       _authBindings = authBindings,
       _recordingStrategies = recordingStrategies,
       _personality = personality,
       _warnings = warnings;

  factory _$ClonePlanImpl.fromJson(Map<String, dynamic> json) =>
      _$$ClonePlanImplFromJson(json);

  @override
  final String sourceTenant;
  @override
  final String destinationTenant;
  @override
  final String destinationProfileId;
  @override
  final ResourceAction profile;
  final List<ResourceAction> _capabilities;
  @override
  @JsonKey()
  List<ResourceAction> get capabilities {
    if (_capabilities is EqualUnmodifiableListView) return _capabilities;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_capabilities);
  }

  final List<ResourceAction> _tools;
  @override
  @JsonKey()
  List<ResourceAction> get tools {
    if (_tools is EqualUnmodifiableListView) return _tools;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_tools);
  }

  final List<KnowledgeAction> _knowledgeSources;
  @override
  @JsonKey()
  List<KnowledgeAction> get knowledgeSources {
    if (_knowledgeSources is EqualUnmodifiableListView)
      return _knowledgeSources;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_knowledgeSources);
  }

  final List<ResourceAction> _authBindings;
  @override
  @JsonKey()
  List<ResourceAction> get authBindings {
    if (_authBindings is EqualUnmodifiableListView) return _authBindings;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_authBindings);
  }

  final List<ResourceAction> _recordingStrategies;
  @override
  @JsonKey()
  List<ResourceAction> get recordingStrategies {
    if (_recordingStrategies is EqualUnmodifiableListView)
      return _recordingStrategies;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_recordingStrategies);
  }

  final List<PersonalityAction> _personality;
  @override
  @JsonKey()
  List<PersonalityAction> get personality {
    if (_personality is EqualUnmodifiableListView) return _personality;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_personality);
  }

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
    return 'ClonePlan(sourceTenant: $sourceTenant, destinationTenant: $destinationTenant, destinationProfileId: $destinationProfileId, profile: $profile, capabilities: $capabilities, tools: $tools, knowledgeSources: $knowledgeSources, authBindings: $authBindings, recordingStrategies: $recordingStrategies, personality: $personality, warnings: $warnings)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$ClonePlanImpl &&
            (identical(other.sourceTenant, sourceTenant) ||
                other.sourceTenant == sourceTenant) &&
            (identical(other.destinationTenant, destinationTenant) ||
                other.destinationTenant == destinationTenant) &&
            (identical(other.destinationProfileId, destinationProfileId) ||
                other.destinationProfileId == destinationProfileId) &&
            (identical(other.profile, profile) || other.profile == profile) &&
            const DeepCollectionEquality().equals(
              other._capabilities,
              _capabilities,
            ) &&
            const DeepCollectionEquality().equals(other._tools, _tools) &&
            const DeepCollectionEquality().equals(
              other._knowledgeSources,
              _knowledgeSources,
            ) &&
            const DeepCollectionEquality().equals(
              other._authBindings,
              _authBindings,
            ) &&
            const DeepCollectionEquality().equals(
              other._recordingStrategies,
              _recordingStrategies,
            ) &&
            const DeepCollectionEquality().equals(
              other._personality,
              _personality,
            ) &&
            const DeepCollectionEquality().equals(other._warnings, _warnings));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    sourceTenant,
    destinationTenant,
    destinationProfileId,
    profile,
    const DeepCollectionEquality().hash(_capabilities),
    const DeepCollectionEquality().hash(_tools),
    const DeepCollectionEquality().hash(_knowledgeSources),
    const DeepCollectionEquality().hash(_authBindings),
    const DeepCollectionEquality().hash(_recordingStrategies),
    const DeepCollectionEquality().hash(_personality),
    const DeepCollectionEquality().hash(_warnings),
  );

  /// Create a copy of ClonePlan
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$ClonePlanImplCopyWith<_$ClonePlanImpl> get copyWith =>
      __$$ClonePlanImplCopyWithImpl<_$ClonePlanImpl>(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$ClonePlanImplToJson(this);
  }
}

abstract class _ClonePlan implements ClonePlan {
  const factory _ClonePlan({
    required final String sourceTenant,
    required final String destinationTenant,
    required final String destinationProfileId,
    required final ResourceAction profile,
    final List<ResourceAction> capabilities,
    final List<ResourceAction> tools,
    final List<KnowledgeAction> knowledgeSources,
    final List<ResourceAction> authBindings,
    final List<ResourceAction> recordingStrategies,
    final List<PersonalityAction> personality,
    final List<String> warnings,
  }) = _$ClonePlanImpl;

  factory _ClonePlan.fromJson(Map<String, dynamic> json) =
      _$ClonePlanImpl.fromJson;

  @override
  String get sourceTenant;
  @override
  String get destinationTenant;
  @override
  String get destinationProfileId;
  @override
  ResourceAction get profile;
  @override
  List<ResourceAction> get capabilities;
  @override
  List<ResourceAction> get tools;
  @override
  List<KnowledgeAction> get knowledgeSources;
  @override
  List<ResourceAction> get authBindings;
  @override
  List<ResourceAction> get recordingStrategies;
  @override
  List<PersonalityAction> get personality;
  @override
  List<String> get warnings;

  /// Create a copy of ClonePlan
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$ClonePlanImplCopyWith<_$ClonePlanImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

CloneResult _$CloneResultFromJson(Map<String, dynamic> json) {
  return _CloneResult.fromJson(json);
}

/// @nodoc
mixin _$CloneResult {
  String? get jobId => throw _privateConstructorUsedError;
  String get status =>
      throw _privateConstructorUsedError; // DRY_RUN | APPLIED | FAILED
  ClonePlan get plan => throw _privateConstructorUsedError;
  String? get errorCode => throw _privateConstructorUsedError;
  String? get errorMessage => throw _privateConstructorUsedError;

  /// Serializes this CloneResult to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of CloneResult
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $CloneResultCopyWith<CloneResult> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $CloneResultCopyWith<$Res> {
  factory $CloneResultCopyWith(
    CloneResult value,
    $Res Function(CloneResult) then,
  ) = _$CloneResultCopyWithImpl<$Res, CloneResult>;
  @useResult
  $Res call({
    String? jobId,
    String status,
    ClonePlan plan,
    String? errorCode,
    String? errorMessage,
  });

  $ClonePlanCopyWith<$Res> get plan;
}

/// @nodoc
class _$CloneResultCopyWithImpl<$Res, $Val extends CloneResult>
    implements $CloneResultCopyWith<$Res> {
  _$CloneResultCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of CloneResult
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? jobId = freezed,
    Object? status = null,
    Object? plan = null,
    Object? errorCode = freezed,
    Object? errorMessage = freezed,
  }) {
    return _then(
      _value.copyWith(
            jobId: freezed == jobId
                ? _value.jobId
                : jobId // ignore: cast_nullable_to_non_nullable
                      as String?,
            status: null == status
                ? _value.status
                : status // ignore: cast_nullable_to_non_nullable
                      as String,
            plan: null == plan
                ? _value.plan
                : plan // ignore: cast_nullable_to_non_nullable
                      as ClonePlan,
            errorCode: freezed == errorCode
                ? _value.errorCode
                : errorCode // ignore: cast_nullable_to_non_nullable
                      as String?,
            errorMessage: freezed == errorMessage
                ? _value.errorMessage
                : errorMessage // ignore: cast_nullable_to_non_nullable
                      as String?,
          )
          as $Val,
    );
  }

  /// Create a copy of CloneResult
  /// with the given fields replaced by the non-null parameter values.
  @override
  @pragma('vm:prefer-inline')
  $ClonePlanCopyWith<$Res> get plan {
    return $ClonePlanCopyWith<$Res>(_value.plan, (value) {
      return _then(_value.copyWith(plan: value) as $Val);
    });
  }
}

/// @nodoc
abstract class _$$CloneResultImplCopyWith<$Res>
    implements $CloneResultCopyWith<$Res> {
  factory _$$CloneResultImplCopyWith(
    _$CloneResultImpl value,
    $Res Function(_$CloneResultImpl) then,
  ) = __$$CloneResultImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String? jobId,
    String status,
    ClonePlan plan,
    String? errorCode,
    String? errorMessage,
  });

  @override
  $ClonePlanCopyWith<$Res> get plan;
}

/// @nodoc
class __$$CloneResultImplCopyWithImpl<$Res>
    extends _$CloneResultCopyWithImpl<$Res, _$CloneResultImpl>
    implements _$$CloneResultImplCopyWith<$Res> {
  __$$CloneResultImplCopyWithImpl(
    _$CloneResultImpl _value,
    $Res Function(_$CloneResultImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of CloneResult
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? jobId = freezed,
    Object? status = null,
    Object? plan = null,
    Object? errorCode = freezed,
    Object? errorMessage = freezed,
  }) {
    return _then(
      _$CloneResultImpl(
        jobId: freezed == jobId
            ? _value.jobId
            : jobId // ignore: cast_nullable_to_non_nullable
                  as String?,
        status: null == status
            ? _value.status
            : status // ignore: cast_nullable_to_non_nullable
                  as String,
        plan: null == plan
            ? _value.plan
            : plan // ignore: cast_nullable_to_non_nullable
                  as ClonePlan,
        errorCode: freezed == errorCode
            ? _value.errorCode
            : errorCode // ignore: cast_nullable_to_non_nullable
                  as String?,
        errorMessage: freezed == errorMessage
            ? _value.errorMessage
            : errorMessage // ignore: cast_nullable_to_non_nullable
                  as String?,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$CloneResultImpl implements _CloneResult {
  const _$CloneResultImpl({
    this.jobId,
    required this.status,
    required this.plan,
    this.errorCode,
    this.errorMessage,
  });

  factory _$CloneResultImpl.fromJson(Map<String, dynamic> json) =>
      _$$CloneResultImplFromJson(json);

  @override
  final String? jobId;
  @override
  final String status;
  // DRY_RUN | APPLIED | FAILED
  @override
  final ClonePlan plan;
  @override
  final String? errorCode;
  @override
  final String? errorMessage;

  @override
  String toString() {
    return 'CloneResult(jobId: $jobId, status: $status, plan: $plan, errorCode: $errorCode, errorMessage: $errorMessage)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$CloneResultImpl &&
            (identical(other.jobId, jobId) || other.jobId == jobId) &&
            (identical(other.status, status) || other.status == status) &&
            (identical(other.plan, plan) || other.plan == plan) &&
            (identical(other.errorCode, errorCode) ||
                other.errorCode == errorCode) &&
            (identical(other.errorMessage, errorMessage) ||
                other.errorMessage == errorMessage));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode =>
      Object.hash(runtimeType, jobId, status, plan, errorCode, errorMessage);

  /// Create a copy of CloneResult
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$CloneResultImplCopyWith<_$CloneResultImpl> get copyWith =>
      __$$CloneResultImplCopyWithImpl<_$CloneResultImpl>(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$CloneResultImplToJson(this);
  }
}

abstract class _CloneResult implements CloneResult {
  const factory _CloneResult({
    final String? jobId,
    required final String status,
    required final ClonePlan plan,
    final String? errorCode,
    final String? errorMessage,
  }) = _$CloneResultImpl;

  factory _CloneResult.fromJson(Map<String, dynamic> json) =
      _$CloneResultImpl.fromJson;

  @override
  String? get jobId;
  @override
  String get status; // DRY_RUN | APPLIED | FAILED
  @override
  ClonePlan get plan;
  @override
  String? get errorCode;
  @override
  String? get errorMessage;

  /// Create a copy of CloneResult
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$CloneResultImplCopyWith<_$CloneResultImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

MigrateToPathPlan _$MigrateToPathPlanFromJson(Map<String, dynamic> json) {
  return _MigrateToPathPlan.fromJson(json);
}

/// @nodoc
mixin _$MigrateToPathPlan {
  List<MigrateCandidate> get candidates => throw _privateConstructorUsedError;
  List<MigrateUnsafe> get unsafe => throw _privateConstructorUsedError;

  /// Serializes this MigrateToPathPlan to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of MigrateToPathPlan
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $MigrateToPathPlanCopyWith<MigrateToPathPlan> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $MigrateToPathPlanCopyWith<$Res> {
  factory $MigrateToPathPlanCopyWith(
    MigrateToPathPlan value,
    $Res Function(MigrateToPathPlan) then,
  ) = _$MigrateToPathPlanCopyWithImpl<$Res, MigrateToPathPlan>;
  @useResult
  $Res call({List<MigrateCandidate> candidates, List<MigrateUnsafe> unsafe});
}

/// @nodoc
class _$MigrateToPathPlanCopyWithImpl<$Res, $Val extends MigrateToPathPlan>
    implements $MigrateToPathPlanCopyWith<$Res> {
  _$MigrateToPathPlanCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of MigrateToPathPlan
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? candidates = null, Object? unsafe = null}) {
    return _then(
      _value.copyWith(
            candidates: null == candidates
                ? _value.candidates
                : candidates // ignore: cast_nullable_to_non_nullable
                      as List<MigrateCandidate>,
            unsafe: null == unsafe
                ? _value.unsafe
                : unsafe // ignore: cast_nullable_to_non_nullable
                      as List<MigrateUnsafe>,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$MigrateToPathPlanImplCopyWith<$Res>
    implements $MigrateToPathPlanCopyWith<$Res> {
  factory _$$MigrateToPathPlanImplCopyWith(
    _$MigrateToPathPlanImpl value,
    $Res Function(_$MigrateToPathPlanImpl) then,
  ) = __$$MigrateToPathPlanImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({List<MigrateCandidate> candidates, List<MigrateUnsafe> unsafe});
}

/// @nodoc
class __$$MigrateToPathPlanImplCopyWithImpl<$Res>
    extends _$MigrateToPathPlanCopyWithImpl<$Res, _$MigrateToPathPlanImpl>
    implements _$$MigrateToPathPlanImplCopyWith<$Res> {
  __$$MigrateToPathPlanImplCopyWithImpl(
    _$MigrateToPathPlanImpl _value,
    $Res Function(_$MigrateToPathPlanImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of MigrateToPathPlan
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? candidates = null, Object? unsafe = null}) {
    return _then(
      _$MigrateToPathPlanImpl(
        candidates: null == candidates
            ? _value._candidates
            : candidates // ignore: cast_nullable_to_non_nullable
                  as List<MigrateCandidate>,
        unsafe: null == unsafe
            ? _value._unsafe
            : unsafe // ignore: cast_nullable_to_non_nullable
                  as List<MigrateUnsafe>,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$MigrateToPathPlanImpl implements _MigrateToPathPlan {
  const _$MigrateToPathPlanImpl({
    final List<MigrateCandidate> candidates = const <MigrateCandidate>[],
    final List<MigrateUnsafe> unsafe = const <MigrateUnsafe>[],
  }) : _candidates = candidates,
       _unsafe = unsafe;

  factory _$MigrateToPathPlanImpl.fromJson(Map<String, dynamic> json) =>
      _$$MigrateToPathPlanImplFromJson(json);

  final List<MigrateCandidate> _candidates;
  @override
  @JsonKey()
  List<MigrateCandidate> get candidates {
    if (_candidates is EqualUnmodifiableListView) return _candidates;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_candidates);
  }

  final List<MigrateUnsafe> _unsafe;
  @override
  @JsonKey()
  List<MigrateUnsafe> get unsafe {
    if (_unsafe is EqualUnmodifiableListView) return _unsafe;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableListView(_unsafe);
  }

  @override
  String toString() {
    return 'MigrateToPathPlan(candidates: $candidates, unsafe: $unsafe)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$MigrateToPathPlanImpl &&
            const DeepCollectionEquality().equals(
              other._candidates,
              _candidates,
            ) &&
            const DeepCollectionEquality().equals(other._unsafe, _unsafe));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    const DeepCollectionEquality().hash(_candidates),
    const DeepCollectionEquality().hash(_unsafe),
  );

  /// Create a copy of MigrateToPathPlan
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$MigrateToPathPlanImplCopyWith<_$MigrateToPathPlanImpl> get copyWith =>
      __$$MigrateToPathPlanImplCopyWithImpl<_$MigrateToPathPlanImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$MigrateToPathPlanImplToJson(this);
  }
}

abstract class _MigrateToPathPlan implements MigrateToPathPlan {
  const factory _MigrateToPathPlan({
    final List<MigrateCandidate> candidates,
    final List<MigrateUnsafe> unsafe,
  }) = _$MigrateToPathPlanImpl;

  factory _MigrateToPathPlan.fromJson(Map<String, dynamic> json) =
      _$MigrateToPathPlanImpl.fromJson;

  @override
  List<MigrateCandidate> get candidates;
  @override
  List<MigrateUnsafe> get unsafe;

  /// Create a copy of MigrateToPathPlan
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$MigrateToPathPlanImplCopyWith<_$MigrateToPathPlanImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

MigrateCandidate _$MigrateCandidateFromJson(Map<String, dynamic> json) {
  return _MigrateCandidate.fromJson(json);
}

/// @nodoc
mixin _$MigrateCandidate {
  String get toolId => throw _privateConstructorUsedError;
  int get version => throw _privateConstructorUsedError;
  String get current => throw _privateConstructorUsedError;
  String get rewritten => throw _privateConstructorUsedError;

  /// Serializes this MigrateCandidate to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of MigrateCandidate
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $MigrateCandidateCopyWith<MigrateCandidate> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $MigrateCandidateCopyWith<$Res> {
  factory $MigrateCandidateCopyWith(
    MigrateCandidate value,
    $Res Function(MigrateCandidate) then,
  ) = _$MigrateCandidateCopyWithImpl<$Res, MigrateCandidate>;
  @useResult
  $Res call({String toolId, int version, String current, String rewritten});
}

/// @nodoc
class _$MigrateCandidateCopyWithImpl<$Res, $Val extends MigrateCandidate>
    implements $MigrateCandidateCopyWith<$Res> {
  _$MigrateCandidateCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of MigrateCandidate
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? toolId = null,
    Object? version = null,
    Object? current = null,
    Object? rewritten = null,
  }) {
    return _then(
      _value.copyWith(
            toolId: null == toolId
                ? _value.toolId
                : toolId // ignore: cast_nullable_to_non_nullable
                      as String,
            version: null == version
                ? _value.version
                : version // ignore: cast_nullable_to_non_nullable
                      as int,
            current: null == current
                ? _value.current
                : current // ignore: cast_nullable_to_non_nullable
                      as String,
            rewritten: null == rewritten
                ? _value.rewritten
                : rewritten // ignore: cast_nullable_to_non_nullable
                      as String,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$MigrateCandidateImplCopyWith<$Res>
    implements $MigrateCandidateCopyWith<$Res> {
  factory _$$MigrateCandidateImplCopyWith(
    _$MigrateCandidateImpl value,
    $Res Function(_$MigrateCandidateImpl) then,
  ) = __$$MigrateCandidateImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({String toolId, int version, String current, String rewritten});
}

/// @nodoc
class __$$MigrateCandidateImplCopyWithImpl<$Res>
    extends _$MigrateCandidateCopyWithImpl<$Res, _$MigrateCandidateImpl>
    implements _$$MigrateCandidateImplCopyWith<$Res> {
  __$$MigrateCandidateImplCopyWithImpl(
    _$MigrateCandidateImpl _value,
    $Res Function(_$MigrateCandidateImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of MigrateCandidate
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? toolId = null,
    Object? version = null,
    Object? current = null,
    Object? rewritten = null,
  }) {
    return _then(
      _$MigrateCandidateImpl(
        toolId: null == toolId
            ? _value.toolId
            : toolId // ignore: cast_nullable_to_non_nullable
                  as String,
        version: null == version
            ? _value.version
            : version // ignore: cast_nullable_to_non_nullable
                  as int,
        current: null == current
            ? _value.current
            : current // ignore: cast_nullable_to_non_nullable
                  as String,
        rewritten: null == rewritten
            ? _value.rewritten
            : rewritten // ignore: cast_nullable_to_non_nullable
                  as String,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$MigrateCandidateImpl implements _MigrateCandidate {
  const _$MigrateCandidateImpl({
    required this.toolId,
    required this.version,
    required this.current,
    required this.rewritten,
  });

  factory _$MigrateCandidateImpl.fromJson(Map<String, dynamic> json) =>
      _$$MigrateCandidateImplFromJson(json);

  @override
  final String toolId;
  @override
  final int version;
  @override
  final String current;
  @override
  final String rewritten;

  @override
  String toString() {
    return 'MigrateCandidate(toolId: $toolId, version: $version, current: $current, rewritten: $rewritten)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$MigrateCandidateImpl &&
            (identical(other.toolId, toolId) || other.toolId == toolId) &&
            (identical(other.version, version) || other.version == version) &&
            (identical(other.current, current) || other.current == current) &&
            (identical(other.rewritten, rewritten) ||
                other.rewritten == rewritten));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode =>
      Object.hash(runtimeType, toolId, version, current, rewritten);

  /// Create a copy of MigrateCandidate
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$MigrateCandidateImplCopyWith<_$MigrateCandidateImpl> get copyWith =>
      __$$MigrateCandidateImplCopyWithImpl<_$MigrateCandidateImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$MigrateCandidateImplToJson(this);
  }
}

abstract class _MigrateCandidate implements MigrateCandidate {
  const factory _MigrateCandidate({
    required final String toolId,
    required final int version,
    required final String current,
    required final String rewritten,
  }) = _$MigrateCandidateImpl;

  factory _MigrateCandidate.fromJson(Map<String, dynamic> json) =
      _$MigrateCandidateImpl.fromJson;

  @override
  String get toolId;
  @override
  int get version;
  @override
  String get current;
  @override
  String get rewritten;

  /// Create a copy of MigrateCandidate
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$MigrateCandidateImplCopyWith<_$MigrateCandidateImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

MigrateUnsafe _$MigrateUnsafeFromJson(Map<String, dynamic> json) {
  return _MigrateUnsafe.fromJson(json);
}

/// @nodoc
mixin _$MigrateUnsafe {
  String get toolId => throw _privateConstructorUsedError;
  int get version => throw _privateConstructorUsedError;
  String get current => throw _privateConstructorUsedError;
  String get reason => throw _privateConstructorUsedError;

  /// Serializes this MigrateUnsafe to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of MigrateUnsafe
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $MigrateUnsafeCopyWith<MigrateUnsafe> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $MigrateUnsafeCopyWith<$Res> {
  factory $MigrateUnsafeCopyWith(
    MigrateUnsafe value,
    $Res Function(MigrateUnsafe) then,
  ) = _$MigrateUnsafeCopyWithImpl<$Res, MigrateUnsafe>;
  @useResult
  $Res call({String toolId, int version, String current, String reason});
}

/// @nodoc
class _$MigrateUnsafeCopyWithImpl<$Res, $Val extends MigrateUnsafe>
    implements $MigrateUnsafeCopyWith<$Res> {
  _$MigrateUnsafeCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of MigrateUnsafe
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? toolId = null,
    Object? version = null,
    Object? current = null,
    Object? reason = null,
  }) {
    return _then(
      _value.copyWith(
            toolId: null == toolId
                ? _value.toolId
                : toolId // ignore: cast_nullable_to_non_nullable
                      as String,
            version: null == version
                ? _value.version
                : version // ignore: cast_nullable_to_non_nullable
                      as int,
            current: null == current
                ? _value.current
                : current // ignore: cast_nullable_to_non_nullable
                      as String,
            reason: null == reason
                ? _value.reason
                : reason // ignore: cast_nullable_to_non_nullable
                      as String,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$MigrateUnsafeImplCopyWith<$Res>
    implements $MigrateUnsafeCopyWith<$Res> {
  factory _$$MigrateUnsafeImplCopyWith(
    _$MigrateUnsafeImpl value,
    $Res Function(_$MigrateUnsafeImpl) then,
  ) = __$$MigrateUnsafeImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({String toolId, int version, String current, String reason});
}

/// @nodoc
class __$$MigrateUnsafeImplCopyWithImpl<$Res>
    extends _$MigrateUnsafeCopyWithImpl<$Res, _$MigrateUnsafeImpl>
    implements _$$MigrateUnsafeImplCopyWith<$Res> {
  __$$MigrateUnsafeImplCopyWithImpl(
    _$MigrateUnsafeImpl _value,
    $Res Function(_$MigrateUnsafeImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of MigrateUnsafe
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? toolId = null,
    Object? version = null,
    Object? current = null,
    Object? reason = null,
  }) {
    return _then(
      _$MigrateUnsafeImpl(
        toolId: null == toolId
            ? _value.toolId
            : toolId // ignore: cast_nullable_to_non_nullable
                  as String,
        version: null == version
            ? _value.version
            : version // ignore: cast_nullable_to_non_nullable
                  as int,
        current: null == current
            ? _value.current
            : current // ignore: cast_nullable_to_non_nullable
                  as String,
        reason: null == reason
            ? _value.reason
            : reason // ignore: cast_nullable_to_non_nullable
                  as String,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$MigrateUnsafeImpl implements _MigrateUnsafe {
  const _$MigrateUnsafeImpl({
    required this.toolId,
    required this.version,
    required this.current,
    required this.reason,
  });

  factory _$MigrateUnsafeImpl.fromJson(Map<String, dynamic> json) =>
      _$$MigrateUnsafeImplFromJson(json);

  @override
  final String toolId;
  @override
  final int version;
  @override
  final String current;
  @override
  final String reason;

  @override
  String toString() {
    return 'MigrateUnsafe(toolId: $toolId, version: $version, current: $current, reason: $reason)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$MigrateUnsafeImpl &&
            (identical(other.toolId, toolId) || other.toolId == toolId) &&
            (identical(other.version, version) || other.version == version) &&
            (identical(other.current, current) || other.current == current) &&
            (identical(other.reason, reason) || other.reason == reason));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode =>
      Object.hash(runtimeType, toolId, version, current, reason);

  /// Create a copy of MigrateUnsafe
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$MigrateUnsafeImplCopyWith<_$MigrateUnsafeImpl> get copyWith =>
      __$$MigrateUnsafeImplCopyWithImpl<_$MigrateUnsafeImpl>(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$MigrateUnsafeImplToJson(this);
  }
}

abstract class _MigrateUnsafe implements MigrateUnsafe {
  const factory _MigrateUnsafe({
    required final String toolId,
    required final int version,
    required final String current,
    required final String reason,
  }) = _$MigrateUnsafeImpl;

  factory _MigrateUnsafe.fromJson(Map<String, dynamic> json) =
      _$MigrateUnsafeImpl.fromJson;

  @override
  String get toolId;
  @override
  int get version;
  @override
  String get current;
  @override
  String get reason;

  /// Create a copy of MigrateUnsafe
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$MigrateUnsafeImplCopyWith<_$MigrateUnsafeImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

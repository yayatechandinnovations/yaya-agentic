// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'invitation.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

Invitation _$InvitationFromJson(Map<String, dynamic> json) {
  return _Invitation.fromJson(json);
}

/// @nodoc
mixin _$Invitation {
  String get id => throw _privateConstructorUsedError;
  String get workspaceId => throw _privateConstructorUsedError;
  String get email => throw _privateConstructorUsedError;
  String get role => throw _privateConstructorUsedError;
  String? get invitedBy => throw _privateConstructorUsedError;
  String? get expiresAt => throw _privateConstructorUsedError;
  String? get createdAt => throw _privateConstructorUsedError;
  String? get consumedAt => throw _privateConstructorUsedError;

  /// Serializes this Invitation to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of Invitation
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $InvitationCopyWith<Invitation> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $InvitationCopyWith<$Res> {
  factory $InvitationCopyWith(
    Invitation value,
    $Res Function(Invitation) then,
  ) = _$InvitationCopyWithImpl<$Res, Invitation>;
  @useResult
  $Res call({
    String id,
    String workspaceId,
    String email,
    String role,
    String? invitedBy,
    String? expiresAt,
    String? createdAt,
    String? consumedAt,
  });
}

/// @nodoc
class _$InvitationCopyWithImpl<$Res, $Val extends Invitation>
    implements $InvitationCopyWith<$Res> {
  _$InvitationCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of Invitation
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? workspaceId = null,
    Object? email = null,
    Object? role = null,
    Object? invitedBy = freezed,
    Object? expiresAt = freezed,
    Object? createdAt = freezed,
    Object? consumedAt = freezed,
  }) {
    return _then(
      _value.copyWith(
            id: null == id
                ? _value.id
                : id // ignore: cast_nullable_to_non_nullable
                      as String,
            workspaceId: null == workspaceId
                ? _value.workspaceId
                : workspaceId // ignore: cast_nullable_to_non_nullable
                      as String,
            email: null == email
                ? _value.email
                : email // ignore: cast_nullable_to_non_nullable
                      as String,
            role: null == role
                ? _value.role
                : role // ignore: cast_nullable_to_non_nullable
                      as String,
            invitedBy: freezed == invitedBy
                ? _value.invitedBy
                : invitedBy // ignore: cast_nullable_to_non_nullable
                      as String?,
            expiresAt: freezed == expiresAt
                ? _value.expiresAt
                : expiresAt // ignore: cast_nullable_to_non_nullable
                      as String?,
            createdAt: freezed == createdAt
                ? _value.createdAt
                : createdAt // ignore: cast_nullable_to_non_nullable
                      as String?,
            consumedAt: freezed == consumedAt
                ? _value.consumedAt
                : consumedAt // ignore: cast_nullable_to_non_nullable
                      as String?,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$InvitationImplCopyWith<$Res>
    implements $InvitationCopyWith<$Res> {
  factory _$$InvitationImplCopyWith(
    _$InvitationImpl value,
    $Res Function(_$InvitationImpl) then,
  ) = __$$InvitationImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String id,
    String workspaceId,
    String email,
    String role,
    String? invitedBy,
    String? expiresAt,
    String? createdAt,
    String? consumedAt,
  });
}

/// @nodoc
class __$$InvitationImplCopyWithImpl<$Res>
    extends _$InvitationCopyWithImpl<$Res, _$InvitationImpl>
    implements _$$InvitationImplCopyWith<$Res> {
  __$$InvitationImplCopyWithImpl(
    _$InvitationImpl _value,
    $Res Function(_$InvitationImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of Invitation
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? workspaceId = null,
    Object? email = null,
    Object? role = null,
    Object? invitedBy = freezed,
    Object? expiresAt = freezed,
    Object? createdAt = freezed,
    Object? consumedAt = freezed,
  }) {
    return _then(
      _$InvitationImpl(
        id: null == id
            ? _value.id
            : id // ignore: cast_nullable_to_non_nullable
                  as String,
        workspaceId: null == workspaceId
            ? _value.workspaceId
            : workspaceId // ignore: cast_nullable_to_non_nullable
                  as String,
        email: null == email
            ? _value.email
            : email // ignore: cast_nullable_to_non_nullable
                  as String,
        role: null == role
            ? _value.role
            : role // ignore: cast_nullable_to_non_nullable
                  as String,
        invitedBy: freezed == invitedBy
            ? _value.invitedBy
            : invitedBy // ignore: cast_nullable_to_non_nullable
                  as String?,
        expiresAt: freezed == expiresAt
            ? _value.expiresAt
            : expiresAt // ignore: cast_nullable_to_non_nullable
                  as String?,
        createdAt: freezed == createdAt
            ? _value.createdAt
            : createdAt // ignore: cast_nullable_to_non_nullable
                  as String?,
        consumedAt: freezed == consumedAt
            ? _value.consumedAt
            : consumedAt // ignore: cast_nullable_to_non_nullable
                  as String?,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$InvitationImpl implements _Invitation {
  const _$InvitationImpl({
    required this.id,
    required this.workspaceId,
    required this.email,
    required this.role,
    this.invitedBy,
    this.expiresAt,
    this.createdAt,
    this.consumedAt,
  });

  factory _$InvitationImpl.fromJson(Map<String, dynamic> json) =>
      _$$InvitationImplFromJson(json);

  @override
  final String id;
  @override
  final String workspaceId;
  @override
  final String email;
  @override
  final String role;
  @override
  final String? invitedBy;
  @override
  final String? expiresAt;
  @override
  final String? createdAt;
  @override
  final String? consumedAt;

  @override
  String toString() {
    return 'Invitation(id: $id, workspaceId: $workspaceId, email: $email, role: $role, invitedBy: $invitedBy, expiresAt: $expiresAt, createdAt: $createdAt, consumedAt: $consumedAt)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$InvitationImpl &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.workspaceId, workspaceId) ||
                other.workspaceId == workspaceId) &&
            (identical(other.email, email) || other.email == email) &&
            (identical(other.role, role) || other.role == role) &&
            (identical(other.invitedBy, invitedBy) ||
                other.invitedBy == invitedBy) &&
            (identical(other.expiresAt, expiresAt) ||
                other.expiresAt == expiresAt) &&
            (identical(other.createdAt, createdAt) ||
                other.createdAt == createdAt) &&
            (identical(other.consumedAt, consumedAt) ||
                other.consumedAt == consumedAt));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    id,
    workspaceId,
    email,
    role,
    invitedBy,
    expiresAt,
    createdAt,
    consumedAt,
  );

  /// Create a copy of Invitation
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$InvitationImplCopyWith<_$InvitationImpl> get copyWith =>
      __$$InvitationImplCopyWithImpl<_$InvitationImpl>(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$InvitationImplToJson(this);
  }
}

abstract class _Invitation implements Invitation {
  const factory _Invitation({
    required final String id,
    required final String workspaceId,
    required final String email,
    required final String role,
    final String? invitedBy,
    final String? expiresAt,
    final String? createdAt,
    final String? consumedAt,
  }) = _$InvitationImpl;

  factory _Invitation.fromJson(Map<String, dynamic> json) =
      _$InvitationImpl.fromJson;

  @override
  String get id;
  @override
  String get workspaceId;
  @override
  String get email;
  @override
  String get role;
  @override
  String? get invitedBy;
  @override
  String? get expiresAt;
  @override
  String? get createdAt;
  @override
  String? get consumedAt;

  /// Create a copy of Invitation
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$InvitationImplCopyWith<_$InvitationImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

InvitationCreateRequest _$InvitationCreateRequestFromJson(
  Map<String, dynamic> json,
) {
  return _InvitationCreateRequest.fromJson(json);
}

/// @nodoc
mixin _$InvitationCreateRequest {
  String get email => throw _privateConstructorUsedError;
  String get role => throw _privateConstructorUsedError;

  /// Serializes this InvitationCreateRequest to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of InvitationCreateRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $InvitationCreateRequestCopyWith<InvitationCreateRequest> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $InvitationCreateRequestCopyWith<$Res> {
  factory $InvitationCreateRequestCopyWith(
    InvitationCreateRequest value,
    $Res Function(InvitationCreateRequest) then,
  ) = _$InvitationCreateRequestCopyWithImpl<$Res, InvitationCreateRequest>;
  @useResult
  $Res call({String email, String role});
}

/// @nodoc
class _$InvitationCreateRequestCopyWithImpl<
  $Res,
  $Val extends InvitationCreateRequest
>
    implements $InvitationCreateRequestCopyWith<$Res> {
  _$InvitationCreateRequestCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of InvitationCreateRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? email = null, Object? role = null}) {
    return _then(
      _value.copyWith(
            email: null == email
                ? _value.email
                : email // ignore: cast_nullable_to_non_nullable
                      as String,
            role: null == role
                ? _value.role
                : role // ignore: cast_nullable_to_non_nullable
                      as String,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$InvitationCreateRequestImplCopyWith<$Res>
    implements $InvitationCreateRequestCopyWith<$Res> {
  factory _$$InvitationCreateRequestImplCopyWith(
    _$InvitationCreateRequestImpl value,
    $Res Function(_$InvitationCreateRequestImpl) then,
  ) = __$$InvitationCreateRequestImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({String email, String role});
}

/// @nodoc
class __$$InvitationCreateRequestImplCopyWithImpl<$Res>
    extends
        _$InvitationCreateRequestCopyWithImpl<
          $Res,
          _$InvitationCreateRequestImpl
        >
    implements _$$InvitationCreateRequestImplCopyWith<$Res> {
  __$$InvitationCreateRequestImplCopyWithImpl(
    _$InvitationCreateRequestImpl _value,
    $Res Function(_$InvitationCreateRequestImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of InvitationCreateRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? email = null, Object? role = null}) {
    return _then(
      _$InvitationCreateRequestImpl(
        email: null == email
            ? _value.email
            : email // ignore: cast_nullable_to_non_nullable
                  as String,
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
class _$InvitationCreateRequestImpl implements _InvitationCreateRequest {
  const _$InvitationCreateRequestImpl({
    required this.email,
    required this.role,
  });

  factory _$InvitationCreateRequestImpl.fromJson(Map<String, dynamic> json) =>
      _$$InvitationCreateRequestImplFromJson(json);

  @override
  final String email;
  @override
  final String role;

  @override
  String toString() {
    return 'InvitationCreateRequest(email: $email, role: $role)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$InvitationCreateRequestImpl &&
            (identical(other.email, email) || other.email == email) &&
            (identical(other.role, role) || other.role == role));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(runtimeType, email, role);

  /// Create a copy of InvitationCreateRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$InvitationCreateRequestImplCopyWith<_$InvitationCreateRequestImpl>
  get copyWith =>
      __$$InvitationCreateRequestImplCopyWithImpl<
        _$InvitationCreateRequestImpl
      >(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$InvitationCreateRequestImplToJson(this);
  }
}

abstract class _InvitationCreateRequest implements InvitationCreateRequest {
  const factory _InvitationCreateRequest({
    required final String email,
    required final String role,
  }) = _$InvitationCreateRequestImpl;

  factory _InvitationCreateRequest.fromJson(Map<String, dynamic> json) =
      _$InvitationCreateRequestImpl.fromJson;

  @override
  String get email;
  @override
  String get role;

  /// Create a copy of InvitationCreateRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$InvitationCreateRequestImplCopyWith<_$InvitationCreateRequestImpl>
  get copyWith => throw _privateConstructorUsedError;
}

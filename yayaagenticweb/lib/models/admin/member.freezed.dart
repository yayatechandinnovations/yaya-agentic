// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'member.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

Member _$MemberFromJson(Map<String, dynamic> json) {
  return _Member.fromJson(json);
}

/// @nodoc
mixin _$Member {
  String get userId => throw _privateConstructorUsedError;
  String? get email => throw _privateConstructorUsedError;
  String? get displayName => throw _privateConstructorUsedError;
  String get role => throw _privateConstructorUsedError;
  String? get addedAt => throw _privateConstructorUsedError;

  /// Serializes this Member to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of Member
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $MemberCopyWith<Member> get copyWith => throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $MemberCopyWith<$Res> {
  factory $MemberCopyWith(Member value, $Res Function(Member) then) =
      _$MemberCopyWithImpl<$Res, Member>;
  @useResult
  $Res call({
    String userId,
    String? email,
    String? displayName,
    String role,
    String? addedAt,
  });
}

/// @nodoc
class _$MemberCopyWithImpl<$Res, $Val extends Member>
    implements $MemberCopyWith<$Res> {
  _$MemberCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of Member
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? userId = null,
    Object? email = freezed,
    Object? displayName = freezed,
    Object? role = null,
    Object? addedAt = freezed,
  }) {
    return _then(
      _value.copyWith(
            userId: null == userId
                ? _value.userId
                : userId // ignore: cast_nullable_to_non_nullable
                      as String,
            email: freezed == email
                ? _value.email
                : email // ignore: cast_nullable_to_non_nullable
                      as String?,
            displayName: freezed == displayName
                ? _value.displayName
                : displayName // ignore: cast_nullable_to_non_nullable
                      as String?,
            role: null == role
                ? _value.role
                : role // ignore: cast_nullable_to_non_nullable
                      as String,
            addedAt: freezed == addedAt
                ? _value.addedAt
                : addedAt // ignore: cast_nullable_to_non_nullable
                      as String?,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$MemberImplCopyWith<$Res> implements $MemberCopyWith<$Res> {
  factory _$$MemberImplCopyWith(
    _$MemberImpl value,
    $Res Function(_$MemberImpl) then,
  ) = __$$MemberImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String userId,
    String? email,
    String? displayName,
    String role,
    String? addedAt,
  });
}

/// @nodoc
class __$$MemberImplCopyWithImpl<$Res>
    extends _$MemberCopyWithImpl<$Res, _$MemberImpl>
    implements _$$MemberImplCopyWith<$Res> {
  __$$MemberImplCopyWithImpl(
    _$MemberImpl _value,
    $Res Function(_$MemberImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of Member
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? userId = null,
    Object? email = freezed,
    Object? displayName = freezed,
    Object? role = null,
    Object? addedAt = freezed,
  }) {
    return _then(
      _$MemberImpl(
        userId: null == userId
            ? _value.userId
            : userId // ignore: cast_nullable_to_non_nullable
                  as String,
        email: freezed == email
            ? _value.email
            : email // ignore: cast_nullable_to_non_nullable
                  as String?,
        displayName: freezed == displayName
            ? _value.displayName
            : displayName // ignore: cast_nullable_to_non_nullable
                  as String?,
        role: null == role
            ? _value.role
            : role // ignore: cast_nullable_to_non_nullable
                  as String,
        addedAt: freezed == addedAt
            ? _value.addedAt
            : addedAt // ignore: cast_nullable_to_non_nullable
                  as String?,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$MemberImpl implements _Member {
  const _$MemberImpl({
    required this.userId,
    this.email,
    this.displayName,
    required this.role,
    this.addedAt,
  });

  factory _$MemberImpl.fromJson(Map<String, dynamic> json) =>
      _$$MemberImplFromJson(json);

  @override
  final String userId;
  @override
  final String? email;
  @override
  final String? displayName;
  @override
  final String role;
  @override
  final String? addedAt;

  @override
  String toString() {
    return 'Member(userId: $userId, email: $email, displayName: $displayName, role: $role, addedAt: $addedAt)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$MemberImpl &&
            (identical(other.userId, userId) || other.userId == userId) &&
            (identical(other.email, email) || other.email == email) &&
            (identical(other.displayName, displayName) ||
                other.displayName == displayName) &&
            (identical(other.role, role) || other.role == role) &&
            (identical(other.addedAt, addedAt) || other.addedAt == addedAt));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode =>
      Object.hash(runtimeType, userId, email, displayName, role, addedAt);

  /// Create a copy of Member
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$MemberImplCopyWith<_$MemberImpl> get copyWith =>
      __$$MemberImplCopyWithImpl<_$MemberImpl>(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$MemberImplToJson(this);
  }
}

abstract class _Member implements Member {
  const factory _Member({
    required final String userId,
    final String? email,
    final String? displayName,
    required final String role,
    final String? addedAt,
  }) = _$MemberImpl;

  factory _Member.fromJson(Map<String, dynamic> json) = _$MemberImpl.fromJson;

  @override
  String get userId;
  @override
  String? get email;
  @override
  String? get displayName;
  @override
  String get role;
  @override
  String? get addedAt;

  /// Create a copy of Member
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$MemberImplCopyWith<_$MemberImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

MemberRoleChangeRequest _$MemberRoleChangeRequestFromJson(
  Map<String, dynamic> json,
) {
  return _MemberRoleChangeRequest.fromJson(json);
}

/// @nodoc
mixin _$MemberRoleChangeRequest {
  String get role => throw _privateConstructorUsedError;

  /// Serializes this MemberRoleChangeRequest to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of MemberRoleChangeRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $MemberRoleChangeRequestCopyWith<MemberRoleChangeRequest> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $MemberRoleChangeRequestCopyWith<$Res> {
  factory $MemberRoleChangeRequestCopyWith(
    MemberRoleChangeRequest value,
    $Res Function(MemberRoleChangeRequest) then,
  ) = _$MemberRoleChangeRequestCopyWithImpl<$Res, MemberRoleChangeRequest>;
  @useResult
  $Res call({String role});
}

/// @nodoc
class _$MemberRoleChangeRequestCopyWithImpl<
  $Res,
  $Val extends MemberRoleChangeRequest
>
    implements $MemberRoleChangeRequestCopyWith<$Res> {
  _$MemberRoleChangeRequestCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of MemberRoleChangeRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? role = null}) {
    return _then(
      _value.copyWith(
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
abstract class _$$MemberRoleChangeRequestImplCopyWith<$Res>
    implements $MemberRoleChangeRequestCopyWith<$Res> {
  factory _$$MemberRoleChangeRequestImplCopyWith(
    _$MemberRoleChangeRequestImpl value,
    $Res Function(_$MemberRoleChangeRequestImpl) then,
  ) = __$$MemberRoleChangeRequestImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({String role});
}

/// @nodoc
class __$$MemberRoleChangeRequestImplCopyWithImpl<$Res>
    extends
        _$MemberRoleChangeRequestCopyWithImpl<
          $Res,
          _$MemberRoleChangeRequestImpl
        >
    implements _$$MemberRoleChangeRequestImplCopyWith<$Res> {
  __$$MemberRoleChangeRequestImplCopyWithImpl(
    _$MemberRoleChangeRequestImpl _value,
    $Res Function(_$MemberRoleChangeRequestImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of MemberRoleChangeRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? role = null}) {
    return _then(
      _$MemberRoleChangeRequestImpl(
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
class _$MemberRoleChangeRequestImpl implements _MemberRoleChangeRequest {
  const _$MemberRoleChangeRequestImpl({required this.role});

  factory _$MemberRoleChangeRequestImpl.fromJson(Map<String, dynamic> json) =>
      _$$MemberRoleChangeRequestImplFromJson(json);

  @override
  final String role;

  @override
  String toString() {
    return 'MemberRoleChangeRequest(role: $role)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$MemberRoleChangeRequestImpl &&
            (identical(other.role, role) || other.role == role));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(runtimeType, role);

  /// Create a copy of MemberRoleChangeRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$MemberRoleChangeRequestImplCopyWith<_$MemberRoleChangeRequestImpl>
  get copyWith =>
      __$$MemberRoleChangeRequestImplCopyWithImpl<
        _$MemberRoleChangeRequestImpl
      >(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$MemberRoleChangeRequestImplToJson(this);
  }
}

abstract class _MemberRoleChangeRequest implements MemberRoleChangeRequest {
  const factory _MemberRoleChangeRequest({required final String role}) =
      _$MemberRoleChangeRequestImpl;

  factory _MemberRoleChangeRequest.fromJson(Map<String, dynamic> json) =
      _$MemberRoleChangeRequestImpl.fromJson;

  @override
  String get role;

  /// Create a copy of MemberRoleChangeRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$MemberRoleChangeRequestImplCopyWith<_$MemberRoleChangeRequestImpl>
  get copyWith => throw _privateConstructorUsedError;
}

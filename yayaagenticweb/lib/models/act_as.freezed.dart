// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'act_as.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

ActAs _$ActAsFromJson(Map<String, dynamic> json) {
  return ActAsRawToken.fromJson(json);
}

/// @nodoc
mixin _$ActAs {
  String get kind => throw _privateConstructorUsedError;
  String get scheme => throw _privateConstructorUsedError;
  String get token => throw _privateConstructorUsedError;
  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(String kind, String scheme, String token)
    rawToken,
  }) => throw _privateConstructorUsedError;
  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(String kind, String scheme, String token)? rawToken,
  }) => throw _privateConstructorUsedError;
  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(String kind, String scheme, String token)? rawToken,
    required TResult orElse(),
  }) => throw _privateConstructorUsedError;
  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(ActAsRawToken value) rawToken,
  }) => throw _privateConstructorUsedError;
  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(ActAsRawToken value)? rawToken,
  }) => throw _privateConstructorUsedError;
  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(ActAsRawToken value)? rawToken,
    required TResult orElse(),
  }) => throw _privateConstructorUsedError;

  /// Serializes this ActAs to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of ActAs
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $ActAsCopyWith<ActAs> get copyWith => throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $ActAsCopyWith<$Res> {
  factory $ActAsCopyWith(ActAs value, $Res Function(ActAs) then) =
      _$ActAsCopyWithImpl<$Res, ActAs>;
  @useResult
  $Res call({String kind, String scheme, String token});
}

/// @nodoc
class _$ActAsCopyWithImpl<$Res, $Val extends ActAs>
    implements $ActAsCopyWith<$Res> {
  _$ActAsCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of ActAs
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? kind = null,
    Object? scheme = null,
    Object? token = null,
  }) {
    return _then(
      _value.copyWith(
            kind: null == kind
                ? _value.kind
                : kind // ignore: cast_nullable_to_non_nullable
                      as String,
            scheme: null == scheme
                ? _value.scheme
                : scheme // ignore: cast_nullable_to_non_nullable
                      as String,
            token: null == token
                ? _value.token
                : token // ignore: cast_nullable_to_non_nullable
                      as String,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$ActAsRawTokenImplCopyWith<$Res>
    implements $ActAsCopyWith<$Res> {
  factory _$$ActAsRawTokenImplCopyWith(
    _$ActAsRawTokenImpl value,
    $Res Function(_$ActAsRawTokenImpl) then,
  ) = __$$ActAsRawTokenImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({String kind, String scheme, String token});
}

/// @nodoc
class __$$ActAsRawTokenImplCopyWithImpl<$Res>
    extends _$ActAsCopyWithImpl<$Res, _$ActAsRawTokenImpl>
    implements _$$ActAsRawTokenImplCopyWith<$Res> {
  __$$ActAsRawTokenImplCopyWithImpl(
    _$ActAsRawTokenImpl _value,
    $Res Function(_$ActAsRawTokenImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of ActAs
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? kind = null,
    Object? scheme = null,
    Object? token = null,
  }) {
    return _then(
      _$ActAsRawTokenImpl(
        kind: null == kind
            ? _value.kind
            : kind // ignore: cast_nullable_to_non_nullable
                  as String,
        scheme: null == scheme
            ? _value.scheme
            : scheme // ignore: cast_nullable_to_non_nullable
                  as String,
        token: null == token
            ? _value.token
            : token // ignore: cast_nullable_to_non_nullable
                  as String,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$ActAsRawTokenImpl implements ActAsRawToken {
  const _$ActAsRawTokenImpl({
    this.kind = 'raw-token',
    this.scheme = 'Bearer',
    required this.token,
  });

  factory _$ActAsRawTokenImpl.fromJson(Map<String, dynamic> json) =>
      _$$ActAsRawTokenImplFromJson(json);

  @override
  @JsonKey()
  final String kind;
  @override
  @JsonKey()
  final String scheme;
  @override
  final String token;

  @override
  String toString() {
    return 'ActAs.rawToken(kind: $kind, scheme: $scheme, token: $token)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$ActAsRawTokenImpl &&
            (identical(other.kind, kind) || other.kind == kind) &&
            (identical(other.scheme, scheme) || other.scheme == scheme) &&
            (identical(other.token, token) || other.token == token));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(runtimeType, kind, scheme, token);

  /// Create a copy of ActAs
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$ActAsRawTokenImplCopyWith<_$ActAsRawTokenImpl> get copyWith =>
      __$$ActAsRawTokenImplCopyWithImpl<_$ActAsRawTokenImpl>(this, _$identity);

  @override
  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(String kind, String scheme, String token)
    rawToken,
  }) {
    return rawToken(kind, scheme, token);
  }

  @override
  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(String kind, String scheme, String token)? rawToken,
  }) {
    return rawToken?.call(kind, scheme, token);
  }

  @override
  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(String kind, String scheme, String token)? rawToken,
    required TResult orElse(),
  }) {
    if (rawToken != null) {
      return rawToken(kind, scheme, token);
    }
    return orElse();
  }

  @override
  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(ActAsRawToken value) rawToken,
  }) {
    return rawToken(this);
  }

  @override
  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(ActAsRawToken value)? rawToken,
  }) {
    return rawToken?.call(this);
  }

  @override
  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(ActAsRawToken value)? rawToken,
    required TResult orElse(),
  }) {
    if (rawToken != null) {
      return rawToken(this);
    }
    return orElse();
  }

  @override
  Map<String, dynamic> toJson() {
    return _$$ActAsRawTokenImplToJson(this);
  }
}

abstract class ActAsRawToken implements ActAs {
  const factory ActAsRawToken({
    final String kind,
    final String scheme,
    required final String token,
  }) = _$ActAsRawTokenImpl;

  factory ActAsRawToken.fromJson(Map<String, dynamic> json) =
      _$ActAsRawTokenImpl.fromJson;

  @override
  String get kind;
  @override
  String get scheme;
  @override
  String get token;

  /// Create a copy of ActAs
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$ActAsRawTokenImplCopyWith<_$ActAsRawTokenImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

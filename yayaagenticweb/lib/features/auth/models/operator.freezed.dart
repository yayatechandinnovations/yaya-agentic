// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'operator.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

Operator _$OperatorFromJson(Map<String, dynamic> json) {
  return _Operator.fromJson(json);
}

/// @nodoc
mixin _$Operator {
  String get subject => throw _privateConstructorUsedError;
  String get displayName => throw _privateConstructorUsedError;
  String get source => throw _privateConstructorUsedError;
  Map<String, dynamic> get attributes => throw _privateConstructorUsedError;
  DateTime? get verifiedAt => throw _privateConstructorUsedError;

  /// Serializes this Operator to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of Operator
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $OperatorCopyWith<Operator> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $OperatorCopyWith<$Res> {
  factory $OperatorCopyWith(Operator value, $Res Function(Operator) then) =
      _$OperatorCopyWithImpl<$Res, Operator>;
  @useResult
  $Res call({
    String subject,
    String displayName,
    String source,
    Map<String, dynamic> attributes,
    DateTime? verifiedAt,
  });
}

/// @nodoc
class _$OperatorCopyWithImpl<$Res, $Val extends Operator>
    implements $OperatorCopyWith<$Res> {
  _$OperatorCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of Operator
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? subject = null,
    Object? displayName = null,
    Object? source = null,
    Object? attributes = null,
    Object? verifiedAt = freezed,
  }) {
    return _then(
      _value.copyWith(
            subject: null == subject
                ? _value.subject
                : subject // ignore: cast_nullable_to_non_nullable
                      as String,
            displayName: null == displayName
                ? _value.displayName
                : displayName // ignore: cast_nullable_to_non_nullable
                      as String,
            source: null == source
                ? _value.source
                : source // ignore: cast_nullable_to_non_nullable
                      as String,
            attributes: null == attributes
                ? _value.attributes
                : attributes // ignore: cast_nullable_to_non_nullable
                      as Map<String, dynamic>,
            verifiedAt: freezed == verifiedAt
                ? _value.verifiedAt
                : verifiedAt // ignore: cast_nullable_to_non_nullable
                      as DateTime?,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$OperatorImplCopyWith<$Res>
    implements $OperatorCopyWith<$Res> {
  factory _$$OperatorImplCopyWith(
    _$OperatorImpl value,
    $Res Function(_$OperatorImpl) then,
  ) = __$$OperatorImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String subject,
    String displayName,
    String source,
    Map<String, dynamic> attributes,
    DateTime? verifiedAt,
  });
}

/// @nodoc
class __$$OperatorImplCopyWithImpl<$Res>
    extends _$OperatorCopyWithImpl<$Res, _$OperatorImpl>
    implements _$$OperatorImplCopyWith<$Res> {
  __$$OperatorImplCopyWithImpl(
    _$OperatorImpl _value,
    $Res Function(_$OperatorImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of Operator
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? subject = null,
    Object? displayName = null,
    Object? source = null,
    Object? attributes = null,
    Object? verifiedAt = freezed,
  }) {
    return _then(
      _$OperatorImpl(
        subject: null == subject
            ? _value.subject
            : subject // ignore: cast_nullable_to_non_nullable
                  as String,
        displayName: null == displayName
            ? _value.displayName
            : displayName // ignore: cast_nullable_to_non_nullable
                  as String,
        source: null == source
            ? _value.source
            : source // ignore: cast_nullable_to_non_nullable
                  as String,
        attributes: null == attributes
            ? _value._attributes
            : attributes // ignore: cast_nullable_to_non_nullable
                  as Map<String, dynamic>,
        verifiedAt: freezed == verifiedAt
            ? _value.verifiedAt
            : verifiedAt // ignore: cast_nullable_to_non_nullable
                  as DateTime?,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$OperatorImpl implements _Operator {
  const _$OperatorImpl({
    required this.subject,
    required this.displayName,
    required this.source,
    final Map<String, dynamic> attributes = const <String, dynamic>{},
    this.verifiedAt,
  }) : _attributes = attributes;

  factory _$OperatorImpl.fromJson(Map<String, dynamic> json) =>
      _$$OperatorImplFromJson(json);

  @override
  final String subject;
  @override
  final String displayName;
  @override
  final String source;
  final Map<String, dynamic> _attributes;
  @override
  @JsonKey()
  Map<String, dynamic> get attributes {
    if (_attributes is EqualUnmodifiableMapView) return _attributes;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_attributes);
  }

  @override
  final DateTime? verifiedAt;

  @override
  String toString() {
    return 'Operator(subject: $subject, displayName: $displayName, source: $source, attributes: $attributes, verifiedAt: $verifiedAt)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$OperatorImpl &&
            (identical(other.subject, subject) || other.subject == subject) &&
            (identical(other.displayName, displayName) ||
                other.displayName == displayName) &&
            (identical(other.source, source) || other.source == source) &&
            const DeepCollectionEquality().equals(
              other._attributes,
              _attributes,
            ) &&
            (identical(other.verifiedAt, verifiedAt) ||
                other.verifiedAt == verifiedAt));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    subject,
    displayName,
    source,
    const DeepCollectionEquality().hash(_attributes),
    verifiedAt,
  );

  /// Create a copy of Operator
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$OperatorImplCopyWith<_$OperatorImpl> get copyWith =>
      __$$OperatorImplCopyWithImpl<_$OperatorImpl>(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$OperatorImplToJson(this);
  }
}

abstract class _Operator implements Operator {
  const factory _Operator({
    required final String subject,
    required final String displayName,
    required final String source,
    final Map<String, dynamic> attributes,
    final DateTime? verifiedAt,
  }) = _$OperatorImpl;

  factory _Operator.fromJson(Map<String, dynamic> json) =
      _$OperatorImpl.fromJson;

  @override
  String get subject;
  @override
  String get displayName;
  @override
  String get source;
  @override
  Map<String, dynamic> get attributes;
  @override
  DateTime? get verifiedAt;

  /// Create a copy of Operator
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$OperatorImplCopyWith<_$OperatorImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

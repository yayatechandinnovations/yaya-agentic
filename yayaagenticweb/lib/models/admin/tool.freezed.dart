// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'tool.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

HttpBodyDto _$HttpBodyDtoFromJson(Map<String, dynamic> json) {
  return _HttpBodyDto.fromJson(json);
}

/// @nodoc
mixin _$HttpBodyDto {
  String? get contentType => throw _privateConstructorUsedError;
  String? get template => throw _privateConstructorUsedError;

  /// Serializes this HttpBodyDto to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of HttpBodyDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $HttpBodyDtoCopyWith<HttpBodyDto> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $HttpBodyDtoCopyWith<$Res> {
  factory $HttpBodyDtoCopyWith(
    HttpBodyDto value,
    $Res Function(HttpBodyDto) then,
  ) = _$HttpBodyDtoCopyWithImpl<$Res, HttpBodyDto>;
  @useResult
  $Res call({String? contentType, String? template});
}

/// @nodoc
class _$HttpBodyDtoCopyWithImpl<$Res, $Val extends HttpBodyDto>
    implements $HttpBodyDtoCopyWith<$Res> {
  _$HttpBodyDtoCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of HttpBodyDto
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? contentType = freezed, Object? template = freezed}) {
    return _then(
      _value.copyWith(
            contentType: freezed == contentType
                ? _value.contentType
                : contentType // ignore: cast_nullable_to_non_nullable
                      as String?,
            template: freezed == template
                ? _value.template
                : template // ignore: cast_nullable_to_non_nullable
                      as String?,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$HttpBodyDtoImplCopyWith<$Res>
    implements $HttpBodyDtoCopyWith<$Res> {
  factory _$$HttpBodyDtoImplCopyWith(
    _$HttpBodyDtoImpl value,
    $Res Function(_$HttpBodyDtoImpl) then,
  ) = __$$HttpBodyDtoImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({String? contentType, String? template});
}

/// @nodoc
class __$$HttpBodyDtoImplCopyWithImpl<$Res>
    extends _$HttpBodyDtoCopyWithImpl<$Res, _$HttpBodyDtoImpl>
    implements _$$HttpBodyDtoImplCopyWith<$Res> {
  __$$HttpBodyDtoImplCopyWithImpl(
    _$HttpBodyDtoImpl _value,
    $Res Function(_$HttpBodyDtoImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of HttpBodyDto
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? contentType = freezed, Object? template = freezed}) {
    return _then(
      _$HttpBodyDtoImpl(
        contentType: freezed == contentType
            ? _value.contentType
            : contentType // ignore: cast_nullable_to_non_nullable
                  as String?,
        template: freezed == template
            ? _value.template
            : template // ignore: cast_nullable_to_non_nullable
                  as String?,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$HttpBodyDtoImpl implements _HttpBodyDto {
  const _$HttpBodyDtoImpl({this.contentType, this.template});

  factory _$HttpBodyDtoImpl.fromJson(Map<String, dynamic> json) =>
      _$$HttpBodyDtoImplFromJson(json);

  @override
  final String? contentType;
  @override
  final String? template;

  @override
  String toString() {
    return 'HttpBodyDto(contentType: $contentType, template: $template)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$HttpBodyDtoImpl &&
            (identical(other.contentType, contentType) ||
                other.contentType == contentType) &&
            (identical(other.template, template) ||
                other.template == template));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(runtimeType, contentType, template);

  /// Create a copy of HttpBodyDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$HttpBodyDtoImplCopyWith<_$HttpBodyDtoImpl> get copyWith =>
      __$$HttpBodyDtoImplCopyWithImpl<_$HttpBodyDtoImpl>(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$HttpBodyDtoImplToJson(this);
  }
}

abstract class _HttpBodyDto implements HttpBodyDto {
  const factory _HttpBodyDto({
    final String? contentType,
    final String? template,
  }) = _$HttpBodyDtoImpl;

  factory _HttpBodyDto.fromJson(Map<String, dynamic> json) =
      _$HttpBodyDtoImpl.fromJson;

  @override
  String? get contentType;
  @override
  String? get template;

  /// Create a copy of HttpBodyDto
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$HttpBodyDtoImplCopyWith<_$HttpBodyDtoImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

HttpResponseDto _$HttpResponseDtoFromJson(Map<String, dynamic> json) {
  return _HttpResponseDto.fromJson(json);
}

/// @nodoc
mixin _$HttpResponseDto {
  String? get jsonPath => throw _privateConstructorUsedError;
  Map<String, String> get headerOutputs => throw _privateConstructorUsedError;

  /// Serializes this HttpResponseDto to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of HttpResponseDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $HttpResponseDtoCopyWith<HttpResponseDto> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $HttpResponseDtoCopyWith<$Res> {
  factory $HttpResponseDtoCopyWith(
    HttpResponseDto value,
    $Res Function(HttpResponseDto) then,
  ) = _$HttpResponseDtoCopyWithImpl<$Res, HttpResponseDto>;
  @useResult
  $Res call({String? jsonPath, Map<String, String> headerOutputs});
}

/// @nodoc
class _$HttpResponseDtoCopyWithImpl<$Res, $Val extends HttpResponseDto>
    implements $HttpResponseDtoCopyWith<$Res> {
  _$HttpResponseDtoCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of HttpResponseDto
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? jsonPath = freezed, Object? headerOutputs = null}) {
    return _then(
      _value.copyWith(
            jsonPath: freezed == jsonPath
                ? _value.jsonPath
                : jsonPath // ignore: cast_nullable_to_non_nullable
                      as String?,
            headerOutputs: null == headerOutputs
                ? _value.headerOutputs
                : headerOutputs // ignore: cast_nullable_to_non_nullable
                      as Map<String, String>,
          )
          as $Val,
    );
  }
}

/// @nodoc
abstract class _$$HttpResponseDtoImplCopyWith<$Res>
    implements $HttpResponseDtoCopyWith<$Res> {
  factory _$$HttpResponseDtoImplCopyWith(
    _$HttpResponseDtoImpl value,
    $Res Function(_$HttpResponseDtoImpl) then,
  ) = __$$HttpResponseDtoImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({String? jsonPath, Map<String, String> headerOutputs});
}

/// @nodoc
class __$$HttpResponseDtoImplCopyWithImpl<$Res>
    extends _$HttpResponseDtoCopyWithImpl<$Res, _$HttpResponseDtoImpl>
    implements _$$HttpResponseDtoImplCopyWith<$Res> {
  __$$HttpResponseDtoImplCopyWithImpl(
    _$HttpResponseDtoImpl _value,
    $Res Function(_$HttpResponseDtoImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of HttpResponseDto
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? jsonPath = freezed, Object? headerOutputs = null}) {
    return _then(
      _$HttpResponseDtoImpl(
        jsonPath: freezed == jsonPath
            ? _value.jsonPath
            : jsonPath // ignore: cast_nullable_to_non_nullable
                  as String?,
        headerOutputs: null == headerOutputs
            ? _value._headerOutputs
            : headerOutputs // ignore: cast_nullable_to_non_nullable
                  as Map<String, String>,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$HttpResponseDtoImpl implements _HttpResponseDto {
  const _$HttpResponseDtoImpl({
    this.jsonPath,
    final Map<String, String> headerOutputs = const <String, String>{},
  }) : _headerOutputs = headerOutputs;

  factory _$HttpResponseDtoImpl.fromJson(Map<String, dynamic> json) =>
      _$$HttpResponseDtoImplFromJson(json);

  @override
  final String? jsonPath;
  final Map<String, String> _headerOutputs;
  @override
  @JsonKey()
  Map<String, String> get headerOutputs {
    if (_headerOutputs is EqualUnmodifiableMapView) return _headerOutputs;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_headerOutputs);
  }

  @override
  String toString() {
    return 'HttpResponseDto(jsonPath: $jsonPath, headerOutputs: $headerOutputs)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$HttpResponseDtoImpl &&
            (identical(other.jsonPath, jsonPath) ||
                other.jsonPath == jsonPath) &&
            const DeepCollectionEquality().equals(
              other._headerOutputs,
              _headerOutputs,
            ));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    jsonPath,
    const DeepCollectionEquality().hash(_headerOutputs),
  );

  /// Create a copy of HttpResponseDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$HttpResponseDtoImplCopyWith<_$HttpResponseDtoImpl> get copyWith =>
      __$$HttpResponseDtoImplCopyWithImpl<_$HttpResponseDtoImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$HttpResponseDtoImplToJson(this);
  }
}

abstract class _HttpResponseDto implements HttpResponseDto {
  const factory _HttpResponseDto({
    final String? jsonPath,
    final Map<String, String> headerOutputs,
  }) = _$HttpResponseDtoImpl;

  factory _HttpResponseDto.fromJson(Map<String, dynamic> json) =
      _$HttpResponseDtoImpl.fromJson;

  @override
  String? get jsonPath;
  @override
  Map<String, String> get headerOutputs;

  /// Create a copy of HttpResponseDto
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$HttpResponseDtoImplCopyWith<_$HttpResponseDtoImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

HttpHandlerDto _$HttpHandlerDtoFromJson(Map<String, dynamic> json) {
  return _HttpHandlerDto.fromJson(json);
}

/// @nodoc
mixin _$HttpHandlerDto {
  String get method => throw _privateConstructorUsedError;
  String get urlTemplate => throw _privateConstructorUsedError;
  Map<String, String> get headerTemplates => throw _privateConstructorUsedError;
  HttpBodyDto? get body => throw _privateConstructorUsedError;
  HttpResponseDto? get response => throw _privateConstructorUsedError;
  String get authForwarding => throw _privateConstructorUsedError;

  /// Serializes this HttpHandlerDto to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of HttpHandlerDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $HttpHandlerDtoCopyWith<HttpHandlerDto> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $HttpHandlerDtoCopyWith<$Res> {
  factory $HttpHandlerDtoCopyWith(
    HttpHandlerDto value,
    $Res Function(HttpHandlerDto) then,
  ) = _$HttpHandlerDtoCopyWithImpl<$Res, HttpHandlerDto>;
  @useResult
  $Res call({
    String method,
    String urlTemplate,
    Map<String, String> headerTemplates,
    HttpBodyDto? body,
    HttpResponseDto? response,
    String authForwarding,
  });

  $HttpBodyDtoCopyWith<$Res>? get body;
  $HttpResponseDtoCopyWith<$Res>? get response;
}

/// @nodoc
class _$HttpHandlerDtoCopyWithImpl<$Res, $Val extends HttpHandlerDto>
    implements $HttpHandlerDtoCopyWith<$Res> {
  _$HttpHandlerDtoCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of HttpHandlerDto
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? method = null,
    Object? urlTemplate = null,
    Object? headerTemplates = null,
    Object? body = freezed,
    Object? response = freezed,
    Object? authForwarding = null,
  }) {
    return _then(
      _value.copyWith(
            method: null == method
                ? _value.method
                : method // ignore: cast_nullable_to_non_nullable
                      as String,
            urlTemplate: null == urlTemplate
                ? _value.urlTemplate
                : urlTemplate // ignore: cast_nullable_to_non_nullable
                      as String,
            headerTemplates: null == headerTemplates
                ? _value.headerTemplates
                : headerTemplates // ignore: cast_nullable_to_non_nullable
                      as Map<String, String>,
            body: freezed == body
                ? _value.body
                : body // ignore: cast_nullable_to_non_nullable
                      as HttpBodyDto?,
            response: freezed == response
                ? _value.response
                : response // ignore: cast_nullable_to_non_nullable
                      as HttpResponseDto?,
            authForwarding: null == authForwarding
                ? _value.authForwarding
                : authForwarding // ignore: cast_nullable_to_non_nullable
                      as String,
          )
          as $Val,
    );
  }

  /// Create a copy of HttpHandlerDto
  /// with the given fields replaced by the non-null parameter values.
  @override
  @pragma('vm:prefer-inline')
  $HttpBodyDtoCopyWith<$Res>? get body {
    if (_value.body == null) {
      return null;
    }

    return $HttpBodyDtoCopyWith<$Res>(_value.body!, (value) {
      return _then(_value.copyWith(body: value) as $Val);
    });
  }

  /// Create a copy of HttpHandlerDto
  /// with the given fields replaced by the non-null parameter values.
  @override
  @pragma('vm:prefer-inline')
  $HttpResponseDtoCopyWith<$Res>? get response {
    if (_value.response == null) {
      return null;
    }

    return $HttpResponseDtoCopyWith<$Res>(_value.response!, (value) {
      return _then(_value.copyWith(response: value) as $Val);
    });
  }
}

/// @nodoc
abstract class _$$HttpHandlerDtoImplCopyWith<$Res>
    implements $HttpHandlerDtoCopyWith<$Res> {
  factory _$$HttpHandlerDtoImplCopyWith(
    _$HttpHandlerDtoImpl value,
    $Res Function(_$HttpHandlerDtoImpl) then,
  ) = __$$HttpHandlerDtoImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String method,
    String urlTemplate,
    Map<String, String> headerTemplates,
    HttpBodyDto? body,
    HttpResponseDto? response,
    String authForwarding,
  });

  @override
  $HttpBodyDtoCopyWith<$Res>? get body;
  @override
  $HttpResponseDtoCopyWith<$Res>? get response;
}

/// @nodoc
class __$$HttpHandlerDtoImplCopyWithImpl<$Res>
    extends _$HttpHandlerDtoCopyWithImpl<$Res, _$HttpHandlerDtoImpl>
    implements _$$HttpHandlerDtoImplCopyWith<$Res> {
  __$$HttpHandlerDtoImplCopyWithImpl(
    _$HttpHandlerDtoImpl _value,
    $Res Function(_$HttpHandlerDtoImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of HttpHandlerDto
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? method = null,
    Object? urlTemplate = null,
    Object? headerTemplates = null,
    Object? body = freezed,
    Object? response = freezed,
    Object? authForwarding = null,
  }) {
    return _then(
      _$HttpHandlerDtoImpl(
        method: null == method
            ? _value.method
            : method // ignore: cast_nullable_to_non_nullable
                  as String,
        urlTemplate: null == urlTemplate
            ? _value.urlTemplate
            : urlTemplate // ignore: cast_nullable_to_non_nullable
                  as String,
        headerTemplates: null == headerTemplates
            ? _value._headerTemplates
            : headerTemplates // ignore: cast_nullable_to_non_nullable
                  as Map<String, String>,
        body: freezed == body
            ? _value.body
            : body // ignore: cast_nullable_to_non_nullable
                  as HttpBodyDto?,
        response: freezed == response
            ? _value.response
            : response // ignore: cast_nullable_to_non_nullable
                  as HttpResponseDto?,
        authForwarding: null == authForwarding
            ? _value.authForwarding
            : authForwarding // ignore: cast_nullable_to_non_nullable
                  as String,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$HttpHandlerDtoImpl implements _HttpHandlerDto {
  const _$HttpHandlerDtoImpl({
    required this.method,
    required this.urlTemplate,
    final Map<String, String> headerTemplates = const <String, String>{},
    this.body,
    this.response,
    this.authForwarding = 'NONE',
  }) : _headerTemplates = headerTemplates;

  factory _$HttpHandlerDtoImpl.fromJson(Map<String, dynamic> json) =>
      _$$HttpHandlerDtoImplFromJson(json);

  @override
  final String method;
  @override
  final String urlTemplate;
  final Map<String, String> _headerTemplates;
  @override
  @JsonKey()
  Map<String, String> get headerTemplates {
    if (_headerTemplates is EqualUnmodifiableMapView) return _headerTemplates;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_headerTemplates);
  }

  @override
  final HttpBodyDto? body;
  @override
  final HttpResponseDto? response;
  @override
  @JsonKey()
  final String authForwarding;

  @override
  String toString() {
    return 'HttpHandlerDto(method: $method, urlTemplate: $urlTemplate, headerTemplates: $headerTemplates, body: $body, response: $response, authForwarding: $authForwarding)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$HttpHandlerDtoImpl &&
            (identical(other.method, method) || other.method == method) &&
            (identical(other.urlTemplate, urlTemplate) ||
                other.urlTemplate == urlTemplate) &&
            const DeepCollectionEquality().equals(
              other._headerTemplates,
              _headerTemplates,
            ) &&
            (identical(other.body, body) || other.body == body) &&
            (identical(other.response, response) ||
                other.response == response) &&
            (identical(other.authForwarding, authForwarding) ||
                other.authForwarding == authForwarding));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    method,
    urlTemplate,
    const DeepCollectionEquality().hash(_headerTemplates),
    body,
    response,
    authForwarding,
  );

  /// Create a copy of HttpHandlerDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$HttpHandlerDtoImplCopyWith<_$HttpHandlerDtoImpl> get copyWith =>
      __$$HttpHandlerDtoImplCopyWithImpl<_$HttpHandlerDtoImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$HttpHandlerDtoImplToJson(this);
  }
}

abstract class _HttpHandlerDto implements HttpHandlerDto {
  const factory _HttpHandlerDto({
    required final String method,
    required final String urlTemplate,
    final Map<String, String> headerTemplates,
    final HttpBodyDto? body,
    final HttpResponseDto? response,
    final String authForwarding,
  }) = _$HttpHandlerDtoImpl;

  factory _HttpHandlerDto.fromJson(Map<String, dynamic> json) =
      _$HttpHandlerDtoImpl.fromJson;

  @override
  String get method;
  @override
  String get urlTemplate;
  @override
  Map<String, String> get headerTemplates;
  @override
  HttpBodyDto? get body;
  @override
  HttpResponseDto? get response;
  @override
  String get authForwarding;

  /// Create a copy of HttpHandlerDto
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$HttpHandlerDtoImplCopyWith<_$HttpHandlerDtoImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

ToolHandlerDto _$ToolHandlerDtoFromJson(Map<String, dynamic> json) {
  return _ToolHandlerDto.fromJson(json);
}

/// @nodoc
mixin _$ToolHandlerDto {
  String get kind => throw _privateConstructorUsedError;
  String? get beanName => throw _privateConstructorUsedError;
  HttpHandlerDto? get httpSpec => throw _privateConstructorUsedError;

  /// Serializes this ToolHandlerDto to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of ToolHandlerDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $ToolHandlerDtoCopyWith<ToolHandlerDto> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $ToolHandlerDtoCopyWith<$Res> {
  factory $ToolHandlerDtoCopyWith(
    ToolHandlerDto value,
    $Res Function(ToolHandlerDto) then,
  ) = _$ToolHandlerDtoCopyWithImpl<$Res, ToolHandlerDto>;
  @useResult
  $Res call({String kind, String? beanName, HttpHandlerDto? httpSpec});

  $HttpHandlerDtoCopyWith<$Res>? get httpSpec;
}

/// @nodoc
class _$ToolHandlerDtoCopyWithImpl<$Res, $Val extends ToolHandlerDto>
    implements $ToolHandlerDtoCopyWith<$Res> {
  _$ToolHandlerDtoCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of ToolHandlerDto
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? kind = null,
    Object? beanName = freezed,
    Object? httpSpec = freezed,
  }) {
    return _then(
      _value.copyWith(
            kind: null == kind
                ? _value.kind
                : kind // ignore: cast_nullable_to_non_nullable
                      as String,
            beanName: freezed == beanName
                ? _value.beanName
                : beanName // ignore: cast_nullable_to_non_nullable
                      as String?,
            httpSpec: freezed == httpSpec
                ? _value.httpSpec
                : httpSpec // ignore: cast_nullable_to_non_nullable
                      as HttpHandlerDto?,
          )
          as $Val,
    );
  }

  /// Create a copy of ToolHandlerDto
  /// with the given fields replaced by the non-null parameter values.
  @override
  @pragma('vm:prefer-inline')
  $HttpHandlerDtoCopyWith<$Res>? get httpSpec {
    if (_value.httpSpec == null) {
      return null;
    }

    return $HttpHandlerDtoCopyWith<$Res>(_value.httpSpec!, (value) {
      return _then(_value.copyWith(httpSpec: value) as $Val);
    });
  }
}

/// @nodoc
abstract class _$$ToolHandlerDtoImplCopyWith<$Res>
    implements $ToolHandlerDtoCopyWith<$Res> {
  factory _$$ToolHandlerDtoImplCopyWith(
    _$ToolHandlerDtoImpl value,
    $Res Function(_$ToolHandlerDtoImpl) then,
  ) = __$$ToolHandlerDtoImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({String kind, String? beanName, HttpHandlerDto? httpSpec});

  @override
  $HttpHandlerDtoCopyWith<$Res>? get httpSpec;
}

/// @nodoc
class __$$ToolHandlerDtoImplCopyWithImpl<$Res>
    extends _$ToolHandlerDtoCopyWithImpl<$Res, _$ToolHandlerDtoImpl>
    implements _$$ToolHandlerDtoImplCopyWith<$Res> {
  __$$ToolHandlerDtoImplCopyWithImpl(
    _$ToolHandlerDtoImpl _value,
    $Res Function(_$ToolHandlerDtoImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of ToolHandlerDto
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? kind = null,
    Object? beanName = freezed,
    Object? httpSpec = freezed,
  }) {
    return _then(
      _$ToolHandlerDtoImpl(
        kind: null == kind
            ? _value.kind
            : kind // ignore: cast_nullable_to_non_nullable
                  as String,
        beanName: freezed == beanName
            ? _value.beanName
            : beanName // ignore: cast_nullable_to_non_nullable
                  as String?,
        httpSpec: freezed == httpSpec
            ? _value.httpSpec
            : httpSpec // ignore: cast_nullable_to_non_nullable
                  as HttpHandlerDto?,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$ToolHandlerDtoImpl implements _ToolHandlerDto {
  const _$ToolHandlerDtoImpl({
    required this.kind,
    this.beanName,
    this.httpSpec,
  });

  factory _$ToolHandlerDtoImpl.fromJson(Map<String, dynamic> json) =>
      _$$ToolHandlerDtoImplFromJson(json);

  @override
  final String kind;
  @override
  final String? beanName;
  @override
  final HttpHandlerDto? httpSpec;

  @override
  String toString() {
    return 'ToolHandlerDto(kind: $kind, beanName: $beanName, httpSpec: $httpSpec)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$ToolHandlerDtoImpl &&
            (identical(other.kind, kind) || other.kind == kind) &&
            (identical(other.beanName, beanName) ||
                other.beanName == beanName) &&
            (identical(other.httpSpec, httpSpec) ||
                other.httpSpec == httpSpec));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(runtimeType, kind, beanName, httpSpec);

  /// Create a copy of ToolHandlerDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$ToolHandlerDtoImplCopyWith<_$ToolHandlerDtoImpl> get copyWith =>
      __$$ToolHandlerDtoImplCopyWithImpl<_$ToolHandlerDtoImpl>(
        this,
        _$identity,
      );

  @override
  Map<String, dynamic> toJson() {
    return _$$ToolHandlerDtoImplToJson(this);
  }
}

abstract class _ToolHandlerDto implements ToolHandlerDto {
  const factory _ToolHandlerDto({
    required final String kind,
    final String? beanName,
    final HttpHandlerDto? httpSpec,
  }) = _$ToolHandlerDtoImpl;

  factory _ToolHandlerDto.fromJson(Map<String, dynamic> json) =
      _$ToolHandlerDtoImpl.fromJson;

  @override
  String get kind;
  @override
  String? get beanName;
  @override
  HttpHandlerDto? get httpSpec;

  /// Create a copy of ToolHandlerDto
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$ToolHandlerDtoImplCopyWith<_$ToolHandlerDtoImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

ToolRequest _$ToolRequestFromJson(Map<String, dynamic> json) {
  return _ToolRequest.fromJson(json);
}

/// @nodoc
mixin _$ToolRequest {
  String get tenant => throw _privateConstructorUsedError;
  String get id => throw _privateConstructorUsedError;
  String get inputSchemaJson => throw _privateConstructorUsedError;
  String get outputSchemaJson => throw _privateConstructorUsedError;
  Map<String, dynamic> get requires => throw _privateConstructorUsedError;
  ToolHandlerDto get handler => throw _privateConstructorUsedError;
  Map<String, dynamic> get policy => throw _privateConstructorUsedError;

  /// Serializes this ToolRequest to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of ToolRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $ToolRequestCopyWith<ToolRequest> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $ToolRequestCopyWith<$Res> {
  factory $ToolRequestCopyWith(
    ToolRequest value,
    $Res Function(ToolRequest) then,
  ) = _$ToolRequestCopyWithImpl<$Res, ToolRequest>;
  @useResult
  $Res call({
    String tenant,
    String id,
    String inputSchemaJson,
    String outputSchemaJson,
    Map<String, dynamic> requires,
    ToolHandlerDto handler,
    Map<String, dynamic> policy,
  });

  $ToolHandlerDtoCopyWith<$Res> get handler;
}

/// @nodoc
class _$ToolRequestCopyWithImpl<$Res, $Val extends ToolRequest>
    implements $ToolRequestCopyWith<$Res> {
  _$ToolRequestCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of ToolRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenant = null,
    Object? id = null,
    Object? inputSchemaJson = null,
    Object? outputSchemaJson = null,
    Object? requires = null,
    Object? handler = null,
    Object? policy = null,
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
            inputSchemaJson: null == inputSchemaJson
                ? _value.inputSchemaJson
                : inputSchemaJson // ignore: cast_nullable_to_non_nullable
                      as String,
            outputSchemaJson: null == outputSchemaJson
                ? _value.outputSchemaJson
                : outputSchemaJson // ignore: cast_nullable_to_non_nullable
                      as String,
            requires: null == requires
                ? _value.requires
                : requires // ignore: cast_nullable_to_non_nullable
                      as Map<String, dynamic>,
            handler: null == handler
                ? _value.handler
                : handler // ignore: cast_nullable_to_non_nullable
                      as ToolHandlerDto,
            policy: null == policy
                ? _value.policy
                : policy // ignore: cast_nullable_to_non_nullable
                      as Map<String, dynamic>,
          )
          as $Val,
    );
  }

  /// Create a copy of ToolRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @pragma('vm:prefer-inline')
  $ToolHandlerDtoCopyWith<$Res> get handler {
    return $ToolHandlerDtoCopyWith<$Res>(_value.handler, (value) {
      return _then(_value.copyWith(handler: value) as $Val);
    });
  }
}

/// @nodoc
abstract class _$$ToolRequestImplCopyWith<$Res>
    implements $ToolRequestCopyWith<$Res> {
  factory _$$ToolRequestImplCopyWith(
    _$ToolRequestImpl value,
    $Res Function(_$ToolRequestImpl) then,
  ) = __$$ToolRequestImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String tenant,
    String id,
    String inputSchemaJson,
    String outputSchemaJson,
    Map<String, dynamic> requires,
    ToolHandlerDto handler,
    Map<String, dynamic> policy,
  });

  @override
  $ToolHandlerDtoCopyWith<$Res> get handler;
}

/// @nodoc
class __$$ToolRequestImplCopyWithImpl<$Res>
    extends _$ToolRequestCopyWithImpl<$Res, _$ToolRequestImpl>
    implements _$$ToolRequestImplCopyWith<$Res> {
  __$$ToolRequestImplCopyWithImpl(
    _$ToolRequestImpl _value,
    $Res Function(_$ToolRequestImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of ToolRequest
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? tenant = null,
    Object? id = null,
    Object? inputSchemaJson = null,
    Object? outputSchemaJson = null,
    Object? requires = null,
    Object? handler = null,
    Object? policy = null,
  }) {
    return _then(
      _$ToolRequestImpl(
        tenant: null == tenant
            ? _value.tenant
            : tenant // ignore: cast_nullable_to_non_nullable
                  as String,
        id: null == id
            ? _value.id
            : id // ignore: cast_nullable_to_non_nullable
                  as String,
        inputSchemaJson: null == inputSchemaJson
            ? _value.inputSchemaJson
            : inputSchemaJson // ignore: cast_nullable_to_non_nullable
                  as String,
        outputSchemaJson: null == outputSchemaJson
            ? _value.outputSchemaJson
            : outputSchemaJson // ignore: cast_nullable_to_non_nullable
                  as String,
        requires: null == requires
            ? _value._requires
            : requires // ignore: cast_nullable_to_non_nullable
                  as Map<String, dynamic>,
        handler: null == handler
            ? _value.handler
            : handler // ignore: cast_nullable_to_non_nullable
                  as ToolHandlerDto,
        policy: null == policy
            ? _value._policy
            : policy // ignore: cast_nullable_to_non_nullable
                  as Map<String, dynamic>,
      ),
    );
  }
}

/// @nodoc
@JsonSerializable()
class _$ToolRequestImpl implements _ToolRequest {
  const _$ToolRequestImpl({
    required this.tenant,
    required this.id,
    required this.inputSchemaJson,
    required this.outputSchemaJson,
    final Map<String, dynamic> requires = const <String, dynamic>{},
    required this.handler,
    final Map<String, dynamic> policy = const <String, dynamic>{},
  }) : _requires = requires,
       _policy = policy;

  factory _$ToolRequestImpl.fromJson(Map<String, dynamic> json) =>
      _$$ToolRequestImplFromJson(json);

  @override
  final String tenant;
  @override
  final String id;
  @override
  final String inputSchemaJson;
  @override
  final String outputSchemaJson;
  final Map<String, dynamic> _requires;
  @override
  @JsonKey()
  Map<String, dynamic> get requires {
    if (_requires is EqualUnmodifiableMapView) return _requires;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_requires);
  }

  @override
  final ToolHandlerDto handler;
  final Map<String, dynamic> _policy;
  @override
  @JsonKey()
  Map<String, dynamic> get policy {
    if (_policy is EqualUnmodifiableMapView) return _policy;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_policy);
  }

  @override
  String toString() {
    return 'ToolRequest(tenant: $tenant, id: $id, inputSchemaJson: $inputSchemaJson, outputSchemaJson: $outputSchemaJson, requires: $requires, handler: $handler, policy: $policy)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$ToolRequestImpl &&
            (identical(other.tenant, tenant) || other.tenant == tenant) &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.inputSchemaJson, inputSchemaJson) ||
                other.inputSchemaJson == inputSchemaJson) &&
            (identical(other.outputSchemaJson, outputSchemaJson) ||
                other.outputSchemaJson == outputSchemaJson) &&
            const DeepCollectionEquality().equals(other._requires, _requires) &&
            (identical(other.handler, handler) || other.handler == handler) &&
            const DeepCollectionEquality().equals(other._policy, _policy));
  }

  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  int get hashCode => Object.hash(
    runtimeType,
    tenant,
    id,
    inputSchemaJson,
    outputSchemaJson,
    const DeepCollectionEquality().hash(_requires),
    handler,
    const DeepCollectionEquality().hash(_policy),
  );

  /// Create a copy of ToolRequest
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$ToolRequestImplCopyWith<_$ToolRequestImpl> get copyWith =>
      __$$ToolRequestImplCopyWithImpl<_$ToolRequestImpl>(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$ToolRequestImplToJson(this);
  }
}

abstract class _ToolRequest implements ToolRequest {
  const factory _ToolRequest({
    required final String tenant,
    required final String id,
    required final String inputSchemaJson,
    required final String outputSchemaJson,
    final Map<String, dynamic> requires,
    required final ToolHandlerDto handler,
    final Map<String, dynamic> policy,
  }) = _$ToolRequestImpl;

  factory _ToolRequest.fromJson(Map<String, dynamic> json) =
      _$ToolRequestImpl.fromJson;

  @override
  String get tenant;
  @override
  String get id;
  @override
  String get inputSchemaJson;
  @override
  String get outputSchemaJson;
  @override
  Map<String, dynamic> get requires;
  @override
  ToolHandlerDto get handler;
  @override
  Map<String, dynamic> get policy;

  /// Create a copy of ToolRequest
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$ToolRequestImplCopyWith<_$ToolRequestImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

ToolResponse _$ToolResponseFromJson(Map<String, dynamic> json) {
  return _ToolResponse.fromJson(json);
}

/// @nodoc
mixin _$ToolResponse {
  String get id => throw _privateConstructorUsedError;
  int get version => throw _privateConstructorUsedError;
  String get tenant => throw _privateConstructorUsedError;
  String get inputSchemaJson => throw _privateConstructorUsedError;
  String get outputSchemaJson => throw _privateConstructorUsedError;
  Map<String, dynamic> get requires => throw _privateConstructorUsedError;
  ToolHandlerDto get handler => throw _privateConstructorUsedError;
  Map<String, dynamic> get policy => throw _privateConstructorUsedError;
  String? get status => throw _privateConstructorUsedError;
  String? get createdAt => throw _privateConstructorUsedError;

  /// Serializes this ToolResponse to a JSON map.
  Map<String, dynamic> toJson() => throw _privateConstructorUsedError;

  /// Create a copy of ToolResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  $ToolResponseCopyWith<ToolResponse> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $ToolResponseCopyWith<$Res> {
  factory $ToolResponseCopyWith(
    ToolResponse value,
    $Res Function(ToolResponse) then,
  ) = _$ToolResponseCopyWithImpl<$Res, ToolResponse>;
  @useResult
  $Res call({
    String id,
    int version,
    String tenant,
    String inputSchemaJson,
    String outputSchemaJson,
    Map<String, dynamic> requires,
    ToolHandlerDto handler,
    Map<String, dynamic> policy,
    String? status,
    String? createdAt,
  });

  $ToolHandlerDtoCopyWith<$Res> get handler;
}

/// @nodoc
class _$ToolResponseCopyWithImpl<$Res, $Val extends ToolResponse>
    implements $ToolResponseCopyWith<$Res> {
  _$ToolResponseCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of ToolResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? version = null,
    Object? tenant = null,
    Object? inputSchemaJson = null,
    Object? outputSchemaJson = null,
    Object? requires = null,
    Object? handler = null,
    Object? policy = null,
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
            inputSchemaJson: null == inputSchemaJson
                ? _value.inputSchemaJson
                : inputSchemaJson // ignore: cast_nullable_to_non_nullable
                      as String,
            outputSchemaJson: null == outputSchemaJson
                ? _value.outputSchemaJson
                : outputSchemaJson // ignore: cast_nullable_to_non_nullable
                      as String,
            requires: null == requires
                ? _value.requires
                : requires // ignore: cast_nullable_to_non_nullable
                      as Map<String, dynamic>,
            handler: null == handler
                ? _value.handler
                : handler // ignore: cast_nullable_to_non_nullable
                      as ToolHandlerDto,
            policy: null == policy
                ? _value.policy
                : policy // ignore: cast_nullable_to_non_nullable
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

  /// Create a copy of ToolResponse
  /// with the given fields replaced by the non-null parameter values.
  @override
  @pragma('vm:prefer-inline')
  $ToolHandlerDtoCopyWith<$Res> get handler {
    return $ToolHandlerDtoCopyWith<$Res>(_value.handler, (value) {
      return _then(_value.copyWith(handler: value) as $Val);
    });
  }
}

/// @nodoc
abstract class _$$ToolResponseImplCopyWith<$Res>
    implements $ToolResponseCopyWith<$Res> {
  factory _$$ToolResponseImplCopyWith(
    _$ToolResponseImpl value,
    $Res Function(_$ToolResponseImpl) then,
  ) = __$$ToolResponseImplCopyWithImpl<$Res>;
  @override
  @useResult
  $Res call({
    String id,
    int version,
    String tenant,
    String inputSchemaJson,
    String outputSchemaJson,
    Map<String, dynamic> requires,
    ToolHandlerDto handler,
    Map<String, dynamic> policy,
    String? status,
    String? createdAt,
  });

  @override
  $ToolHandlerDtoCopyWith<$Res> get handler;
}

/// @nodoc
class __$$ToolResponseImplCopyWithImpl<$Res>
    extends _$ToolResponseCopyWithImpl<$Res, _$ToolResponseImpl>
    implements _$$ToolResponseImplCopyWith<$Res> {
  __$$ToolResponseImplCopyWithImpl(
    _$ToolResponseImpl _value,
    $Res Function(_$ToolResponseImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of ToolResponse
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? id = null,
    Object? version = null,
    Object? tenant = null,
    Object? inputSchemaJson = null,
    Object? outputSchemaJson = null,
    Object? requires = null,
    Object? handler = null,
    Object? policy = null,
    Object? status = freezed,
    Object? createdAt = freezed,
  }) {
    return _then(
      _$ToolResponseImpl(
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
        inputSchemaJson: null == inputSchemaJson
            ? _value.inputSchemaJson
            : inputSchemaJson // ignore: cast_nullable_to_non_nullable
                  as String,
        outputSchemaJson: null == outputSchemaJson
            ? _value.outputSchemaJson
            : outputSchemaJson // ignore: cast_nullable_to_non_nullable
                  as String,
        requires: null == requires
            ? _value._requires
            : requires // ignore: cast_nullable_to_non_nullable
                  as Map<String, dynamic>,
        handler: null == handler
            ? _value.handler
            : handler // ignore: cast_nullable_to_non_nullable
                  as ToolHandlerDto,
        policy: null == policy
            ? _value._policy
            : policy // ignore: cast_nullable_to_non_nullable
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
class _$ToolResponseImpl implements _ToolResponse {
  const _$ToolResponseImpl({
    required this.id,
    required this.version,
    required this.tenant,
    required this.inputSchemaJson,
    required this.outputSchemaJson,
    final Map<String, dynamic> requires = const <String, dynamic>{},
    required this.handler,
    final Map<String, dynamic> policy = const <String, dynamic>{},
    this.status,
    this.createdAt,
  }) : _requires = requires,
       _policy = policy;

  factory _$ToolResponseImpl.fromJson(Map<String, dynamic> json) =>
      _$$ToolResponseImplFromJson(json);

  @override
  final String id;
  @override
  final int version;
  @override
  final String tenant;
  @override
  final String inputSchemaJson;
  @override
  final String outputSchemaJson;
  final Map<String, dynamic> _requires;
  @override
  @JsonKey()
  Map<String, dynamic> get requires {
    if (_requires is EqualUnmodifiableMapView) return _requires;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_requires);
  }

  @override
  final ToolHandlerDto handler;
  final Map<String, dynamic> _policy;
  @override
  @JsonKey()
  Map<String, dynamic> get policy {
    if (_policy is EqualUnmodifiableMapView) return _policy;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_policy);
  }

  @override
  final String? status;
  @override
  final String? createdAt;

  @override
  String toString() {
    return 'ToolResponse(id: $id, version: $version, tenant: $tenant, inputSchemaJson: $inputSchemaJson, outputSchemaJson: $outputSchemaJson, requires: $requires, handler: $handler, policy: $policy, status: $status, createdAt: $createdAt)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$ToolResponseImpl &&
            (identical(other.id, id) || other.id == id) &&
            (identical(other.version, version) || other.version == version) &&
            (identical(other.tenant, tenant) || other.tenant == tenant) &&
            (identical(other.inputSchemaJson, inputSchemaJson) ||
                other.inputSchemaJson == inputSchemaJson) &&
            (identical(other.outputSchemaJson, outputSchemaJson) ||
                other.outputSchemaJson == outputSchemaJson) &&
            const DeepCollectionEquality().equals(other._requires, _requires) &&
            (identical(other.handler, handler) || other.handler == handler) &&
            const DeepCollectionEquality().equals(other._policy, _policy) &&
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
    inputSchemaJson,
    outputSchemaJson,
    const DeepCollectionEquality().hash(_requires),
    handler,
    const DeepCollectionEquality().hash(_policy),
    status,
    createdAt,
  );

  /// Create a copy of ToolResponse
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$ToolResponseImplCopyWith<_$ToolResponseImpl> get copyWith =>
      __$$ToolResponseImplCopyWithImpl<_$ToolResponseImpl>(this, _$identity);

  @override
  Map<String, dynamic> toJson() {
    return _$$ToolResponseImplToJson(this);
  }
}

abstract class _ToolResponse implements ToolResponse {
  const factory _ToolResponse({
    required final String id,
    required final int version,
    required final String tenant,
    required final String inputSchemaJson,
    required final String outputSchemaJson,
    final Map<String, dynamic> requires,
    required final ToolHandlerDto handler,
    final Map<String, dynamic> policy,
    final String? status,
    final String? createdAt,
  }) = _$ToolResponseImpl;

  factory _ToolResponse.fromJson(Map<String, dynamic> json) =
      _$ToolResponseImpl.fromJson;

  @override
  String get id;
  @override
  int get version;
  @override
  String get tenant;
  @override
  String get inputSchemaJson;
  @override
  String get outputSchemaJson;
  @override
  Map<String, dynamic> get requires;
  @override
  ToolHandlerDto get handler;
  @override
  Map<String, dynamic> get policy;
  @override
  String? get status;
  @override
  String? get createdAt;

  /// Create a copy of ToolResponse
  /// with the given fields replaced by the non-null parameter values.
  @override
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$ToolResponseImplCopyWith<_$ToolResponseImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

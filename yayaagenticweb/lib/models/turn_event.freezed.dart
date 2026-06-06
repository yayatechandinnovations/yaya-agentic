// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint
// ignore_for_file: unused_element, deprecated_member_use, deprecated_member_use_from_same_package, use_function_type_syntax_for_parameters, unnecessary_const, avoid_init_to_null, invalid_override_different_default_values_named, prefer_expression_function_bodies, annotate_overrides, invalid_annotation_target, unnecessary_question_mark

part of 'turn_event.dart';

// **************************************************************************
// FreezedGenerator
// **************************************************************************

T _$identity<T>(T value) => value;

final _privateConstructorUsedError = UnsupportedError(
  'It seems like you constructed your class using `MyClass._()`. This constructor is only meant to be used by freezed and you are not supposed to need it nor use it.\nPlease check the documentation here for more information: https://github.com/rrousselGit/freezed#adding-getters-and-methods-to-our-models',
);

/// @nodoc
mixin _$TurnEventDto {
  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(String text) token,
    required TResult Function(
      String callId,
      String tool,
      Map<String, dynamic> args,
    )
    toolCall,
    required TResult Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )
    toolResult,
    required TResult Function(
      String chunkId,
      String source,
      String? title,
      String? url,
    )
    citation,
    required TResult Function(String kind, Map<String, dynamic> payload) uiHint,
    required TResult Function(String? turnId, int? tokensIn, int? tokensOut)
    end,
  }) => throw _privateConstructorUsedError;
  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(String text)? token,
    TResult? Function(String callId, String tool, Map<String, dynamic> args)?
    toolCall,
    TResult? Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )?
    toolResult,
    TResult? Function(
      String chunkId,
      String source,
      String? title,
      String? url,
    )?
    citation,
    TResult? Function(String kind, Map<String, dynamic> payload)? uiHint,
    TResult? Function(String? turnId, int? tokensIn, int? tokensOut)? end,
  }) => throw _privateConstructorUsedError;
  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(String text)? token,
    TResult Function(String callId, String tool, Map<String, dynamic> args)?
    toolCall,
    TResult Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )?
    toolResult,
    TResult Function(String chunkId, String source, String? title, String? url)?
    citation,
    TResult Function(String kind, Map<String, dynamic> payload)? uiHint,
    TResult Function(String? turnId, int? tokensIn, int? tokensOut)? end,
    required TResult orElse(),
  }) => throw _privateConstructorUsedError;
  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(TokenEvent value) token,
    required TResult Function(ToolCallEvent value) toolCall,
    required TResult Function(ToolResultEvent value) toolResult,
    required TResult Function(CitationEvent value) citation,
    required TResult Function(UiHintEvent value) uiHint,
    required TResult Function(EndEvent value) end,
  }) => throw _privateConstructorUsedError;
  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(TokenEvent value)? token,
    TResult? Function(ToolCallEvent value)? toolCall,
    TResult? Function(ToolResultEvent value)? toolResult,
    TResult? Function(CitationEvent value)? citation,
    TResult? Function(UiHintEvent value)? uiHint,
    TResult? Function(EndEvent value)? end,
  }) => throw _privateConstructorUsedError;
  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(TokenEvent value)? token,
    TResult Function(ToolCallEvent value)? toolCall,
    TResult Function(ToolResultEvent value)? toolResult,
    TResult Function(CitationEvent value)? citation,
    TResult Function(UiHintEvent value)? uiHint,
    TResult Function(EndEvent value)? end,
    required TResult orElse(),
  }) => throw _privateConstructorUsedError;
}

/// @nodoc
abstract class $TurnEventDtoCopyWith<$Res> {
  factory $TurnEventDtoCopyWith(
    TurnEventDto value,
    $Res Function(TurnEventDto) then,
  ) = _$TurnEventDtoCopyWithImpl<$Res, TurnEventDto>;
}

/// @nodoc
class _$TurnEventDtoCopyWithImpl<$Res, $Val extends TurnEventDto>
    implements $TurnEventDtoCopyWith<$Res> {
  _$TurnEventDtoCopyWithImpl(this._value, this._then);

  // ignore: unused_field
  final $Val _value;
  // ignore: unused_field
  final $Res Function($Val) _then;

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
}

/// @nodoc
abstract class _$$TokenEventImplCopyWith<$Res> {
  factory _$$TokenEventImplCopyWith(
    _$TokenEventImpl value,
    $Res Function(_$TokenEventImpl) then,
  ) = __$$TokenEventImplCopyWithImpl<$Res>;
  @useResult
  $Res call({String text});
}

/// @nodoc
class __$$TokenEventImplCopyWithImpl<$Res>
    extends _$TurnEventDtoCopyWithImpl<$Res, _$TokenEventImpl>
    implements _$$TokenEventImplCopyWith<$Res> {
  __$$TokenEventImplCopyWithImpl(
    _$TokenEventImpl _value,
    $Res Function(_$TokenEventImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? text = null}) {
    return _then(
      _$TokenEventImpl(
        null == text
            ? _value.text
            : text // ignore: cast_nullable_to_non_nullable
                  as String,
      ),
    );
  }
}

/// @nodoc

class _$TokenEventImpl implements TokenEvent {
  const _$TokenEventImpl(this.text);

  @override
  final String text;

  @override
  String toString() {
    return 'TurnEventDto.token(text: $text)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$TokenEventImpl &&
            (identical(other.text, text) || other.text == text));
  }

  @override
  int get hashCode => Object.hash(runtimeType, text);

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$TokenEventImplCopyWith<_$TokenEventImpl> get copyWith =>
      __$$TokenEventImplCopyWithImpl<_$TokenEventImpl>(this, _$identity);

  @override
  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(String text) token,
    required TResult Function(
      String callId,
      String tool,
      Map<String, dynamic> args,
    )
    toolCall,
    required TResult Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )
    toolResult,
    required TResult Function(
      String chunkId,
      String source,
      String? title,
      String? url,
    )
    citation,
    required TResult Function(String kind, Map<String, dynamic> payload) uiHint,
    required TResult Function(String? turnId, int? tokensIn, int? tokensOut)
    end,
  }) {
    return token(text);
  }

  @override
  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(String text)? token,
    TResult? Function(String callId, String tool, Map<String, dynamic> args)?
    toolCall,
    TResult? Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )?
    toolResult,
    TResult? Function(
      String chunkId,
      String source,
      String? title,
      String? url,
    )?
    citation,
    TResult? Function(String kind, Map<String, dynamic> payload)? uiHint,
    TResult? Function(String? turnId, int? tokensIn, int? tokensOut)? end,
  }) {
    return token?.call(text);
  }

  @override
  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(String text)? token,
    TResult Function(String callId, String tool, Map<String, dynamic> args)?
    toolCall,
    TResult Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )?
    toolResult,
    TResult Function(String chunkId, String source, String? title, String? url)?
    citation,
    TResult Function(String kind, Map<String, dynamic> payload)? uiHint,
    TResult Function(String? turnId, int? tokensIn, int? tokensOut)? end,
    required TResult orElse(),
  }) {
    if (token != null) {
      return token(text);
    }
    return orElse();
  }

  @override
  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(TokenEvent value) token,
    required TResult Function(ToolCallEvent value) toolCall,
    required TResult Function(ToolResultEvent value) toolResult,
    required TResult Function(CitationEvent value) citation,
    required TResult Function(UiHintEvent value) uiHint,
    required TResult Function(EndEvent value) end,
  }) {
    return token(this);
  }

  @override
  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(TokenEvent value)? token,
    TResult? Function(ToolCallEvent value)? toolCall,
    TResult? Function(ToolResultEvent value)? toolResult,
    TResult? Function(CitationEvent value)? citation,
    TResult? Function(UiHintEvent value)? uiHint,
    TResult? Function(EndEvent value)? end,
  }) {
    return token?.call(this);
  }

  @override
  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(TokenEvent value)? token,
    TResult Function(ToolCallEvent value)? toolCall,
    TResult Function(ToolResultEvent value)? toolResult,
    TResult Function(CitationEvent value)? citation,
    TResult Function(UiHintEvent value)? uiHint,
    TResult Function(EndEvent value)? end,
    required TResult orElse(),
  }) {
    if (token != null) {
      return token(this);
    }
    return orElse();
  }
}

abstract class TokenEvent implements TurnEventDto {
  const factory TokenEvent(final String text) = _$TokenEventImpl;

  String get text;

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$TokenEventImplCopyWith<_$TokenEventImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class _$$ToolCallEventImplCopyWith<$Res> {
  factory _$$ToolCallEventImplCopyWith(
    _$ToolCallEventImpl value,
    $Res Function(_$ToolCallEventImpl) then,
  ) = __$$ToolCallEventImplCopyWithImpl<$Res>;
  @useResult
  $Res call({String callId, String tool, Map<String, dynamic> args});
}

/// @nodoc
class __$$ToolCallEventImplCopyWithImpl<$Res>
    extends _$TurnEventDtoCopyWithImpl<$Res, _$ToolCallEventImpl>
    implements _$$ToolCallEventImplCopyWith<$Res> {
  __$$ToolCallEventImplCopyWithImpl(
    _$ToolCallEventImpl _value,
    $Res Function(_$ToolCallEventImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? callId = null, Object? tool = null, Object? args = null}) {
    return _then(
      _$ToolCallEventImpl(
        callId: null == callId
            ? _value.callId
            : callId // ignore: cast_nullable_to_non_nullable
                  as String,
        tool: null == tool
            ? _value.tool
            : tool // ignore: cast_nullable_to_non_nullable
                  as String,
        args: null == args
            ? _value._args
            : args // ignore: cast_nullable_to_non_nullable
                  as Map<String, dynamic>,
      ),
    );
  }
}

/// @nodoc

class _$ToolCallEventImpl implements ToolCallEvent {
  const _$ToolCallEventImpl({
    required this.callId,
    required this.tool,
    required final Map<String, dynamic> args,
  }) : _args = args;

  @override
  final String callId;
  @override
  final String tool;
  final Map<String, dynamic> _args;
  @override
  Map<String, dynamic> get args {
    if (_args is EqualUnmodifiableMapView) return _args;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_args);
  }

  @override
  String toString() {
    return 'TurnEventDto.toolCall(callId: $callId, tool: $tool, args: $args)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$ToolCallEventImpl &&
            (identical(other.callId, callId) || other.callId == callId) &&
            (identical(other.tool, tool) || other.tool == tool) &&
            const DeepCollectionEquality().equals(other._args, _args));
  }

  @override
  int get hashCode => Object.hash(
    runtimeType,
    callId,
    tool,
    const DeepCollectionEquality().hash(_args),
  );

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$ToolCallEventImplCopyWith<_$ToolCallEventImpl> get copyWith =>
      __$$ToolCallEventImplCopyWithImpl<_$ToolCallEventImpl>(this, _$identity);

  @override
  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(String text) token,
    required TResult Function(
      String callId,
      String tool,
      Map<String, dynamic> args,
    )
    toolCall,
    required TResult Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )
    toolResult,
    required TResult Function(
      String chunkId,
      String source,
      String? title,
      String? url,
    )
    citation,
    required TResult Function(String kind, Map<String, dynamic> payload) uiHint,
    required TResult Function(String? turnId, int? tokensIn, int? tokensOut)
    end,
  }) {
    return toolCall(callId, tool, args);
  }

  @override
  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(String text)? token,
    TResult? Function(String callId, String tool, Map<String, dynamic> args)?
    toolCall,
    TResult? Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )?
    toolResult,
    TResult? Function(
      String chunkId,
      String source,
      String? title,
      String? url,
    )?
    citation,
    TResult? Function(String kind, Map<String, dynamic> payload)? uiHint,
    TResult? Function(String? turnId, int? tokensIn, int? tokensOut)? end,
  }) {
    return toolCall?.call(callId, tool, args);
  }

  @override
  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(String text)? token,
    TResult Function(String callId, String tool, Map<String, dynamic> args)?
    toolCall,
    TResult Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )?
    toolResult,
    TResult Function(String chunkId, String source, String? title, String? url)?
    citation,
    TResult Function(String kind, Map<String, dynamic> payload)? uiHint,
    TResult Function(String? turnId, int? tokensIn, int? tokensOut)? end,
    required TResult orElse(),
  }) {
    if (toolCall != null) {
      return toolCall(callId, tool, args);
    }
    return orElse();
  }

  @override
  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(TokenEvent value) token,
    required TResult Function(ToolCallEvent value) toolCall,
    required TResult Function(ToolResultEvent value) toolResult,
    required TResult Function(CitationEvent value) citation,
    required TResult Function(UiHintEvent value) uiHint,
    required TResult Function(EndEvent value) end,
  }) {
    return toolCall(this);
  }

  @override
  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(TokenEvent value)? token,
    TResult? Function(ToolCallEvent value)? toolCall,
    TResult? Function(ToolResultEvent value)? toolResult,
    TResult? Function(CitationEvent value)? citation,
    TResult? Function(UiHintEvent value)? uiHint,
    TResult? Function(EndEvent value)? end,
  }) {
    return toolCall?.call(this);
  }

  @override
  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(TokenEvent value)? token,
    TResult Function(ToolCallEvent value)? toolCall,
    TResult Function(ToolResultEvent value)? toolResult,
    TResult Function(CitationEvent value)? citation,
    TResult Function(UiHintEvent value)? uiHint,
    TResult Function(EndEvent value)? end,
    required TResult orElse(),
  }) {
    if (toolCall != null) {
      return toolCall(this);
    }
    return orElse();
  }
}

abstract class ToolCallEvent implements TurnEventDto {
  const factory ToolCallEvent({
    required final String callId,
    required final String tool,
    required final Map<String, dynamic> args,
  }) = _$ToolCallEventImpl;

  String get callId;
  String get tool;
  Map<String, dynamic> get args;

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$ToolCallEventImplCopyWith<_$ToolCallEventImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class _$$ToolResultEventImplCopyWith<$Res> {
  factory _$$ToolResultEventImplCopyWith(
    _$ToolResultEventImpl value,
    $Res Function(_$ToolResultEventImpl) then,
  ) = __$$ToolResultEventImplCopyWithImpl<$Res>;
  @useResult
  $Res call({String callId, String status, Object? value, String? error});
}

/// @nodoc
class __$$ToolResultEventImplCopyWithImpl<$Res>
    extends _$TurnEventDtoCopyWithImpl<$Res, _$ToolResultEventImpl>
    implements _$$ToolResultEventImplCopyWith<$Res> {
  __$$ToolResultEventImplCopyWithImpl(
    _$ToolResultEventImpl _value,
    $Res Function(_$ToolResultEventImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? callId = null,
    Object? status = null,
    Object? value = freezed,
    Object? error = freezed,
  }) {
    return _then(
      _$ToolResultEventImpl(
        callId: null == callId
            ? _value.callId
            : callId // ignore: cast_nullable_to_non_nullable
                  as String,
        status: null == status
            ? _value.status
            : status // ignore: cast_nullable_to_non_nullable
                  as String,
        value: freezed == value ? _value.value : value,
        error: freezed == error
            ? _value.error
            : error // ignore: cast_nullable_to_non_nullable
                  as String?,
      ),
    );
  }
}

/// @nodoc

class _$ToolResultEventImpl implements ToolResultEvent {
  const _$ToolResultEventImpl({
    required this.callId,
    required this.status,
    this.value,
    this.error,
  });

  @override
  final String callId;
  @override
  final String status;
  @override
  final Object? value;
  @override
  final String? error;

  @override
  String toString() {
    return 'TurnEventDto.toolResult(callId: $callId, status: $status, value: $value, error: $error)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$ToolResultEventImpl &&
            (identical(other.callId, callId) || other.callId == callId) &&
            (identical(other.status, status) || other.status == status) &&
            const DeepCollectionEquality().equals(other.value, value) &&
            (identical(other.error, error) || other.error == error));
  }

  @override
  int get hashCode => Object.hash(
    runtimeType,
    callId,
    status,
    const DeepCollectionEquality().hash(value),
    error,
  );

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$ToolResultEventImplCopyWith<_$ToolResultEventImpl> get copyWith =>
      __$$ToolResultEventImplCopyWithImpl<_$ToolResultEventImpl>(
        this,
        _$identity,
      );

  @override
  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(String text) token,
    required TResult Function(
      String callId,
      String tool,
      Map<String, dynamic> args,
    )
    toolCall,
    required TResult Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )
    toolResult,
    required TResult Function(
      String chunkId,
      String source,
      String? title,
      String? url,
    )
    citation,
    required TResult Function(String kind, Map<String, dynamic> payload) uiHint,
    required TResult Function(String? turnId, int? tokensIn, int? tokensOut)
    end,
  }) {
    return toolResult(callId, status, value, error);
  }

  @override
  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(String text)? token,
    TResult? Function(String callId, String tool, Map<String, dynamic> args)?
    toolCall,
    TResult? Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )?
    toolResult,
    TResult? Function(
      String chunkId,
      String source,
      String? title,
      String? url,
    )?
    citation,
    TResult? Function(String kind, Map<String, dynamic> payload)? uiHint,
    TResult? Function(String? turnId, int? tokensIn, int? tokensOut)? end,
  }) {
    return toolResult?.call(callId, status, value, error);
  }

  @override
  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(String text)? token,
    TResult Function(String callId, String tool, Map<String, dynamic> args)?
    toolCall,
    TResult Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )?
    toolResult,
    TResult Function(String chunkId, String source, String? title, String? url)?
    citation,
    TResult Function(String kind, Map<String, dynamic> payload)? uiHint,
    TResult Function(String? turnId, int? tokensIn, int? tokensOut)? end,
    required TResult orElse(),
  }) {
    if (toolResult != null) {
      return toolResult(callId, status, value, error);
    }
    return orElse();
  }

  @override
  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(TokenEvent value) token,
    required TResult Function(ToolCallEvent value) toolCall,
    required TResult Function(ToolResultEvent value) toolResult,
    required TResult Function(CitationEvent value) citation,
    required TResult Function(UiHintEvent value) uiHint,
    required TResult Function(EndEvent value) end,
  }) {
    return toolResult(this);
  }

  @override
  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(TokenEvent value)? token,
    TResult? Function(ToolCallEvent value)? toolCall,
    TResult? Function(ToolResultEvent value)? toolResult,
    TResult? Function(CitationEvent value)? citation,
    TResult? Function(UiHintEvent value)? uiHint,
    TResult? Function(EndEvent value)? end,
  }) {
    return toolResult?.call(this);
  }

  @override
  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(TokenEvent value)? token,
    TResult Function(ToolCallEvent value)? toolCall,
    TResult Function(ToolResultEvent value)? toolResult,
    TResult Function(CitationEvent value)? citation,
    TResult Function(UiHintEvent value)? uiHint,
    TResult Function(EndEvent value)? end,
    required TResult orElse(),
  }) {
    if (toolResult != null) {
      return toolResult(this);
    }
    return orElse();
  }
}

abstract class ToolResultEvent implements TurnEventDto {
  const factory ToolResultEvent({
    required final String callId,
    required final String status,
    final Object? value,
    final String? error,
  }) = _$ToolResultEventImpl;

  String get callId;
  String get status;
  Object? get value;
  String? get error;

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$ToolResultEventImplCopyWith<_$ToolResultEventImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class _$$CitationEventImplCopyWith<$Res> {
  factory _$$CitationEventImplCopyWith(
    _$CitationEventImpl value,
    $Res Function(_$CitationEventImpl) then,
  ) = __$$CitationEventImplCopyWithImpl<$Res>;
  @useResult
  $Res call({String chunkId, String source, String? title, String? url});
}

/// @nodoc
class __$$CitationEventImplCopyWithImpl<$Res>
    extends _$TurnEventDtoCopyWithImpl<$Res, _$CitationEventImpl>
    implements _$$CitationEventImplCopyWith<$Res> {
  __$$CitationEventImplCopyWithImpl(
    _$CitationEventImpl _value,
    $Res Function(_$CitationEventImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? chunkId = null,
    Object? source = null,
    Object? title = freezed,
    Object? url = freezed,
  }) {
    return _then(
      _$CitationEventImpl(
        chunkId: null == chunkId
            ? _value.chunkId
            : chunkId // ignore: cast_nullable_to_non_nullable
                  as String,
        source: null == source
            ? _value.source
            : source // ignore: cast_nullable_to_non_nullable
                  as String,
        title: freezed == title
            ? _value.title
            : title // ignore: cast_nullable_to_non_nullable
                  as String?,
        url: freezed == url
            ? _value.url
            : url // ignore: cast_nullable_to_non_nullable
                  as String?,
      ),
    );
  }
}

/// @nodoc

class _$CitationEventImpl implements CitationEvent {
  const _$CitationEventImpl({
    required this.chunkId,
    required this.source,
    this.title,
    this.url,
  });

  @override
  final String chunkId;
  @override
  final String source;
  @override
  final String? title;
  @override
  final String? url;

  @override
  String toString() {
    return 'TurnEventDto.citation(chunkId: $chunkId, source: $source, title: $title, url: $url)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$CitationEventImpl &&
            (identical(other.chunkId, chunkId) || other.chunkId == chunkId) &&
            (identical(other.source, source) || other.source == source) &&
            (identical(other.title, title) || other.title == title) &&
            (identical(other.url, url) || other.url == url));
  }

  @override
  int get hashCode => Object.hash(runtimeType, chunkId, source, title, url);

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$CitationEventImplCopyWith<_$CitationEventImpl> get copyWith =>
      __$$CitationEventImplCopyWithImpl<_$CitationEventImpl>(this, _$identity);

  @override
  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(String text) token,
    required TResult Function(
      String callId,
      String tool,
      Map<String, dynamic> args,
    )
    toolCall,
    required TResult Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )
    toolResult,
    required TResult Function(
      String chunkId,
      String source,
      String? title,
      String? url,
    )
    citation,
    required TResult Function(String kind, Map<String, dynamic> payload) uiHint,
    required TResult Function(String? turnId, int? tokensIn, int? tokensOut)
    end,
  }) {
    return citation(chunkId, source, title, url);
  }

  @override
  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(String text)? token,
    TResult? Function(String callId, String tool, Map<String, dynamic> args)?
    toolCall,
    TResult? Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )?
    toolResult,
    TResult? Function(
      String chunkId,
      String source,
      String? title,
      String? url,
    )?
    citation,
    TResult? Function(String kind, Map<String, dynamic> payload)? uiHint,
    TResult? Function(String? turnId, int? tokensIn, int? tokensOut)? end,
  }) {
    return citation?.call(chunkId, source, title, url);
  }

  @override
  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(String text)? token,
    TResult Function(String callId, String tool, Map<String, dynamic> args)?
    toolCall,
    TResult Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )?
    toolResult,
    TResult Function(String chunkId, String source, String? title, String? url)?
    citation,
    TResult Function(String kind, Map<String, dynamic> payload)? uiHint,
    TResult Function(String? turnId, int? tokensIn, int? tokensOut)? end,
    required TResult orElse(),
  }) {
    if (citation != null) {
      return citation(chunkId, source, title, url);
    }
    return orElse();
  }

  @override
  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(TokenEvent value) token,
    required TResult Function(ToolCallEvent value) toolCall,
    required TResult Function(ToolResultEvent value) toolResult,
    required TResult Function(CitationEvent value) citation,
    required TResult Function(UiHintEvent value) uiHint,
    required TResult Function(EndEvent value) end,
  }) {
    return citation(this);
  }

  @override
  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(TokenEvent value)? token,
    TResult? Function(ToolCallEvent value)? toolCall,
    TResult? Function(ToolResultEvent value)? toolResult,
    TResult? Function(CitationEvent value)? citation,
    TResult? Function(UiHintEvent value)? uiHint,
    TResult? Function(EndEvent value)? end,
  }) {
    return citation?.call(this);
  }

  @override
  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(TokenEvent value)? token,
    TResult Function(ToolCallEvent value)? toolCall,
    TResult Function(ToolResultEvent value)? toolResult,
    TResult Function(CitationEvent value)? citation,
    TResult Function(UiHintEvent value)? uiHint,
    TResult Function(EndEvent value)? end,
    required TResult orElse(),
  }) {
    if (citation != null) {
      return citation(this);
    }
    return orElse();
  }
}

abstract class CitationEvent implements TurnEventDto {
  const factory CitationEvent({
    required final String chunkId,
    required final String source,
    final String? title,
    final String? url,
  }) = _$CitationEventImpl;

  String get chunkId;
  String get source;
  String? get title;
  String? get url;

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$CitationEventImplCopyWith<_$CitationEventImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class _$$UiHintEventImplCopyWith<$Res> {
  factory _$$UiHintEventImplCopyWith(
    _$UiHintEventImpl value,
    $Res Function(_$UiHintEventImpl) then,
  ) = __$$UiHintEventImplCopyWithImpl<$Res>;
  @useResult
  $Res call({String kind, Map<String, dynamic> payload});
}

/// @nodoc
class __$$UiHintEventImplCopyWithImpl<$Res>
    extends _$TurnEventDtoCopyWithImpl<$Res, _$UiHintEventImpl>
    implements _$$UiHintEventImplCopyWith<$Res> {
  __$$UiHintEventImplCopyWithImpl(
    _$UiHintEventImpl _value,
    $Res Function(_$UiHintEventImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({Object? kind = null, Object? payload = null}) {
    return _then(
      _$UiHintEventImpl(
        kind: null == kind
            ? _value.kind
            : kind // ignore: cast_nullable_to_non_nullable
                  as String,
        payload: null == payload
            ? _value._payload
            : payload // ignore: cast_nullable_to_non_nullable
                  as Map<String, dynamic>,
      ),
    );
  }
}

/// @nodoc

class _$UiHintEventImpl implements UiHintEvent {
  const _$UiHintEventImpl({
    required this.kind,
    required final Map<String, dynamic> payload,
  }) : _payload = payload;

  @override
  final String kind;
  final Map<String, dynamic> _payload;
  @override
  Map<String, dynamic> get payload {
    if (_payload is EqualUnmodifiableMapView) return _payload;
    // ignore: implicit_dynamic_type
    return EqualUnmodifiableMapView(_payload);
  }

  @override
  String toString() {
    return 'TurnEventDto.uiHint(kind: $kind, payload: $payload)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$UiHintEventImpl &&
            (identical(other.kind, kind) || other.kind == kind) &&
            const DeepCollectionEquality().equals(other._payload, _payload));
  }

  @override
  int get hashCode => Object.hash(
    runtimeType,
    kind,
    const DeepCollectionEquality().hash(_payload),
  );

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$UiHintEventImplCopyWith<_$UiHintEventImpl> get copyWith =>
      __$$UiHintEventImplCopyWithImpl<_$UiHintEventImpl>(this, _$identity);

  @override
  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(String text) token,
    required TResult Function(
      String callId,
      String tool,
      Map<String, dynamic> args,
    )
    toolCall,
    required TResult Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )
    toolResult,
    required TResult Function(
      String chunkId,
      String source,
      String? title,
      String? url,
    )
    citation,
    required TResult Function(String kind, Map<String, dynamic> payload) uiHint,
    required TResult Function(String? turnId, int? tokensIn, int? tokensOut)
    end,
  }) {
    return uiHint(kind, payload);
  }

  @override
  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(String text)? token,
    TResult? Function(String callId, String tool, Map<String, dynamic> args)?
    toolCall,
    TResult? Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )?
    toolResult,
    TResult? Function(
      String chunkId,
      String source,
      String? title,
      String? url,
    )?
    citation,
    TResult? Function(String kind, Map<String, dynamic> payload)? uiHint,
    TResult? Function(String? turnId, int? tokensIn, int? tokensOut)? end,
  }) {
    return uiHint?.call(kind, payload);
  }

  @override
  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(String text)? token,
    TResult Function(String callId, String tool, Map<String, dynamic> args)?
    toolCall,
    TResult Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )?
    toolResult,
    TResult Function(String chunkId, String source, String? title, String? url)?
    citation,
    TResult Function(String kind, Map<String, dynamic> payload)? uiHint,
    TResult Function(String? turnId, int? tokensIn, int? tokensOut)? end,
    required TResult orElse(),
  }) {
    if (uiHint != null) {
      return uiHint(kind, payload);
    }
    return orElse();
  }

  @override
  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(TokenEvent value) token,
    required TResult Function(ToolCallEvent value) toolCall,
    required TResult Function(ToolResultEvent value) toolResult,
    required TResult Function(CitationEvent value) citation,
    required TResult Function(UiHintEvent value) uiHint,
    required TResult Function(EndEvent value) end,
  }) {
    return uiHint(this);
  }

  @override
  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(TokenEvent value)? token,
    TResult? Function(ToolCallEvent value)? toolCall,
    TResult? Function(ToolResultEvent value)? toolResult,
    TResult? Function(CitationEvent value)? citation,
    TResult? Function(UiHintEvent value)? uiHint,
    TResult? Function(EndEvent value)? end,
  }) {
    return uiHint?.call(this);
  }

  @override
  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(TokenEvent value)? token,
    TResult Function(ToolCallEvent value)? toolCall,
    TResult Function(ToolResultEvent value)? toolResult,
    TResult Function(CitationEvent value)? citation,
    TResult Function(UiHintEvent value)? uiHint,
    TResult Function(EndEvent value)? end,
    required TResult orElse(),
  }) {
    if (uiHint != null) {
      return uiHint(this);
    }
    return orElse();
  }
}

abstract class UiHintEvent implements TurnEventDto {
  const factory UiHintEvent({
    required final String kind,
    required final Map<String, dynamic> payload,
  }) = _$UiHintEventImpl;

  String get kind;
  Map<String, dynamic> get payload;

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$UiHintEventImplCopyWith<_$UiHintEventImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

/// @nodoc
abstract class _$$EndEventImplCopyWith<$Res> {
  factory _$$EndEventImplCopyWith(
    _$EndEventImpl value,
    $Res Function(_$EndEventImpl) then,
  ) = __$$EndEventImplCopyWithImpl<$Res>;
  @useResult
  $Res call({String? turnId, int? tokensIn, int? tokensOut});
}

/// @nodoc
class __$$EndEventImplCopyWithImpl<$Res>
    extends _$TurnEventDtoCopyWithImpl<$Res, _$EndEventImpl>
    implements _$$EndEventImplCopyWith<$Res> {
  __$$EndEventImplCopyWithImpl(
    _$EndEventImpl _value,
    $Res Function(_$EndEventImpl) _then,
  ) : super(_value, _then);

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @pragma('vm:prefer-inline')
  @override
  $Res call({
    Object? turnId = freezed,
    Object? tokensIn = freezed,
    Object? tokensOut = freezed,
  }) {
    return _then(
      _$EndEventImpl(
        turnId: freezed == turnId
            ? _value.turnId
            : turnId // ignore: cast_nullable_to_non_nullable
                  as String?,
        tokensIn: freezed == tokensIn
            ? _value.tokensIn
            : tokensIn // ignore: cast_nullable_to_non_nullable
                  as int?,
        tokensOut: freezed == tokensOut
            ? _value.tokensOut
            : tokensOut // ignore: cast_nullable_to_non_nullable
                  as int?,
      ),
    );
  }
}

/// @nodoc

class _$EndEventImpl implements EndEvent {
  const _$EndEventImpl({this.turnId, this.tokensIn, this.tokensOut});

  @override
  final String? turnId;
  @override
  final int? tokensIn;
  @override
  final int? tokensOut;

  @override
  String toString() {
    return 'TurnEventDto.end(turnId: $turnId, tokensIn: $tokensIn, tokensOut: $tokensOut)';
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other.runtimeType == runtimeType &&
            other is _$EndEventImpl &&
            (identical(other.turnId, turnId) || other.turnId == turnId) &&
            (identical(other.tokensIn, tokensIn) ||
                other.tokensIn == tokensIn) &&
            (identical(other.tokensOut, tokensOut) ||
                other.tokensOut == tokensOut));
  }

  @override
  int get hashCode => Object.hash(runtimeType, turnId, tokensIn, tokensOut);

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  @override
  @pragma('vm:prefer-inline')
  _$$EndEventImplCopyWith<_$EndEventImpl> get copyWith =>
      __$$EndEventImplCopyWithImpl<_$EndEventImpl>(this, _$identity);

  @override
  @optionalTypeArgs
  TResult when<TResult extends Object?>({
    required TResult Function(String text) token,
    required TResult Function(
      String callId,
      String tool,
      Map<String, dynamic> args,
    )
    toolCall,
    required TResult Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )
    toolResult,
    required TResult Function(
      String chunkId,
      String source,
      String? title,
      String? url,
    )
    citation,
    required TResult Function(String kind, Map<String, dynamic> payload) uiHint,
    required TResult Function(String? turnId, int? tokensIn, int? tokensOut)
    end,
  }) {
    return end(turnId, tokensIn, tokensOut);
  }

  @override
  @optionalTypeArgs
  TResult? whenOrNull<TResult extends Object?>({
    TResult? Function(String text)? token,
    TResult? Function(String callId, String tool, Map<String, dynamic> args)?
    toolCall,
    TResult? Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )?
    toolResult,
    TResult? Function(
      String chunkId,
      String source,
      String? title,
      String? url,
    )?
    citation,
    TResult? Function(String kind, Map<String, dynamic> payload)? uiHint,
    TResult? Function(String? turnId, int? tokensIn, int? tokensOut)? end,
  }) {
    return end?.call(turnId, tokensIn, tokensOut);
  }

  @override
  @optionalTypeArgs
  TResult maybeWhen<TResult extends Object?>({
    TResult Function(String text)? token,
    TResult Function(String callId, String tool, Map<String, dynamic> args)?
    toolCall,
    TResult Function(
      String callId,
      String status,
      Object? value,
      String? error,
    )?
    toolResult,
    TResult Function(String chunkId, String source, String? title, String? url)?
    citation,
    TResult Function(String kind, Map<String, dynamic> payload)? uiHint,
    TResult Function(String? turnId, int? tokensIn, int? tokensOut)? end,
    required TResult orElse(),
  }) {
    if (end != null) {
      return end(turnId, tokensIn, tokensOut);
    }
    return orElse();
  }

  @override
  @optionalTypeArgs
  TResult map<TResult extends Object?>({
    required TResult Function(TokenEvent value) token,
    required TResult Function(ToolCallEvent value) toolCall,
    required TResult Function(ToolResultEvent value) toolResult,
    required TResult Function(CitationEvent value) citation,
    required TResult Function(UiHintEvent value) uiHint,
    required TResult Function(EndEvent value) end,
  }) {
    return end(this);
  }

  @override
  @optionalTypeArgs
  TResult? mapOrNull<TResult extends Object?>({
    TResult? Function(TokenEvent value)? token,
    TResult? Function(ToolCallEvent value)? toolCall,
    TResult? Function(ToolResultEvent value)? toolResult,
    TResult? Function(CitationEvent value)? citation,
    TResult? Function(UiHintEvent value)? uiHint,
    TResult? Function(EndEvent value)? end,
  }) {
    return end?.call(this);
  }

  @override
  @optionalTypeArgs
  TResult maybeMap<TResult extends Object?>({
    TResult Function(TokenEvent value)? token,
    TResult Function(ToolCallEvent value)? toolCall,
    TResult Function(ToolResultEvent value)? toolResult,
    TResult Function(CitationEvent value)? citation,
    TResult Function(UiHintEvent value)? uiHint,
    TResult Function(EndEvent value)? end,
    required TResult orElse(),
  }) {
    if (end != null) {
      return end(this);
    }
    return orElse();
  }
}

abstract class EndEvent implements TurnEventDto {
  const factory EndEvent({
    final String? turnId,
    final int? tokensIn,
    final int? tokensOut,
  }) = _$EndEventImpl;

  String? get turnId;
  int? get tokensIn;
  int? get tokensOut;

  /// Create a copy of TurnEventDto
  /// with the given fields replaced by the non-null parameter values.
  @JsonKey(includeFromJson: false, includeToJson: false)
  _$$EndEventImplCopyWith<_$EndEventImpl> get copyWith =>
      throw _privateConstructorUsedError;
}

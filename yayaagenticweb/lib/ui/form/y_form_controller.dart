import 'dart:async';
import 'dart:convert';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../feedback/api_error.dart';

typedef FormSnapshot = Map<String, Object?>;

/// One editor's form state: what's valid, what's been touched, what the
/// server rejected, and what changed since it was loaded.
///
/// Values stay where the screen keeps them (controllers, fields); the screen
/// hands over a [snapshot] (for dirty tracking and drafts) and a [validate]
/// function (field → message, null = fine). Validation is shown on blur, then
/// live once a field has erred, and for every field after a submit attempt.
class YFormController extends ChangeNotifier {
  YFormController({
    required FormSnapshot Function() snapshot,
    required Map<String, String?> Function() validate,
    this.labels = const {},
    this.draftKey,
    this.excludeFromDraft = const {},
    this.baseVersion,
  })  : _snapshot = snapshot,
        _validate = validate {
    _initial = _normalize(_snapshot());
  }

  final FormSnapshot Function() _snapshot;
  final Map<String, String?> Function() _validate;

  /// Human labels for the error summary, keyed by field name.
  final Map<String, String> labels;

  /// Where an unsaved draft is kept (per workspace/tenant/kind/id). Null = no drafts.
  final String? draftKey;

  /// Never persisted to a draft — secrets.
  final Set<String> excludeFromDraft;

  /// The version this editor started from; sent as `baseVersion` on save.
  int? baseVersion;

  late Map<String, String> _initial;
  final Set<String> _touched = {};
  final Set<String> _erred = {};
  final Map<String, String> _serverErrors = {};
  final Map<String, GlobalKey> _keys = {};
  bool _submitted = false;
  bool _saving = false;
  String? _formError;
  Timer? _draftTimer;

  bool get submitted => _submitted;
  bool get saving => _saving;
  String? get formError => _formError;

  set saving(bool v) {
    _saving = v;
    notifyListeners();
  }

  GlobalKey keyFor(String field) => _keys.putIfAbsent(field, () => GlobalKey(debugLabel: 'field:$field'));

  Map<String, String> _normalize(FormSnapshot s) => s.map((k, v) => MapEntry(k, jsonEncode(v)));

  /// Fields whose value differs from when the form was loaded or last saved.
  Set<String> get changedFields {
    final now = _normalize(_snapshot());
    return {
      for (final k in {...now.keys, ..._initial.keys})
        if (now[k] != _initial[k]) k,
    };
  }

  int get dirtyCount => changedFields.length;
  bool get isDirty => dirtyCount > 0;

  String get dirtyLabel => dirtyCount == 1 ? '1 unsaved change' : '$dirtyCount unsaved changes';

  Map<String, String> get _allErrors {
    final out = <String, String>{};
    _validate().forEach((k, v) {
      if (v != null) out[k] = v;
    });
    _serverErrors.forEach((k, v) => out.putIfAbsent(k, () => v));
    return out;
  }

  /// The error to show under [field] right now, if any.
  String? errorFor(String field) {
    final server = _serverErrors[field];
    if (server != null) return server;
    if (!_submitted && !_touched.contains(field) && !_erred.contains(field)) return null;
    final msg = _validate()[field];
    if (msg != null) _erred.add(field);
    return msg;
  }

  /// Errors for the summary at the top: only after a submit attempt.
  List<({String field, String label, String message})> get summary {
    if (!_submitted && _serverErrors.isEmpty) return const [];
    final errs = _submitted ? _allErrors : Map.of(_serverErrors);
    return [
      for (final e in errs.entries) (field: e.key, label: labels[e.key] ?? e.key, message: e.value),
    ];
  }

  void touch(String field) {
    if (_touched.add(field)) notifyListeners();
  }

  /// Call from a field's onChanged: clears what the server said about it and
  /// schedules a draft save.
  void changed(String field) {
    _serverErrors.remove(field);
    _formError = null;
    _scheduleDraft();
    notifyListeners();
  }

  /// Call after programmatic changes (toggles, pickers) that aren't tied to one field.
  void refresh() {
    _scheduleDraft();
    notifyListeners();
  }

  /// Marks a submit attempt. Returns true when the form is valid; otherwise
  /// the summary shows and focus moves to the first invalid field.
  bool submit() {
    _submitted = true;
    _formError = null;
    final errors = _allErrors;
    notifyListeners();
    if (errors.isEmpty) return true;
    final view = ui.PlatformDispatcher.instance.implicitView;
    if (view != null) {
      SemanticsService.sendAnnouncement(
        view,
        errors.length == 1 ? '1 field needs attention' : '${errors.length} fields need attention',
        TextDirection.ltr,
      );
    }
    WidgetsBinding.instance.addPostFrameCallback((_) => revealFirstError(errors.keys));
    return false;
  }

  /// Key for the error summary, so a failed submit shows it before the fields.
  final summaryKey = GlobalKey(debugLabel: 'form-error-summary');

  /// Brings the error summary into view, then — only if it's still off-screen
  /// below — the first invalid field.
  Future<void> revealFirstError([Iterable<String>? fields]) async {
    const d = Duration(milliseconds: 200);
    final summary = summaryKey.currentContext;
    if (summary != null && summary.mounted) await Scrollable.ensureVisible(summary, duration: d, alignment: 0.05);
    for (final f in fields ?? _allErrors.keys) {
      final ctx = _keys[f]?.currentContext;
      if (ctx != null && ctx.mounted) {
        await Scrollable.ensureVisible(
          ctx,
          duration: d,
          alignment: summary == null ? 0.15 : 1,
          alignmentPolicy: summary == null ? ScrollPositionAlignmentPolicy.explicit : ScrollPositionAlignmentPolicy.keepVisibleAtEnd,
        );
        return;
      }
    }
  }

  /// Scroll to one field (an error summary link).
  void revealField(String field) {
    final ctx = _keys[field]?.currentContext;
    if (ctx != null) Scrollable.ensureVisible(ctx, duration: const Duration(milliseconds: 200), alignment: 0.15);
  }

  /// Puts what the server rejected next to the fields it named; anything
  /// else becomes the form-level message.
  void applyServerError(Object error) {
    final info = ApiErrorInfo.from(error);
    _serverErrors
      ..clear()
      ..addAll(info.fieldErrors);
    _formError = info.fieldErrors.isEmpty || info.isVersionConflict ? info.message : null;
    _submitted = true;
    notifyListeners();
    WidgetsBinding.instance.addPostFrameCallback((_) => revealFirstError(info.fieldErrors.keys));
  }

  /// After a successful save or a reload: the current values become the baseline.
  void markClean({int? baseVersion}) {
    _initial = _normalize(_snapshot());
    _touched.clear();
    _erred.clear();
    _serverErrors.clear();
    _submitted = false;
    _formError = null;
    if (baseVersion != null) this.baseVersion = baseVersion;
    clearDraft();
    notifyListeners();
  }

  // ---- drafts --------------------------------------------------------

  void _scheduleDraft() {
    if (draftKey == null) return;
    _draftTimer?.cancel();
    _draftTimer = Timer(const Duration(milliseconds: 1200), _saveDraft);
  }

  DateTime? _draftSavedAt;
  DateTime? get draftSavedAt => _draftSavedAt;

  Future<void> _saveDraft() async {
    final key = draftKey;
    if (key == null) return;
    final prefs = await SharedPreferences.getInstance();
    if (!isDirty) {
      await prefs.remove(key);
      return;
    }
    final snap = Map.of(_snapshot())..removeWhere((k, _) => excludeFromDraft.contains(k));
    _draftSavedAt = DateTime.now();
    await prefs.setString(key, jsonEncode({'savedAt': _draftSavedAt!.toIso8601String(), 'values': snap}));
    notifyListeners();
  }

  /// A draft left from an earlier visit, if one exists.
  Future<({DateTime savedAt, FormSnapshot values})?> loadDraft() async {
    final key = draftKey;
    if (key == null) return null;
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(key);
    if (raw == null) return null;
    try {
      final json = jsonDecode(raw) as Map<String, dynamic>;
      return (
        savedAt: DateTime.parse(json['savedAt'] as String),
        values: (json['values'] as Map).cast<String, Object?>(),
      );
    } catch (_) {
      await prefs.remove(key);
      return null;
    }
  }

  Future<void> clearDraft() async {
    _draftTimer?.cancel();
    final key = draftKey;
    if (key == null) return;
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(key);
  }

  @override
  void dispose() {
    _draftTimer?.cancel();
    super.dispose();
  }
}

/// Field rules the backend enforces, phrased for the operator.
abstract final class Validators {
  static String? required(String? v, String what) =>
      (v == null || v.trim().isEmpty) ? 'Enter $what.' : null;

  static final _resourceId = RegExp(r'^[a-zA-Z0-9_-]{1,128}$');

  /// Tool ids are also model tool names: letters, digits, `_` and `-` only.
  static String? toolId(String? v) {
    final r = required(v, 'a tool ID');
    if (r != null) return r;
    if (!_resourceId.hasMatch(v!.trim())) {
      return 'Use letters, digits, underscores or dashes (no dots or spaces), up to 128 characters.';
    }
    return null;
  }

  static final _slug = RegExp(r'^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$');

  static String? slugId(String? v, String what) {
    final r = required(v, what);
    if (r != null) return r;
    if (!_slug.hasMatch(v!.trim())) {
      return 'Start with a letter or digit; then letters, digits, dots, underscores or dashes.';
    }
    return null;
  }

  /// JSON that must decode to an object. Returns (error, decoded).
  static (String?, Map<String, dynamic>?) jsonObject(String? v, {bool allowEmpty = false}) {
    if (v == null || v.trim().isEmpty) return (allowEmpty ? null : 'Enter a JSON object.', null);
    try {
      final decoded = jsonDecode(v);
      if (decoded is! Map<String, dynamic>) return ('Must be a JSON object ({ … }).', null);
      return (null, decoded);
    } on FormatException catch (e) {
      return ('Not valid JSON — ${e.message}${e.offset != null ? ' (at character ${e.offset})' : ''}.', null);
    }
  }

  static String? intInRange(String? v, int min, int max, {bool optional = true}) {
    if (v == null || v.trim().isEmpty) return optional ? null : 'Enter a number.';
    final n = int.tryParse(v.trim());
    if (n == null) return 'Enter a whole number.';
    if (n < min || n > max) return 'Use a number from $min to $max.';
    return null;
  }

  static String? httpUrl(String? v, {bool optional = false}) {
    if (v == null || v.trim().isEmpty) return optional ? null : 'Enter a URL.';
    final u = Uri.tryParse(v.trim());
    if (u == null || !(u.isScheme('https') || u.isScheme('http')) || u.host.isEmpty) {
      return 'Enter a full URL starting with https://';
    }
    return null;
  }
}

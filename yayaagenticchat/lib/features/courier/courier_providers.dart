import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../data/providers.dart';
import 'courier_api.dart';
import 'courier_models.dart';

/// Where the courier UI stands. Derived from the stored token + `/me` — see
/// [phaseForStatus].
enum CourierPhase {
  /// No token yet: invite + registration form.
  register,

  /// Registered: waiting on the phone OTP.
  otp,

  /// Token held, PENDING/DOCS_SUBMITTED: upload KYC documents.
  docs,

  /// UNDER_REVIEW: waiting on the merchant, poll /me.
  review,

  /// REJECTED or SUSPENDED: terminal status screen.
  blocked,

  /// APPROVED: the working surface (assignments, status advance, COD).
  approved,

  /// A held token stopped authenticating (approval ROTATES the credential —
  /// the operational token is shown to the merchant, who hands it over):
  /// ask the courier to paste the new one.
  reauth,

  /// Fetching /me with a stored token.
  loading,
}

/// Pure mapping from the `/me` status to a phase — unit-testable state logic.
CourierPhase phaseForStatus(String status) {
  switch (status) {
    case 'PENDING':
    case 'DOCS_SUBMITTED':
      return CourierPhase.docs;
    case 'UNDER_REVIEW':
      return CourierPhase.review;
    case 'APPROVED':
      return CourierPhase.approved;
    case 'REJECTED':
    case 'SUSPENDED':
      return CourierPhase.blocked;
    default:
      return CourierPhase.docs;
  }
}

class CourierState {
  final CourierPhase phase;
  final String? token;
  final String? agentId;
  final String? phone;

  /// Dev/test only: the OTP echoed by the register response.
  final String? otpEcho;
  final CourierProfile? me;
  final List<CourierAssignment> assignments;
  final bool busy;
  final String? error;

  /// One-shot success banner (e.g. "COD collected").
  final String? notice;

  const CourierState({
    this.phase = CourierPhase.register,
    this.token,
    this.agentId,
    this.phone,
    this.otpEcho,
    this.me,
    this.assignments = const [],
    this.busy = false,
    this.error,
    this.notice,
  });

  CourierState copyWith({
    CourierPhase? phase,
    String? token,
    String? agentId,
    String? phone,
    String? otpEcho,
    CourierProfile? me,
    List<CourierAssignment>? assignments,
    bool? busy,
    String? error,
    String? notice,
    bool clearError = false,
    bool clearNotice = false,
    bool clearToken = false,
  }) =>
      CourierState(
        phase: phase ?? this.phase,
        token: clearToken ? null : (token ?? this.token),
        agentId: agentId ?? this.agentId,
        phone: phone ?? this.phone,
        otpEcho: otpEcho ?? this.otpEcho,
        me: me ?? this.me,
        assignments: assignments ?? this.assignments,
        busy: busy ?? this.busy,
        error: clearError ? null : (error ?? this.error),
        notice: clearNotice ? null : (notice ?? this.notice),
      );
}

/// Token persistence: the courier credential is a bearer shown once by
/// yaya-sales (registration-scoped first; approval rotates it to the
/// operational one) — losing it means re-pasting, so it lives in prefs.
class CourierStore {
  static const _tokenKey = 'yaya.courier.token';
  static const _agentKey = 'yaya.courier.agentId';
  static const _phoneKey = 'yaya.courier.phone';

  final SharedPreferences _prefs;
  CourierStore(this._prefs);

  String? token() => _prefs.getString(_tokenKey);
  String? agentId() => _prefs.getString(_agentKey);
  String? phone() => _prefs.getString(_phoneKey);

  Future<void> saveToken(String token, {String? agentId}) async {
    await _prefs.setString(_tokenKey, token);
    if (agentId != null && agentId.isNotEmpty) await _prefs.setString(_agentKey, agentId);
  }

  Future<void> savePhone(String phone) => _prefs.setString(_phoneKey, phone);

  Future<void> clearToken() => _prefs.remove(_tokenKey);

  Future<void> clearAll() async {
    await _prefs.remove(_tokenKey);
    await _prefs.remove(_agentKey);
    await _prefs.remove(_phoneKey);
  }
}

final courierStoreProvider =
    Provider<CourierStore>((ref) => CourierStore(ref.watch(sharedPrefsProvider)));

final courierApiProvider = Provider<CourierApi>((ref) => CourierApi());

class CourierNotifier extends StateNotifier<CourierState> {
  CourierNotifier(this._api, this._store)
      : super(CourierState(
          phase: _store.token() == null ? CourierPhase.register : CourierPhase.loading,
          token: _store.token(),
          agentId: _store.agentId(),
          phone: _store.phone(),
        )) {
    if (state.token != null) refreshMe();
  }

  final CourierApi _api;
  final CourierStore _store;

  /* ---------------- registration (invite → form → OTP → token) ----------- */

  Future<void> register({
    required String inviteToken,
    required String legalName,
    required Map<String, String?> legalAddress,
    required String phone,
    required Map<String, String?> vehicle,
  }) async {
    state = state.copyWith(busy: true, clearError: true);
    try {
      final registration = await _api.register(
        inviteToken: inviteToken,
        legalName: legalName,
        legalAddress: legalAddress,
        phone: phone,
        vehicle: vehicle,
      );
      await _store.savePhone(phone);
      state = state.copyWith(
        busy: false,
        phase: CourierPhase.otp,
        agentId: registration.agentId,
        phone: phone,
        otpEcho: registration.otpEcho,
      );
    } on CourierApiException catch (e) {
      state = state.copyWith(busy: false, error: e.message);
    }
  }

  Future<void> verifyOtp(String phone, String otp) async {
    state = state.copyWith(busy: true, clearError: true);
    try {
      final credential = await _api.verify(phone, otp);
      await _store.saveToken(credential.token, agentId: credential.agentId);
      await _store.savePhone(phone);
      state = state.copyWith(
        busy: false,
        token: credential.token,
        agentId: credential.agentId,
        phone: phone,
      );
      await refreshMe();
    } on CourierApiException catch (e) {
      state = state.copyWith(busy: false, error: e.message);
    }
  }

  /// Registered on another device / lost prefs / approval handed over the
  /// rotated operational token — adopt a pasted bearer and probe /me with it.
  Future<void> adoptToken(String token) async {
    final trimmed = token.trim();
    if (trimmed.isEmpty) return;
    await _store.saveToken(trimmed);
    state = state.copyWith(token: trimmed, phase: CourierPhase.loading, clearError: true);
    await refreshMe();
  }

  /* ------------------------------- /me ----------------------------------- */

  Future<void> refreshMe() async {
    final token = state.token;
    if (token == null) {
      state = state.copyWith(phase: CourierPhase.register);
      return;
    }
    state = state.copyWith(busy: true, clearError: true);
    try {
      final me = await _api.me(token);
      final phase = phaseForStatus(me.status);
      state = state.copyWith(busy: false, me: me, agentId: me.agentId, phase: phase);
      if (phase == CourierPhase.approved) await refreshAssignments();
    } on CourierApiException catch (e) {
      if (e.unauthorized) {
        await _onRotatedCredential();
      } else {
        state = state.copyWith(
            busy: false,
            error: e.message,
            // Keep a usable screen even when /me fails transiently.
            phase: state.me == null ? CourierPhase.reauth : state.phase);
      }
    }
  }

  /// Approval rotates the credential (§10.4): the stored token dies and the
  /// merchant holds the new operational one. Drop the dead token and ask for it.
  Future<void> _onRotatedCredential() async {
    await _store.clearToken();
    state = state.copyWith(
      busy: false,
      clearToken: true,
      phase: CourierPhase.reauth,
      error: 'Your access token changed. Paste the new one to continue.',
    );
  }

  /* ------------------------------ documents ------------------------------ */

  /// v1 document capture: per-kind text content, base64-encoded to the API's
  /// `{kind, contentBase64}` shape (the backend encrypts it at rest).
  Future<void> submitDocuments(Map<String, String> contentByKind) async {
    final token = state.token;
    final agentId = state.agentId;
    if (token == null || agentId == null) return;
    final documents = [
      for (final e in contentByKind.entries)
        if (e.value.trim().isNotEmpty)
          {'kind': e.key, 'contentBase64': base64Encode(utf8.encode(e.value.trim()))},
    ];
    if (documents.isEmpty) {
      state = state.copyWith(error: 'Add at least one document.');
      return;
    }
    state = state.copyWith(busy: true, clearError: true);
    try {
      await _api.submitDocuments(token, agentId, documents);
      await refreshMe();
      state = state.copyWith(notice: 'Documents submitted.');
    } on CourierApiException catch (e) {
      if (e.unauthorized) {
        await _onRotatedCredential();
      } else {
        state = state.copyWith(busy: false, error: e.message);
      }
    }
  }

  /* ---------------------------- the working day -------------------------- */

  Future<void> refreshAssignments() async {
    final token = state.token;
    if (token == null) return;
    try {
      final assignments = await _api.assignments(token);
      state = state.copyWith(busy: false, assignments: assignments);
    } on CourierApiException catch (e) {
      if (e.unauthorized) {
        await _onRotatedCredential();
      } else {
        state = state.copyWith(busy: false, error: e.message);
      }
    }
  }

  Future<void> acceptAssignment(String assignmentId) =>
      _act((token) => _api.accept(token, assignmentId));

  Future<void> advance(String assignmentId, String status,
          {String? notes, String? otp}) =>
      _act((token) => _api.updateStatus(token, assignmentId, status, proof: {
            if (notes != null && notes.trim().isNotEmpty) 'notes': notes.trim(),
            if (otp != null && otp.trim().isNotEmpty) 'otp': otp.trim(),
          }));

  Future<void> collectCod(String assignmentId, int amountMinor) => _act((token) async {
        final collected = await _api.collectCod(token, assignmentId, amountMinor);
        state = state.copyWith(
            notice: collected.alreadyCollected
                ? 'This cash payment was already recorded.'
                : 'Cash collected — order #${_short(collected.orderId)} is paid.');
      });

  Future<void> _act(Future<void> Function(String token) call) async {
    final token = state.token;
    if (token == null) return;
    state = state.copyWith(busy: true, clearError: true, clearNotice: true);
    try {
      await call(token);
      await refreshAssignments();
    } on CourierApiException catch (e) {
      if (e.unauthorized) {
        await _onRotatedCredential();
      } else {
        state = state.copyWith(busy: false, error: e.message);
      }
    }
  }

  void dismissNotice() => state = state.copyWith(clearNotice: true);

  /// "I already have a token" — jump to the paste-a-token screen.
  void showTokenEntry() =>
      state = state.copyWith(phase: CourierPhase.reauth, clearError: true);

  /// Back to registration from the token-entry screen.
  void showRegistration() =>
      state = state.copyWith(phase: CourierPhase.register, clearError: true);

  /// Forget this courier identity on the device (does not revoke server-side).
  Future<void> exitCourierMode() async {
    await _store.clearAll();
    state = const CourierState();
  }

  static String _short(String id) {
    final bare = id.replaceAll('-', '');
    return bare.length <= 8 ? bare : bare.substring(0, 8);
  }
}

final courierProvider = StateNotifierProvider<CourierNotifier, CourierState>(
  (ref) => CourierNotifier(ref.watch(courierApiProvider), ref.watch(courierStoreProvider)),
);

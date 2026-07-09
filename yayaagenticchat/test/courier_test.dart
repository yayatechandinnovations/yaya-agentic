import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticchat/data/providers.dart';
import 'package:yayaagenticchat/features/courier/courier_api.dart';
import 'package:yayaagenticchat/features/courier/courier_models.dart';
import 'package:yayaagenticchat/features/courier/courier_providers.dart';

/// Courier-mode state logic (yaya-sales-design.md §10.4/§11.5): wire parsing
/// mirrors the courier controllers' JSON, and the notifier walks
/// register → OTP → docs/review → approved, dropping to token re-entry on 401
/// (approval ROTATES the credential).
void main() {
  group('CourierProfile.fromJson', () {
    test('parses /me with documents and reason', () {
      final me = CourierProfile.fromJson({
        'agentId': 'ag-1',
        'legalName': 'Ana Perez',
        'status': 'REJECTED',
        'phoneVerified': true,
        'documents': [
          {'id': 'd1', 'kind': 'PHOTO_ID_FRONT', 'status': 'SUBMITTED', 'uploadedAt': 'x'},
        ],
        'reason': 'blurry ID',
      });
      expect(me.agentId, 'ag-1');
      expect(me.status, 'REJECTED');
      expect(me.phoneVerified, true);
      expect(me.documents.single.kind, 'PHOTO_ID_FRONT');
      expect(me.reason, 'blurry ID');
      expect(me.approved, false);
    });
  });

  group('CourierAssignment.fromJson', () {
    test('parses the assignment payload with shipment, shipTo and COD', () {
      final a = CourierAssignment.fromJson({
        'assignmentId': 'as-1',
        'status': 'ACCEPTED',
        'assignedAt': '2026-07-08T10:00:00Z',
        'shipment': {
          'shipmentId': 'sh-1',
          'orderId': 'ab12cd34-5678-90ef-aaaa-bbbbccccdddd',
          'kind': 'LOCAL_DELIVERY',
          'deliveryStatus': 'OUT_FOR_DELIVERY',
        },
        'shipTo': {'line1': 'Calle 1 #23', 'city': 'Santo Domingo', 'postalCode': '10101'},
        'codDueMinor': 125000,
      });
      expect(a.accepted, true);
      expect(a.codDue, true);
      expect(a.shipment!.shortOrderId, 'ab12cd34');
      expect(a.shipToLine, 'Calle 1 #23, Santo Domingo, 10101');
    });

    test('tolerates a bare assignment without shipment or COD', () {
      final a = CourierAssignment.fromJson({'assignmentId': 'as-2', 'status': 'ASSIGNED'});
      expect(a.codDue, false);
      expect(a.shipment, isNull);
      expect(a.shipToLine, '');
    });
  });

  group('CodCollectionResult.fromJson', () {
    test('maps ALREADY_COLLECTED to the idempotent flag', () {
      final r = CodCollectionResult.fromJson({
        'orderId': 'o-1', 'amountMinor': 5000, 'currency': 'DOP', 'status': 'ALREADY_COLLECTED',
      });
      expect(r.alreadyCollected, true);
      expect(r.amountMinor, 5000);
    });
  });

  group('phaseForStatus', () {
    test('maps every /me status to its screen', () {
      expect(phaseForStatus('PENDING'), CourierPhase.docs);
      expect(phaseForStatus('DOCS_SUBMITTED'), CourierPhase.docs);
      expect(phaseForStatus('UNDER_REVIEW'), CourierPhase.review);
      expect(phaseForStatus('APPROVED'), CourierPhase.approved);
      expect(phaseForStatus('REJECTED'), CourierPhase.blocked);
      expect(phaseForStatus('SUSPENDED'), CourierPhase.blocked);
    });
  });

  group('CourierNotifier', () {
    late FakeCourierApi api;
    late ProviderContainer container;
    late SharedPreferences prefs;

    Future<void> setUpContainer() async {
      SharedPreferences.setMockInitialValues({});
      prefs = await SharedPreferences.getInstance();
      api = FakeCourierApi();
      container = ProviderContainer(overrides: [
        sharedPrefsProvider.overrideWithValue(prefs),
        courierApiProvider.overrideWithValue(api),
      ]);
      addTearDown(container.dispose);
    }

    test('walks register → OTP → verified token → review', () async {
      await setUpContainer();
      final notifier = container.read(courierProvider.notifier);
      expect(container.read(courierProvider).phase, CourierPhase.register);

      await notifier.register(
        inviteToken: 'inv-1',
        legalName: 'Ana Perez',
        legalAddress: {'line1': 'x', 'city': 'y', 'region': 'z', 'postalCode': '1', 'country': 'DO'},
        phone: '+18095551234',
        vehicle: {'kind': 'bike', 'make': 'BMX', 'model': 'X', 'plate': 'A1'},
      );
      expect(container.read(courierProvider).phase, CourierPhase.otp);
      expect(container.read(courierProvider).otpEcho, '123456');

      api.meStatus = 'UNDER_REVIEW';
      await notifier.verifyOtp('+18095551234', '123456');
      final state = container.read(courierProvider);
      expect(state.phase, CourierPhase.review);
      expect(state.token, 'tok-registration');
      // Persisted: a reload resumes with the same credential.
      expect(prefs.getString('yaya.courier.token'), 'tok-registration');
    });

    test('drops to token re-entry on 401 (approval rotated the credential)', () async {
      await setUpContainer();
      final notifier = container.read(courierProvider.notifier);
      await notifier.adoptToken('tok-registration');
      expect(container.read(courierProvider).phase, CourierPhase.review);

      api.throw401 = true;
      await notifier.refreshMe();
      final state = container.read(courierProvider);
      expect(state.phase, CourierPhase.reauth);
      expect(state.token, isNull);
      expect(prefs.getString('yaya.courier.token'), isNull);
    });

    test('adopting the operational token lands on assignments', () async {
      await setUpContainer();
      api.meStatus = 'APPROVED';
      api.assignmentsPayload = [
        CourierAssignment.fromJson({
          'assignmentId': 'as-1',
          'status': 'ASSIGNED',
          'shipment': {'shipmentId': 'sh-1', 'orderId': 'o-1', 'kind': 'LOCAL_DELIVERY'},
        }),
      ];
      final notifier = container.read(courierProvider.notifier);
      await notifier.adoptToken('tok-operational');

      final state = container.read(courierProvider);
      expect(state.phase, CourierPhase.approved);
      expect(state.assignments, hasLength(1));
      expect(prefs.getString('yaya.courier.token'), 'tok-operational');
    });
  });
}

class FakeCourierApi extends CourierApi {
  FakeCourierApi();

  String meStatus = 'UNDER_REVIEW';
  bool throw401 = false;
  List<CourierAssignment> assignmentsPayload = const [];

  @override
  Future<CourierRegistration> register({
    required String inviteToken,
    required String legalName,
    required Map<String, String?> legalAddress,
    required String phone,
    required Map<String, String?> vehicle,
  }) async =>
      const CourierRegistration(agentId: 'ag-1', status: 'PENDING', otpEcho: '123456');

  @override
  Future<({String agentId, String token})> verify(String phone, String otp) async =>
      (agentId: 'ag-1', token: 'tok-registration');

  @override
  Future<CourierProfile> me(String token) async {
    if (throw401) throw CourierApiException(401, 'invalid courier token');
    return CourierProfile(
        agentId: 'ag-1', legalName: 'Ana Perez', status: meStatus, phoneVerified: true);
  }

  @override
  Future<List<CourierAssignment>> assignments(String token) async {
    if (throw401) throw CourierApiException(401, 'invalid courier token');
    return assignmentsPayload;
  }
}

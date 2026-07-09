/// Wire models for the yaya-sales courier surface (yaya-sales-design.md §10.4,
/// §11.5). Couriers are a fourth caller class: these mirror the courier
/// controllers' JSON, not anything from yaya-agentic.
library;

/// KYC document kinds the fleet accepts (DeliveryFleet.DocKind). Approval
/// requires a photo ID (front or license) plus a vehicle document
/// (registration or insurance).
const courierDocKinds = [
  'PHOTO_ID_FRONT',
  'PHOTO_ID_BACK',
  'DRIVER_LICENSE',
  'VEHICLE_REGISTRATION',
  'INSURANCE',
];

/// GET /v1/sales/delivery/me — where the courier stands.
class CourierProfile {
  final String agentId;
  final String legalName;
  final String status; // PENDING | DOCS_SUBMITTED | UNDER_REVIEW | APPROVED | REJECTED | SUSPENDED
  final bool phoneVerified;
  final List<CourierDocument> documents;
  final String? reason; // reject/suspend reason, when present

  const CourierProfile({
    required this.agentId,
    required this.legalName,
    required this.status,
    required this.phoneVerified,
    this.documents = const [],
    this.reason,
  });

  bool get approved => status == 'APPROVED';

  factory CourierProfile.fromJson(Map<String, dynamic> j) => CourierProfile(
        agentId: (j['agentId'] ?? '').toString(),
        legalName: (j['legalName'] ?? '').toString(),
        status: (j['status'] ?? 'PENDING').toString(),
        phoneVerified: (j['phoneVerified'] ?? false) as bool,
        documents: [
          for (final d in (j['documents'] as List? ?? const []))
            CourierDocument.fromJson(Map<String, dynamic>.from(d as Map)),
        ],
        reason: j['reason'] as String?,
      );
}

class CourierDocument {
  final String id;
  final String kind;
  final String status;

  const CourierDocument({required this.id, required this.kind, required this.status});

  factory CourierDocument.fromJson(Map<String, dynamic> j) => CourierDocument(
        id: _idOf(j['id']),
        kind: _idOf(j['kind']),
        status: (j['status'] ?? '').toString(),
      );
}

/// One entry of GET /v1/sales/delivery/assignments (+ accept/status responses).
class CourierAssignment {
  final String assignmentId;
  final String status; // ASSIGNED | ACCEPTED | COMPLETED | REASSIGNED
  final String? assignedAt;
  final AssignmentShipment? shipment;
  final Map<String, dynamic>? shipTo;
  final int? codDueMinor;

  const CourierAssignment({
    required this.assignmentId,
    required this.status,
    this.assignedAt,
    this.shipment,
    this.shipTo,
    this.codDueMinor,
  });

  bool get accepted => status == 'ACCEPTED';
  bool get codDue => codDueMinor != null && codDueMinor! > 0;

  /// The ship-to address as one printable line; empty when absent.
  String get shipToLine {
    final a = shipTo;
    if (a == null) return '';
    final parts = [a['line1'], a['line2'], a['city'], a['region'], a['postalCode']]
        .whereType<String>()
        .where((s) => s.isNotEmpty)
        .toList();
    return parts.join(', ');
  }

  factory CourierAssignment.fromJson(Map<String, dynamic> j) => CourierAssignment(
        assignmentId: _idOf(j['assignmentId']),
        status: (j['status'] ?? 'ASSIGNED').toString(),
        assignedAt: j['assignedAt'] as String?,
        shipment: j['shipment'] is Map
            ? AssignmentShipment.fromJson(Map<String, dynamic>.from(j['shipment'] as Map))
            : null,
        shipTo: j['shipTo'] is Map ? Map<String, dynamic>.from(j['shipTo'] as Map) : null,
        codDueMinor: (j['codDueMinor'] as num?)?.toInt(),
      );
}

class AssignmentShipment {
  final String shipmentId;
  final String orderId;
  final String kind;
  final String? deliveryStatus;
  final String? promisedFrom;
  final String? promisedTo;

  const AssignmentShipment({
    required this.shipmentId,
    required this.orderId,
    required this.kind,
    this.deliveryStatus,
    this.promisedFrom,
    this.promisedTo,
  });

  String get shortOrderId {
    final bare = orderId.replaceAll('-', '');
    return bare.length <= 8 ? bare : bare.substring(0, 8);
  }

  factory AssignmentShipment.fromJson(Map<String, dynamic> j) => AssignmentShipment(
        shipmentId: _idOf(j['shipmentId']),
        orderId: _idOf(j['orderId']),
        kind: (j['kind'] ?? '').toString(),
        deliveryStatus: j['deliveryStatus'] as String?,
        promisedFrom: j['promisedFrom'] as String?,
        promisedTo: j['promisedTo'] as String?,
      );
}

/// POST /v1/sales/delivery/assignments/{id}/collect-cod response.
class CodCollectionResult {
  final String orderId;
  final int amountMinor;
  final String currency;
  final bool alreadyCollected;

  const CodCollectionResult({
    required this.orderId,
    required this.amountMinor,
    required this.currency,
    required this.alreadyCollected,
  });

  factory CodCollectionResult.fromJson(Map<String, dynamic> j) => CodCollectionResult(
        orderId: _idOf(j['orderId']),
        amountMinor: (j['amountMinor'] as num?)?.toInt() ?? 0,
        currency: (j['currency'] ?? 'USD').toString(),
        alreadyCollected: (j['status'] ?? '') == 'ALREADY_COLLECTED',
      );
}

/// POST /v1/sales/delivery/agents/register response. `otp` echoes only when
/// the backend runs with otp-echo-enabled (dev/test) — production delivers it
/// out-of-band (SMS).
class CourierRegistration {
  final String agentId;
  final String status;
  final String? otpEcho;

  const CourierRegistration({required this.agentId, required this.status, this.otpEcho});

  factory CourierRegistration.fromJson(Map<String, dynamic> j) => CourierRegistration(
        agentId: _idOf(j['agentId']),
        status: (j['status'] ?? 'PENDING').toString(),
        otpEcho: j['otp'] as String?,
      );
}

/// Typed ids may arrive as bare strings OR `{value: …}` (same tolerance the
/// commerce models apply).
String _idOf(dynamic v) {
  if (v is Map && v['value'] != null) return v['value'].toString();
  return (v ?? '').toString();
}

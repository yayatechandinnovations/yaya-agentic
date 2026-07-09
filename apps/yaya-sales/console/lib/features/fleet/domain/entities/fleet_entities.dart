import 'package:equatable/equatable.dart';

class FleetInvite extends Equatable {
  const FleetInvite({required this.token, this.expiresAt});

  final String token;
  final DateTime? expiresAt;

  @override
  List<Object?> get props => [token, expiresAt];
}

class AgentDocument extends Equatable {
  const AgentDocument({
    required this.id,
    required this.kind,
    this.status,
    this.uploadedAt,
  });

  final String id;
  final String kind; // PHOTO_ID_FRONT | … | OTHER
  final String? status;
  final DateTime? uploadedAt;

  @override
  List<Object?> get props => [id, kind, status, uploadedAt];
}

class AgentVehicle extends Equatable {
  const AgentVehicle({this.kind, this.make, this.model, this.plate});

  final String? kind;
  final String? make;
  final String? model;
  final String? plate;

  String get display =>
      [kind, make, model, plate].where((p) => p != null && p.isNotEmpty).join(' · ');

  @override
  List<Object?> get props => [kind, make, model, plate];
}

class DeliveryAgent extends Equatable {
  const DeliveryAgent({
    required this.id,
    required this.legalName,
    required this.status,
    this.phone,
    this.phoneVerified = false,
    this.legalAddress,
    this.vehicle,
    this.idDocType,
    this.idDocNumber,
    this.documents = const [],
    this.registeredAt,
    this.approvedAt,
    this.reviewedBy,
    this.rejectReason,
  });

  final String id;
  final String legalName;
  final String status;
  final String? phone;
  final bool phoneVerified;
  final String? legalAddress;
  final AgentVehicle? vehicle;
  final String? idDocType;
  final String? idDocNumber;
  final List<AgentDocument> documents;
  final DateTime? registeredAt;
  final DateTime? approvedAt;
  final String? reviewedBy;
  final String? rejectReason;

  static const statuses = [
    'PENDING',
    'DOCS_SUBMITTED',
    'UNDER_REVIEW',
    'APPROVED',
    'REJECTED',
    'SUSPENDED',
  ];

  @override
  List<Object?> get props => [
        id,
        legalName,
        status,
        phone,
        phoneVerified,
        legalAddress,
        vehicle,
        idDocType,
        idDocNumber,
        documents,
        registeredAt,
        approvedAt,
        reviewedBy,
        rejectReason,
      ];
}

/// The one-time courier credential minted at approval.
class CourierCredential extends Equatable {
  const CourierCredential({required this.agentId, required this.token});

  final String agentId;
  final String token;

  @override
  List<Object?> get props => [agentId, token];
}

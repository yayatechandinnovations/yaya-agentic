import '../../../../core/network/json.dart';
import '../../domain/entities/fleet_entities.dart';

FleetInvite inviteFromJson(Map<String, dynamic> json) => FleetInvite(
      token: '${json['inviteToken'] ?? json['token'] ?? ''}',
      expiresAt: dateOf(json['expiresAt']),
    );

DeliveryAgent agentFromJson(Map<String, dynamic> json) {
  final vehicle = json['vehicle'] == null ? null : mapOf(json['vehicle']);
  final idDoc = json['idDoc'] == null ? null : mapOf(json['idDoc']);
  final address = json['legalAddress'] == null ? null : mapOf(json['legalAddress']);
  return DeliveryAgent(
    id: idOf(json['id']),
    legalName: '${json['legalName'] ?? ''}',
    status: '${json['status'] ?? ''}',
    phone: json['phone'] as String?,
    phoneVerified: json['phoneVerified'] == true,
    legalAddress: address == null
        ? null
        : [
            address['line1'],
            address['line2'],
            address['city'],
            address['region'],
            address['postalCode'],
            address['country'],
          ].where((p) => p != null && '$p'.isNotEmpty).join(', '),
    vehicle: vehicle == null
        ? null
        : AgentVehicle(
            kind: vehicle['kind'] as String?,
            make: vehicle['make'] as String?,
            model: vehicle['model'] as String?,
            plate: vehicle['plate'] as String?,
          ),
    idDocType: idDoc?['type'] as String?,
    idDocNumber: idDoc?['number'] as String?,
    documents: listOfMaps(json['documents'])
        .map((d) => AgentDocument(
              id: '${d['id'] ?? ''}',
              kind: '${d['kind'] ?? ''}',
              status: d['status'] as String?,
              uploadedAt: dateOf(d['uploadedAt']),
            ))
        .toList(),
    registeredAt: dateOf(json['registeredAt']),
    approvedAt: dateOf(json['approvedAt']),
    reviewedBy: json['reviewedBy'] as String?,
    rejectReason: json['rejectReason'] as String?,
  );
}

CourierCredential credentialFromJson(Map<String, dynamic> json) => CourierCredential(
      agentId: '${json['agentId'] ?? ''}',
      token: '${json['courierToken'] ?? ''}',
    );

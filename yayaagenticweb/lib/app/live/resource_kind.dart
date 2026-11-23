/// Resource kinds on the admin change feed. Wire names mirror
/// `AdminChangeEntityListener.MAPPINGS` on the backend.
enum AdminResourceKind {
  tenant('TENANT'),
  profile('PROFILE'),
  personality('PERSONALITY'),
  capability('CAPABILITY'),
  tool('TOOL'),
  knowledgeSource('KNOWLEDGE_SOURCE'),
  authBinding('AUTH_BINDING'),
  recordingStrategy('RECORDING_STRATEGY'),
  oidcIssuer('OIDC_ISSUER'),
  jwtSecret('JWT_SECRET'),
  serviceToken('SERVICE_TOKEN'),
  authResolver('AUTH_RESOLVER'),
  identityRoute('IDENTITY_ROUTE'),
  startFlow('START_FLOW'),
  networkSettings('NETWORK_SETTINGS'),
  whatsappSender('WHATSAPP_SENDER'),
  whatsappTemplate('WHATSAPP_TEMPLATE'),
  channelBinding('CHANNEL_BINDING'),
  chatEntryPoint('CHAT_ENTRY_POINT'),
  chat('CHAT'),
  sms('SMS'),
  systemSender('SYSTEM_SENDER'),
  receiptTemplate('RECEIPT_TEMPLATE'),
  forum('FORUM'),
  optOut('OPT_OUT'),
  apiKey('API_KEY'),
  operatorAuth('OPERATOR_AUTH');

  const AdminResourceKind(this.wire);
  final String wire;

  static AdminResourceKind? fromWire(String? wire) {
    for (final k in values) {
      if (k.wire == wire) return k;
    }
    return null;
  }
}

enum AdminChangeOp { created, updated, deleted }

class AdminChange {
  const AdminChange({
    required this.kind,
    required this.op,
    required this.receivedAt,
    this.id,
    this.version,
  });

  final AdminResourceKind kind;
  final AdminChangeOp op;
  final String? id;
  final int? version;
  final DateTime receivedAt;

  static AdminChange? fromJson(Map<String, dynamic> json, DateTime now) {
    final kind = AdminResourceKind.fromWire(json['kind'] as String?);
    if (kind == null) return null;
    final op = switch (json['op']) {
      'CREATED' => AdminChangeOp.created,
      'DELETED' => AdminChangeOp.deleted,
      _ => AdminChangeOp.updated,
    };
    final v = json['version'];
    return AdminChange(
      kind: kind,
      op: op,
      id: json['id'] as String?,
      version: v is int ? v : (v is num ? v.toInt() : null),
      receivedAt: now,
    );
  }
}

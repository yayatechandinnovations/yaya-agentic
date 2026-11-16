/// Wire models for `/v1/admin/tenants/{t}/whatsapp-senders/{sid}/templates`.
library;

class WhatsAppTemplateResponse {
  WhatsAppTemplateResponse({
    required this.id,
    required this.tenantId,
    required this.senderId,
    required this.name,
    required this.locale,
    required this.category,
    required this.body,
    required this.placeholderCount,
    required this.status,
    this.rejectionReason,
    this.submittedAt,
    this.approvedAt,
    this.shared = false,
  });

  final int id;
  final String tenantId;
  final String senderId;
  final String name;
  final String locale;
  final String category;
  final String body;
  final int placeholderCount;
  final String status;
  final String? rejectionReason;
  final String? submittedAt;
  final String? approvedAt;

  /// Other workspaces of the same WhatsApp account use this template too.
  final bool shared;

  bool get canSubmit => status == 'DRAFT' || status == 'REJECTED';
  bool get isEditable => status == 'DRAFT' || status == 'REJECTED';

  /// Removing it only takes this workspace off the template: other
  /// workspaces use it, or WhatsApp already has it.
  bool get staysInAccount => shared || !isEditable;

  factory WhatsAppTemplateResponse.fromJson(Map<String, dynamic> json) =>
      WhatsAppTemplateResponse(
        id: (json['id'] as num).toInt(),
        tenantId: (json['tenantId'] ?? '') as String,
        senderId: (json['senderId'] ?? '') as String,
        name: (json['name'] ?? '') as String,
        locale: (json['locale'] ?? 'en-US') as String,
        category: (json['category'] ?? 'UTILITY') as String,
        body: (json['body'] ?? '') as String,
        placeholderCount: (json['placeholderCount'] as num?)?.toInt() ?? 0,
        status: (json['status'] ?? 'DRAFT') as String,
        rejectionReason: json['rejectionReason'] as String?,
        submittedAt: json['submittedAt']?.toString(),
        approvedAt: json['approvedAt']?.toString(),
        shared: json['shared'] == true,
      );
}

/// A WhatsApp account that carries templates: the platform's own
/// (`accountKey == 'platform'`, no workspace) or one a workspace connected.
/// Wire model for `/v1/admin/whatsapp-accounts` (platform admins only).
class WhatsAppAccountResponse {
  WhatsAppAccountResponse({
    required this.accountKey,
    required this.label,
    required this.status,
    required this.templateCount,
    this.tenantId,
  });

  final String accountKey;
  final String? tenantId;
  final String label;
  final String status;
  final int templateCount;

  bool get isPlatform => tenantId == null;

  factory WhatsAppAccountResponse.fromJson(Map<String, dynamic> json) =>
      WhatsAppAccountResponse(
        accountKey: (json['accountKey'] ?? '') as String,
        tenantId: json['tenantId'] as String?,
        label: (json['label'] ?? '') as String,
        status: (json['status'] ?? '') as String,
        templateCount: (json['templateCount'] as num?)?.toInt() ?? 0,
      );
}

/// A workspace that may use a template, and who let it.
class WhatsAppTemplateHolder {
  WhatsAppTemplateHolder({required this.tenantId, required this.grantedBy, this.grantedAt});

  final String tenantId;
  final String grantedBy;
  final String? grantedAt;

  factory WhatsAppTemplateHolder.fromJson(Map<String, dynamic> json) =>
      WhatsAppTemplateHolder(
        tenantId: (json['tenantId'] ?? '') as String,
        grantedBy: (json['grantedBy'] ?? '') as String,
        grantedAt: json['grantedAt']?.toString(),
      );
}

/// A template of a WhatsApp account with every workspace that holds it.
/// Wire model for `/v1/admin/whatsapp-accounts/{accountKey}/templates`.
class WhatsAppAccountTemplate {
  WhatsAppAccountTemplate({
    required this.id,
    required this.accountKey,
    required this.name,
    required this.locale,
    required this.category,
    required this.body,
    required this.placeholderCount,
    required this.status,
    required this.headerKind,
    required this.buttons,
    required this.holders,
    this.rejectionReason,
    this.submittedAt,
    this.approvedAt,
  });

  final int id;
  final String accountKey;
  final String name;
  final String locale;
  final String category;
  final String body;
  final int placeholderCount;
  final String status;
  final String headerKind;
  final List<String> buttons;
  final List<WhatsAppTemplateHolder> holders;
  final String? rejectionReason;
  final String? submittedAt;
  final String? approvedAt;

  bool get canSubmit => status == 'DRAFT' || status == 'REJECTED';

  /// Nobody uses it and WhatsApp never approved it.
  bool get canDelete => holders.isEmpty && canSubmit;

  factory WhatsAppAccountTemplate.fromJson(Map<String, dynamic> json) =>
      WhatsAppAccountTemplate(
        id: (json['id'] as num).toInt(),
        accountKey: (json['accountKey'] ?? '') as String,
        name: (json['name'] ?? '') as String,
        locale: (json['locale'] ?? '') as String,
        category: (json['category'] ?? 'UTILITY') as String,
        body: (json['body'] ?? '') as String,
        placeholderCount: (json['placeholderCount'] as num?)?.toInt() ?? 0,
        status: (json['status'] ?? 'DRAFT') as String,
        headerKind: (json['headerKind'] ?? 'NONE') as String,
        buttons: ((json['buttons'] as List?) ?? const [])
            .map((e) => e.toString())
            .toList(),
        holders: ((json['holders'] as List?) ?? const [])
            .map((e) => WhatsAppTemplateHolder.fromJson(
                Map<String, dynamic>.from(e as Map)))
            .toList(),
        rejectionReason: json['rejectionReason'] as String?,
        submittedAt: json['submittedAt']?.toString(),
        approvedAt: json['approvedAt']?.toString(),
      );
}

/// A text-only template declared on an account from the console.
class WhatsAppTemplateDeclaration {
  WhatsAppTemplateDeclaration({
    required this.name,
    required this.language,
    required this.category,
    required this.body,
    required this.bodySamples,
  });

  final String name;
  final String language;
  final String category;
  final String body;

  /// One example value per placeholder, in order — WhatsApp reviews with them.
  final List<String> bodySamples;

  Map<String, dynamic> toJson() => {
        'name': name,
        'language': language,
        'category': category,
        'header': 'NONE',
        'body': body,
        'buttons': const <String>[],
        'samples': {'body': bodySamples},
      };
}

class WhatsAppTemplateRequest {
  WhatsAppTemplateRequest({
    required this.name,
    required this.locale,
    required this.category,
    required this.body,
  });

  final String name;
  final String locale;
  final String category;
  final String body;

  Map<String, dynamic> toJson() => {
        'name': name,
        'locale': locale,
        'category': category,
        'body': body,
      };
}

class WhatsAppTemplateUpdate {
  WhatsAppTemplateUpdate({required this.body, required this.category});
  final String body;
  final String category;

  Map<String, dynamic> toJson() => {'body': body, 'category': category};
}

/// Meta's three template categories.
const List<String> kTemplateCategories = ['UTILITY', 'MARKETING', 'AUTHENTICATION'];

class TemplateStatusInfo {
  const TemplateStatusInfo({required this.label, required this.description});
  final String label;
  final String description;

  static TemplateStatusInfo of(String status) {
    switch (status) {
      case 'DRAFT':
        return const TemplateStatusInfo(
            label: 'Draft',
            description:
                'Saved locally. Submit when you are ready for WhatsApp review.');
      case 'SUBMITTED':
        return const TemplateStatusInfo(
            label: 'Awaiting review',
            description:
                'WhatsApp is reviewing this template. Typically 1–3 business days.');
      case 'APPROVED':
        return const TemplateStatusInfo(
            label: 'Approved',
            description:
                'Live and usable for re-engagement messages outside the 24-hour service window.');
      case 'REJECTED':
        return const TemplateStatusInfo(
            label: 'Rejected',
            description:
                'WhatsApp rejected this template. Edit and resubmit; the rejection reason is shown below.');
      case 'PAUSED':
        return const TemplateStatusInfo(
            label: 'Paused',
            description:
                'WhatsApp paused this template, usually after recipients blocked or reported it. It cannot be sent until WhatsApp restores it.');
      default:
        return TemplateStatusInfo(label: status, description: '');
    }
  }
}

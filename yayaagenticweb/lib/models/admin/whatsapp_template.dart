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

  bool get canSubmit => status == 'DRAFT' || status == 'REJECTED';
  bool get isEditable => status == 'DRAFT' || status == 'REJECTED';

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
      );
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
                'Removed from active use but retained for audit. Cannot be reactivated through the admin UI.');
      default:
        return TemplateStatusInfo(label: status, description: '');
    }
  }
}

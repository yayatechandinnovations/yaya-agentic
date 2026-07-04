/// Wire models for `/v1/admin/tenants/{tenantId}/whatsapp-senders`.
/// Hand-rolled (no freezed) to match the existing pattern used by
/// `identity_profile_mapping.dart` and `tenant_jwt_secret.dart`. The webhook
/// secret is platform-only and never appears here.
library;

/// One sender row, as the admin API returns it.
class WhatsAppSenderResponse {
  WhatsAppSenderResponse({
    required this.id,
    required this.tenantId,
    required this.phoneE164,
    required this.displayName,
    this.approvedDisplayName,
    required this.businessProfile,
    required this.status,
    required this.onboarding,
    required this.defaultLocale,
    this.metadata = const <String, dynamic>{},
    this.createdAt,
    this.updatedAt,
    this.lastSyncAt,
    this.startFlowId,
  });

  final String id;
  final String tenantId;
  final String phoneE164;
  final String displayName;
  final String? approvedDisplayName;
  final BusinessProfileFields businessProfile;
  final String status;
  final OnboardingStateView onboarding;
  final String defaultLocale;
  final Map<String, dynamic> metadata;
  final String? createdAt;
  final String? updatedAt;
  final String? lastSyncAt;
  final String? startFlowId;

  bool get isEditable => status == 'DRAFT' || status == 'FAILED';

  factory WhatsAppSenderResponse.fromJson(Map<String, dynamic> json) {
    return WhatsAppSenderResponse(
      id: (json['id'] ?? '') as String,
      tenantId: (json['tenantId'] ?? '') as String,
      phoneE164: (json['phoneE164'] ?? '') as String,
      displayName: (json['displayName'] ?? '') as String,
      approvedDisplayName: json['approvedDisplayName'] as String?,
      businessProfile: BusinessProfileFields.fromJson(
          Map<String, dynamic>.from(json['businessProfile'] as Map)),
      status: (json['status'] ?? 'DRAFT') as String,
      onboarding: OnboardingStateView.fromJson(
          json['onboarding'] is Map
              ? Map<String, dynamic>.from(json['onboarding'] as Map)
              : const <String, dynamic>{}),
      defaultLocale: (json['defaultLocale'] ?? 'en-US') as String,
      metadata: json['metadata'] is Map
          ? Map<String, dynamic>.from(json['metadata'] as Map)
          : const <String, dynamic>{},
      createdAt: json['createdAt']?.toString(),
      updatedAt: json['updatedAt']?.toString(),
      lastSyncAt: json['lastSyncAt']?.toString(),
      startFlowId: json['startFlowId'] as String?,
    );
  }
}

/// Meta business profile. Operator collects this in step 1 of the wizard.
class BusinessProfileFields {
  BusinessProfileFields({
    required this.legalName,
    required this.displayName,
    required this.contactEmail,
    this.address = '',
    this.websiteUrl = '',
    required this.vertical,
    this.about = '',
  });

  final String legalName;
  final String displayName;
  final String contactEmail;
  final String address;
  final String websiteUrl;
  final String vertical;
  final String about;

  factory BusinessProfileFields.fromJson(Map<String, dynamic> json) =>
      BusinessProfileFields(
        legalName: (json['legalName'] ?? '') as String,
        displayName: (json['displayName'] ?? '') as String,
        contactEmail: (json['contactEmail'] ?? '') as String,
        address: (json['address'] ?? '') as String,
        websiteUrl: (json['websiteUrl'] ?? '') as String,
        vertical: (json['vertical'] ?? '') as String,
        about: (json['about'] ?? '') as String,
      );

  Map<String, dynamic> toJson() => {
        'legalName': legalName,
        'displayName': displayName,
        'contactEmail': contactEmail,
        'address': address,
        'websiteUrl': websiteUrl,
        'vertical': vertical,
        'about': about,
      };

  BusinessProfileFields copyWith({
    String? legalName,
    String? displayName,
    String? contactEmail,
    String? address,
    String? websiteUrl,
    String? vertical,
    String? about,
  }) =>
      BusinessProfileFields(
        legalName: legalName ?? this.legalName,
        displayName: displayName ?? this.displayName,
        contactEmail: contactEmail ?? this.contactEmail,
        address: address ?? this.address,
        websiteUrl: websiteUrl ?? this.websiteUrl,
        vertical: vertical ?? this.vertical,
        about: about ?? this.about,
      );

  static BusinessProfileFields empty() =>
      BusinessProfileFields(legalName: '', displayName: '', contactEmail: '', vertical: '');
}

/// Trimmed view of OnboardingState surfaced to the admin UI.
class OnboardingStateView {
  OnboardingStateView({
    this.twilioSenderSid,
    this.status,
    this.failureMessage,
    this.lastPolledAt,
    this.pollAttempts = 0,
  });

  /// Internal-only; we never surface a Twilio label in tenant-facing text.
  final String? twilioSenderSid;
  final String? status;
  final String? failureMessage;
  final String? lastPolledAt;
  final int pollAttempts;

  factory OnboardingStateView.fromJson(Map<String, dynamic> json) =>
      OnboardingStateView(
        twilioSenderSid: json['twilioSenderSid'] as String?,
        status: json['status'] as String?,
        failureMessage: json['failureMessage'] as String?,
        lastPolledAt: json['lastPolledAt']?.toString(),
        pollAttempts: (json['pollAttempts'] as num?)?.toInt() ?? 0,
      );
}

/// POST / PUT body shape.
class WhatsAppSenderRequest {
  WhatsAppSenderRequest({
    required this.phoneE164,
    required this.displayName,
    required this.businessProfile,
    this.defaultLocale,
    this.startFlowId,
  });

  final String phoneE164;
  final String displayName;
  final BusinessProfileFields businessProfile;
  final String? defaultLocale;
  final String? startFlowId;

  Map<String, dynamic> toJson() => {
        'phoneE164': phoneE164,
        'displayName': displayName,
        'businessProfile': businessProfile.toJson(),
        if (defaultLocale != null && defaultLocale!.isNotEmpty)
          'defaultLocale': defaultLocale,
        // Always sent: a value binds a Session Start Flow, empty clears it.
        'startFlowId': startFlowId ?? '',
      };
}

/// Meta-allowed business verticals. Source: Meta Business Manager dropdown.
/// Keeping this list local to the UI lets us ship before Meta changes their
/// catalog — backend treats the field as a string, so adding rows here never
/// breaks the wire.
const List<String> kMetaBusinessVerticals = [
  'AUTO',
  'BEAUTY_SPA_SALON',
  'CLOTHING_AND_APPAREL',
  'EDUCATION',
  'ENTERTAINMENT',
  'EVENT_PLANNING_AND_SERVICE',
  'FINANCE_AND_BANKING',
  'FOOD_AND_GROCERY',
  'GOVERNMENT_AND_POLITICS',
  'HEALTH_AND_WELLNESS',
  'HOME_IMPROVEMENT',
  'HOTEL_AND_LODGING',
  'NON_PROFIT',
  'PROFESSIONAL_SERVICES',
  'PUBLIC_SERVICE',
  'RESTAURANT',
  'RETAIL',
  'SHOPPING_AND_RETAIL',
  'TRAVEL_AND_TRANSPORTATION',
  'OTHER',
];

/// Operator-facing status copy. Maps the raw `SenderStatus` enum to the
/// label, description, and badge color used everywhere in the admin UI.
class SenderStatusInfo {
  const SenderStatusInfo({
    required this.label,
    required this.description,
  });

  final String label;
  final String description;

  static SenderStatusInfo of(String status) {
    switch (status) {
      case 'DRAFT':
        return const SenderStatusInfo(
            label: 'Draft',
            description:
                'Saved locally. Submit when you are ready to send for WhatsApp review.');
      case 'SUBMITTING':
        return const SenderStatusInfo(
            label: 'Submitting',
            description:
                'Registering the sender. This usually takes seconds; if it lingers, check back shortly.');
      case 'PENDING_REVIEW':
        return const SenderStatusInfo(
            label: 'Awaiting WhatsApp review',
            description:
                'Meta is reviewing the business profile and display name. Usually 1–3 business days.');
      case 'ACTIVE':
        return const SenderStatusInfo(
            label: 'Active',
            description:
                'Sender is approved and live. Bind it to a profile to start handling messages.');
      case 'SUSPENDED':
        return const SenderStatusInfo(
            label: 'Suspended',
            description:
                'Paused — inbound is acked but no replies are sent. Resume to go live again.');
      case 'FAILED':
        return const SenderStatusInfo(
            label: 'Review failed',
            description:
                'Submission was rejected. Edit the sender and resubmit — the rejection details are shown below.');
      case 'REVOKED':
        return const SenderStatusInfo(
            label: 'Revoked',
            description: 'Removed from service. Historical bindings retained read-only.');
      default:
        return SenderStatusInfo(label: status, description: '');
    }
  }
}

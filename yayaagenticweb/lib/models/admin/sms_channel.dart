/// Wire models for the SMS A2P 10DLC compliance + sender surface
/// (`/v1/admin/tenants/{tenantId}/sms/*`). Hand-rolled to match the existing
/// admin-model pattern (see `whatsapp_sender.dart`). Twilio / TCR identifiers
/// are never surfaced — the backend strips them.
library;

// ---------------------------------------------------------------------------
// Brand
// ---------------------------------------------------------------------------

class SmsBusinessIdentity {
  SmsBusinessIdentity({
    required this.legalName,
    required this.taxId,
    this.address,
    this.vertical,
    this.websiteUrl,
    this.contactEmail,
  });

  final String legalName;
  final String taxId; // EIN
  final String? address;
  final String? vertical;
  final String? websiteUrl;
  final String? contactEmail;

  factory SmsBusinessIdentity.fromJson(Map<String, dynamic> json) =>
      SmsBusinessIdentity(
        legalName: (json['legalName'] ?? '') as String,
        taxId: (json['taxId'] ?? '') as String,
        address: json['address'] as String?,
        vertical: json['vertical'] as String?,
        websiteUrl: json['websiteUrl'] as String?,
        contactEmail: json['contactEmail'] as String?,
      );

  Map<String, dynamic> toJson() => {
        'legalName': legalName,
        'taxId': taxId,
        if (address != null) 'address': address,
        if (vertical != null) 'vertical': vertical,
        if (websiteUrl != null) 'websiteUrl': websiteUrl,
        if (contactEmail != null) 'contactEmail': contactEmail,
      };
}

class SmsBrandResponse {
  SmsBrandResponse({
    required this.id,
    required this.tenantId,
    required this.business,
    required this.status,
    this.failureMessage,
    this.createdAt,
    this.updatedAt,
    this.lastSyncAt,
  });

  final String id;
  final String tenantId;
  final SmsBusinessIdentity business;
  final String status;
  final String? failureMessage;
  final String? createdAt;
  final String? updatedAt;
  final String? lastSyncAt;

  bool get isEditable => status == 'DRAFT' || status == 'FAILED';
  bool get isVerified => status == 'VERIFIED';

  factory SmsBrandResponse.fromJson(Map<String, dynamic> json) =>
      SmsBrandResponse(
        id: (json['id'] ?? '') as String,
        tenantId: (json['tenantId'] ?? '') as String,
        business: SmsBusinessIdentity.fromJson(
            Map<String, dynamic>.from(json['business'] as Map)),
        status: (json['status'] ?? 'DRAFT') as String,
        failureMessage: json['failureMessage'] as String?,
        createdAt: json['createdAt']?.toString(),
        updatedAt: json['updatedAt']?.toString(),
        lastSyncAt: json['lastSyncAt']?.toString(),
      );
}

class SmsBrandRequest {
  SmsBrandRequest(this.business);
  final SmsBusinessIdentity business;
  Map<String, dynamic> toJson() => {'business': business.toJson()};
}

// ---------------------------------------------------------------------------
// Campaign
// ---------------------------------------------------------------------------

class SmsOptIn {
  SmsOptIn({required this.description, this.proofUrl});
  final String description;
  final String? proofUrl;

  factory SmsOptIn.fromJson(Map<String, dynamic> json) => SmsOptIn(
        description: (json['description'] ?? '') as String,
        proofUrl: json['proofUrl'] as String?,
      );

  Map<String, dynamic> toJson() =>
      {'description': description, if (proofUrl != null) 'proofUrl': proofUrl};
}

class SmsCampaignResponse {
  SmsCampaignResponse({
    required this.id,
    required this.brandId,
    required this.useCase,
    required this.restricted,
    required this.description,
    required this.sampleMessages,
    required this.optIn,
    required this.requiresOperatorReview,
    required this.status,
    this.failureMessage,
    this.createdAt,
  });

  final String id;
  final String brandId;
  final String useCase;
  final bool restricted;
  final String description;
  final List<String> sampleMessages;
  final SmsOptIn optIn;
  final bool requiresOperatorReview;
  final String status;
  final String? failureMessage;
  final String? createdAt;

  bool get isApproved => status == 'APPROVED';

  factory SmsCampaignResponse.fromJson(Map<String, dynamic> json) =>
      SmsCampaignResponse(
        id: (json['id'] ?? '') as String,
        brandId: (json['brandId'] ?? '') as String,
        useCase: (json['useCase'] ?? '') as String,
        restricted: json['restricted'] == true,
        description: (json['description'] ?? '') as String,
        sampleMessages: (json['sampleMessages'] as List? ?? const [])
            .map((e) => e.toString())
            .toList(),
        optIn: SmsOptIn.fromJson(json['optIn'] is Map
            ? Map<String, dynamic>.from(json['optIn'] as Map)
            : const {'description': ''}),
        requiresOperatorReview: json['requiresOperatorReview'] == true,
        status: (json['status'] ?? 'DRAFT') as String,
        failureMessage: json['failureMessage'] as String?,
        createdAt: json['createdAt']?.toString(),
      );
}

class SmsCampaignRequest {
  SmsCampaignRequest({
    required this.brandId,
    required this.useCase,
    required this.description,
    required this.sampleMessages,
    required this.optIn,
    this.evidence,
  });

  final String brandId;
  final String useCase;
  final String description;
  final List<String> sampleMessages;
  final SmsOptIn optIn;
  final Map<String, dynamic>? evidence;

  Map<String, dynamic> toJson() => {
        'brandId': brandId,
        'useCase': useCase,
        'description': description,
        'sampleMessages': sampleMessages,
        'optIn': optIn.toJson(),
        if (evidence != null) 'evidence': evidence,
      };
}

// ---------------------------------------------------------------------------
// Sender
// ---------------------------------------------------------------------------

class SmsSenderOnboardingView {
  SmsSenderOnboardingView({this.status, this.failureMessage, this.pollAttempts = 0});
  final String? status;
  final String? failureMessage;
  final int pollAttempts;

  factory SmsSenderOnboardingView.fromJson(Map<String, dynamic> json) =>
      SmsSenderOnboardingView(
        status: json['status'] as String?,
        failureMessage: json['failureMessage'] as String?,
        pollAttempts: (json['pollAttempts'] ?? 0) as int,
      );
}

class SmsSenderResponse {
  SmsSenderResponse({
    required this.id,
    required this.phoneE164,
    required this.market,
    required this.senderKind,
    required this.acquisition,
    this.campaignId,
    required this.hasRegulatoryBundle,
    required this.status,
    required this.onboarding,
    required this.defaultLocale,
    this.createdAt,
    this.lastSyncAt,
  });

  final String id;
  final String phoneE164;
  final String market;
  final String senderKind;
  final String acquisition;
  final String? campaignId;
  final bool hasRegulatoryBundle;
  final String status;
  final SmsSenderOnboardingView onboarding;
  final String defaultLocale;
  final String? createdAt;
  final String? lastSyncAt;

  factory SmsSenderResponse.fromJson(Map<String, dynamic> json) =>
      SmsSenderResponse(
        id: (json['id'] ?? '') as String,
        phoneE164: (json['phoneE164'] ?? '') as String,
        market: (json['market'] ?? 'US') as String,
        senderKind: (json['senderKind'] ?? 'LONG_CODE') as String,
        acquisition: (json['acquisition'] ?? 'PURCHASED') as String,
        campaignId: json['campaignId'] as String?,
        hasRegulatoryBundle: json['hasRegulatoryBundle'] == true,
        status: (json['status'] ?? 'DRAFT') as String,
        onboarding: SmsSenderOnboardingView.fromJson(json['onboarding'] is Map
            ? Map<String, dynamic>.from(json['onboarding'] as Map)
            : const <String, dynamic>{}),
        defaultLocale: (json['defaultLocale'] ?? 'en-US') as String,
        createdAt: json['createdAt']?.toString(),
        lastSyncAt: json['lastSyncAt']?.toString(),
      );
}

class SmsSenderRequest {
  SmsSenderRequest({
    required this.phoneE164,
    required this.market,
    this.senderKind = 'LONG_CODE',
    this.acquisition = 'PURCHASED',
    this.campaignId,
    this.defaultLocale,
  });

  final String phoneE164;
  final String market;
  final String senderKind;
  final String acquisition;
  final String? campaignId;
  final String? defaultLocale;

  Map<String, dynamic> toJson() => {
        'phoneE164': phoneE164,
        'market': market,
        'senderKind': senderKind,
        'acquisition': acquisition,
        if (campaignId != null) 'campaignId': campaignId,
        if (defaultLocale != null) 'defaultLocale': defaultLocale,
      };
}

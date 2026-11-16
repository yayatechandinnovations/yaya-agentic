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

  /// WhatsApp sent a one-time code to the phone and is waiting for it.
  bool get needsVerificationCode =>
      status == 'PENDING_REVIEW' && onboarding.status == 'PENDING_VERIFICATION';

  /// The registration is with WhatsApp and a code may already be on the
  /// phone. The reported status can lag the SMS or skip the waiting state
  /// entirely, so code entry never waits for it.
  bool get canEnterVerificationCode =>
      status == 'PENDING_REVIEW' &&
      (onboarding.twilioSenderSid?.isNotEmpty ?? false) &&
      !const {'VERIFYING', 'TWILIO_REVIEW', 'ONLINE', 'ONLINE:UPDATING'}
          .contains(onboarding.status);

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

/// Business verticals WhatsApp accepts on a sender profile, keyed by the code
/// the backend stores (it maps each code to WhatsApp's label on submit).
/// Regulated categories (alcohol, gambling, OTC drugs) are left out on purpose.
const Map<String, String> kBusinessVerticalLabels = {
  'AUTO': 'Automotive',
  'BEAUTY_SPA_SALON': 'Beauty, spa and salon',
  'CLOTHING_AND_APPAREL': 'Clothing and apparel',
  'EDUCATION': 'Education',
  'ENTERTAINMENT': 'Entertainment',
  'EVENT_PLANNING_AND_SERVICE': 'Event planning and service',
  'FINANCE_AND_BANKING': 'Finance and banking',
  'FOOD_AND_GROCERY': 'Food and grocery',
  'HOTEL_AND_LODGING': 'Hotel and lodging',
  'MEDICAL_AND_HEALTH': 'Medical and health',
  'NON_PROFIT': 'Non-profit',
  'PROFESSIONAL_SERVICES': 'Professional services',
  'PUBLIC_SERVICE': 'Public service',
  'RESTAURANT': 'Restaurant',
  'SHOPPING_AND_RETAIL': 'Shopping and retail',
  'TRAVEL_AND_TRANSPORTATION': 'Travel and transportation',
  'OTHER': 'Other',
};

final List<String> kMetaBusinessVerticals = kBusinessVerticalLabels.keys.toList();

/// Drafts saved under the earlier vertical list carry codes the dropdown no
/// longer offers; mapping them keeps the dropdown's value in its items.
const Map<String, String> _legacyVerticals = {
  'GOVERNMENT_AND_POLITICS': 'PUBLIC_SERVICE',
  'HEALTH_AND_WELLNESS': 'MEDICAL_AND_HEALTH',
  'HOME_IMPROVEMENT': 'PROFESSIONAL_SERVICES',
  'RETAIL': 'SHOPPING_AND_RETAIL',
};

/// The dropdown-safe vertical for a stored code, or null when unknown.
String? normalizeBusinessVertical(String? code) {
  if (code == null || code.isEmpty) return null;
  final mapped = _legacyVerticals[code] ?? code;
  return kBusinessVerticalLabels.containsKey(mapped) ? mapped : null;
}

String businessVerticalLabel(String code) =>
    kBusinessVerticalLabels[normalizeBusinessVertical(code)] ?? code;

/// WhatsApp rejects the whole registration when the website has no scheme,
/// so a bare domain like `example.com` becomes `https://example.com`.
String normalizeWebsiteUrl(String raw) {
  final trimmed = raw.trim();
  if (trimmed.isEmpty) return trimmed;
  final lower = trimmed.toLowerCase();
  return lower.startsWith('https://') || lower.startsWith('http://')
      ? trimmed
      : 'https://$trimmed';
}

/// Null when [raw] is blank (the website is optional) or a usable web address.
String? websiteUrlError(String raw) {
  if (raw.trim().isEmpty) return null;
  final uri = Uri.tryParse(normalizeWebsiteUrl(raw));
  final ok = uri != null &&
      (uri.scheme == 'https' || uri.scheme == 'http') &&
      uri.host.contains('.') &&
      !uri.host.endsWith('.') &&
      !uri.host.contains(' ');
  return ok ? null : 'Website must be a web address, e.g. https://example.com.';
}

/// Plain-language progress for the raw registration status WhatsApp reports
/// while a sender is in flight. Never names the provider behind it.
String? registrationProgressLabel(String? rawStatus) {
  switch (rawStatus) {
    case 'CREATING':
    case 'PENDING':
      return 'Registering the number with WhatsApp';
    case 'PENDING_VERIFICATION':
      return 'Waiting for the verification code sent to the phone';
    case 'VERIFYING':
      return 'Checking the verification code';
    case 'TWILIO_REVIEW':
      return 'In final review';
    case 'ONLINE':
    case 'ONLINE:UPDATING':
      return 'Live on WhatsApp';
    case 'OFFLINE':
      return 'WhatsApp reported a problem — see below';
    case 'RETRY_PENDING':
      return 'Could not reach WhatsApp yet — retrying automatically';
    default:
      return null;
  }
}

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
                'WhatsApp is verifying the number and reviewing the display name. Minutes to a few business days.');
      case 'ACTIVE':
        return const SenderStatusInfo(
            label: 'Active',
            description:
                'Sender is approved and live. Messages reach the assistant its routes point to.');
      case 'SUSPENDED':
        return const SenderStatusInfo(
            label: 'Suspended',
            description:
                'Paused — inbound is acked but no replies are sent. Resume to go live again.');
      case 'FAILED':
        return const SenderStatusInfo(
            label: 'Review failed',
            description:
                'WhatsApp did not accept the registration. Use "Fix & resubmit" to correct the details and send it again.');
      case 'REVOKED':
        return const SenderStatusInfo(
            label: 'Revoked',
            description:
                'Removed from service. Historical bindings retained read-only; the number can be registered again.');
      default:
        return SenderStatusInfo(label: status, description: '');
    }
  }
}

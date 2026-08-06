/// Wire models for `/v1/admin/tenants/{tenantId}/whatsapp/embedded-signup`.
/// Hand-rolled (no freezed) to match the sibling WhatsApp models. The
/// backend never surfaces Twilio subaccount SIDs or credentials here.
library;

import 'whatsapp_sender.dart';

/// Browser-safe Meta configuration for the "Connect WhatsApp" wizard.
class EmbeddedSignupConfig {
  EmbeddedSignupConfig({
    required this.enabled,
    this.metaAppId,
    this.metaConfigurationId,
    this.partnerSolutionId,
  });

  final bool enabled;
  final String? metaAppId;
  final String? metaConfigurationId;
  final String? partnerSolutionId;

  factory EmbeddedSignupConfig.fromJson(Map<String, dynamic> json) =>
      EmbeddedSignupConfig(
        enabled: json['enabled'] == true,
        metaAppId: json['metaAppId'] as String?,
        metaConfigurationId: json['metaConfigurationId'] as String?,
        partnerSolutionId: json['partnerSolutionId'] as String?,
      );

  static EmbeddedSignupConfig disabled() => EmbeddedSignupConfig(enabled: false);
}

/// One onboarding attempt row. `failureMessage` is sanitized server-side.
class OnboardingAttemptView {
  OnboardingAttemptView({
    required this.id,
    required this.phoneE164,
    required this.wabaId,
    this.senderId,
    this.messagingAccountId,
    required this.state,
    this.failureCode,
    this.failureMessage,
    this.createdAt,
    this.updatedAt,
    this.completedAt,
  });

  final String id;
  final String phoneE164;
  final String wabaId;
  final String? senderId;
  final String? messagingAccountId;
  final String state;
  final String? failureCode;
  final String? failureMessage;
  final String? createdAt;
  final String? updatedAt;
  final String? completedAt;

  bool get isCompleted => state == 'COMPLETED';
  bool get isFailed => state == 'FAILED';

  factory OnboardingAttemptView.fromJson(Map<String, dynamic> json) =>
      OnboardingAttemptView(
        id: (json['id'] ?? '') as String,
        phoneE164: (json['phoneE164'] ?? '') as String,
        wabaId: (json['wabaId'] ?? '') as String,
        senderId: json['senderId'] as String?,
        messagingAccountId: json['messagingAccountId'] as String?,
        state: (json['state'] ?? 'RECEIVED') as String,
        failureCode: json['failureCode'] as String?,
        failureMessage: json['failureMessage'] as String?,
        createdAt: json['createdAt']?.toString(),
        updatedAt: json['updatedAt']?.toString(),
        completedAt: json['completedAt']?.toString(),
      );
}

/// Response of POST `/whatsapp/embedded-signup`.
class OnboardingResponse {
  OnboardingResponse({required this.attempt, this.sender});

  final OnboardingAttemptView attempt;
  final WhatsAppSenderResponse? sender;

  factory OnboardingResponse.fromJson(Map<String, dynamic> json) =>
      OnboardingResponse(
        attempt: OnboardingAttemptView.fromJson(
            Map<String, dynamic>.from(json['attempt'] as Map)),
        sender: json['sender'] is Map
            ? WhatsAppSenderResponse.fromJson(
                Map<String, dynamic>.from(json['sender'] as Map))
            : null,
      );
}

/// POST body: step-1 form data plus the ids Meta's popup returned.
class OnboardingRequest {
  OnboardingRequest({
    required this.phoneE164,
    required this.wabaId,
    this.metaPhoneNumberId,
    required this.displayName,
    required this.businessProfile,
    this.defaultLocale,
  });

  final String phoneE164;
  final String wabaId;
  final String? metaPhoneNumberId;
  final String displayName;
  final BusinessProfileFields businessProfile;
  final String? defaultLocale;

  Map<String, dynamic> toJson() => {
        'phoneE164': phoneE164,
        'wabaId': wabaId,
        if (metaPhoneNumberId != null && metaPhoneNumberId!.isNotEmpty)
          'metaPhoneNumberId': metaPhoneNumberId,
        'displayName': displayName,
        'businessProfile': businessProfile.toJson(),
        if (defaultLocale != null && defaultLocale!.isNotEmpty)
          'defaultLocale': defaultLocale,
      };
}

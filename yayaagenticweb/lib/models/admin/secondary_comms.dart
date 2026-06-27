/// Wire models for `/v1/admin/tenants/{tenantId}/secondary-comms`. Mirrors the
/// backend's `TenantSecondaryCommsController` DTOs. Plain Dart (not freezed)
/// because the shape is small and stable.
library;

class EmailRelay {
  EmailRelay({required this.fromAddress, this.providerId});
  final String fromAddress;
  final String? providerId;

  factory EmailRelay.fromJson(Map<String, dynamic> json) => EmailRelay(
        fromAddress: (json['fromAddress'] ?? '') as String,
        providerId: json['providerId'] as String?,
      );

  Map<String, dynamic> toJson() => {
        'fromAddress': fromAddress,
        if (providerId != null && providerId!.isNotEmpty)
          'providerId': providerId,
      };
}

class SmsBackup {
  SmsBackup({required this.fromNumber, this.providerId});
  final String fromNumber;
  final String? providerId;

  factory SmsBackup.fromJson(Map<String, dynamic> json) => SmsBackup(
        fromNumber: (json['fromNumber'] ?? '') as String,
        providerId: json['providerId'] as String?,
      );

  Map<String, dynamic> toJson() => {
        'fromNumber': fromNumber,
        if (providerId != null && providerId!.isNotEmpty)
          'providerId': providerId,
      };
}

class SecondaryComms {
  SecondaryComms({this.emailRelay, this.smsBackup});
  final EmailRelay? emailRelay;
  final SmsBackup? smsBackup;

  factory SecondaryComms.fromJson(Map<String, dynamic> json) => SecondaryComms(
        emailRelay: json['emailRelay'] == null
            ? null
            : EmailRelay.fromJson(
                Map<String, dynamic>.from(json['emailRelay'] as Map)),
        smsBackup: json['smsBackup'] == null
            ? null
            : SmsBackup.fromJson(
                Map<String, dynamic>.from(json['smsBackup'] as Map)),
      );

  Map<String, dynamic> toJson() => {
        if (emailRelay != null) 'emailRelay': emailRelay!.toJson(),
        if (smsBackup != null) 'smsBackup': smsBackup!.toJson(),
      };

  bool get hasAny => emailRelay != null || smsBackup != null;
}

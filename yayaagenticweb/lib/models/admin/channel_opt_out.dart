library;

class ChannelOptOutResponse {
  ChannelOptOutResponse({
    required this.senderId,
    required this.endUserPhone,
    this.optedOutAt,
    this.reason,
  });

  final String senderId;
  final String endUserPhone;
  final String? optedOutAt;
  final String? reason;

  factory ChannelOptOutResponse.fromJson(Map<String, dynamic> json) =>
      ChannelOptOutResponse(
        senderId: (json['senderId'] ?? '') as String,
        endUserPhone: (json['endUserPhone'] ?? '') as String,
        optedOutAt: json['optedOutAt']?.toString(),
        reason: json['reason'] as String?,
      );
}

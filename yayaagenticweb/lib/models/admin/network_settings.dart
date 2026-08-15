/// Wire model for `/v1/admin/tenants/{tenantId}/network-settings`. Mirrors the
/// backend `TenantNetworkSettingsController` DTOs. Plain Dart (not freezed) —
/// small, stable shape. Only the first five fields are writable (sent on PUT);
/// the rest are read-only status echoed by the backend.
library;

class NetworkSettings {
  NetworkSettings({
    required this.enabled,
    required this.playEnabled,
    required this.claimEnabled,
    this.hubBaseUrl,
    this.hubCredentialRef,
    this.entryPointHandle,
    this.entryPointActive = false,
    this.chatUrl,
  });

  final bool enabled;
  final bool playEnabled;
  final bool claimEnabled;
  final String? hubBaseUrl;
  final String? hubCredentialRef;

  // Read-only status from the backend.
  final String? entryPointHandle;
  final bool entryPointActive;
  final String? chatUrl;

  factory NetworkSettings.fromJson(Map<String, dynamic> json) => NetworkSettings(
        enabled: (json['enabled'] ?? false) as bool,
        playEnabled: (json['playEnabled'] ?? false) as bool,
        claimEnabled: (json['claimEnabled'] ?? false) as bool,
        hubBaseUrl: json['hubBaseUrl'] as String?,
        hubCredentialRef: json['hubCredentialRef'] as String?,
        entryPointHandle: json['entryPointHandle'] as String?,
        entryPointActive: (json['entryPointActive'] ?? false) as bool,
        chatUrl: json['chatUrl'] as String?,
      );

  /// Only the writable fields ride a PUT.
  Map<String, dynamic> toJson() => {
        'enabled': enabled,
        'playEnabled': playEnabled,
        'claimEnabled': claimEnabled,
        if (hubBaseUrl != null && hubBaseUrl!.isNotEmpty) 'hubBaseUrl': hubBaseUrl,
        if (hubCredentialRef != null && hubCredentialRef!.isNotEmpty)
          'hubCredentialRef': hubCredentialRef,
      };

  NetworkSettings copyWith({
    bool? enabled,
    bool? playEnabled,
    bool? claimEnabled,
    String? hubBaseUrl,
    String? hubCredentialRef,
  }) =>
      NetworkSettings(
        enabled: enabled ?? this.enabled,
        playEnabled: playEnabled ?? this.playEnabled,
        claimEnabled: claimEnabled ?? this.claimEnabled,
        hubBaseUrl: hubBaseUrl ?? this.hubBaseUrl,
        hubCredentialRef: hubCredentialRef ?? this.hubCredentialRef,
        entryPointHandle: entryPointHandle,
        entryPointActive: entryPointActive,
        chatUrl: chatUrl,
      );
}

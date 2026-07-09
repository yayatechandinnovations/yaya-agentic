import '../../domain/entities/client.dart';

class ClientModel {
  static Client fromJson(Map<String, dynamic> json) => Client(
        clientId: json['clientId'] as String? ?? '',
        type: json['type'] as String? ?? '',
        audiences: (json['audiences'] as List<dynamic>? ?? const []).cast<String>(),
      );
}

import '../../domain/entities/organization.dart';

/// Data-layer mapping for an organization JSON object from the admin API.
class OrganizationModel {
  static Organization fromJson(Map<String, dynamic> json) => Organization(
        id: json['id'] as String,
        name: json['name'] as String? ?? '',
        slug: json['slug'] as String? ?? '',
        mfaRequired: json['mfaRequired'] as bool? ?? false,
      );
}

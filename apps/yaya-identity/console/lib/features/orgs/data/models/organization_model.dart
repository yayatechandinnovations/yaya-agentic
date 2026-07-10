import '../../domain/entities/organization.dart';

/// Data-layer mapping for an organization JSON object from the admin API.
class OrganizationModel {
  static Organization fromJson(Map<String, dynamic> json) => Organization(
        id: json['id'] as String,
        name: json['name'] as String? ?? '',
        slug: json['slug'] as String? ?? '',
        status: json['status'] as String? ?? 'ACTIVE',
        mfaRequired: json['mfaRequired'] as bool? ?? false,
        description: json['description'] as String?,
        contactEmail: json['contactEmail'] as String?,
        contactPhone: json['contactPhone'] as String?,
        website: json['website'] as String?,
        country: json['country'] as String?,
        createdAt: json['createdAt'] as String?,
        memberCount: (json['memberCount'] as num?)?.toInt() ?? 0,
      );

  static Map<String, dynamic> draftToJson(OrgDraft draft) => {
        'name': draft.name,
        'description': draft.description,
        'contactEmail': draft.contactEmail,
        'contactPhone': draft.contactPhone,
        'website': draft.website,
        'country': draft.country,
      };
}

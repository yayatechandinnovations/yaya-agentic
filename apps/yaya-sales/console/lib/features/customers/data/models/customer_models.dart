import '../../../../core/network/json.dart';
import '../../domain/entities/customer_entities.dart';

CustomerAddress addressFromJson(Map<String, dynamic> json) {
  final data = mapOf(json['address']);
  String? s(String key) => data[key] == null ? null : '${data[key]}';
  return CustomerAddress(
    id: idOf(json['id']),
    kind: '${json['kind'] ?? 'SHIPPING'}',
    line1: s('line1'),
    line2: s('line2'),
    city: s('city'),
    region: s('region'),
    postalCode: s('postalCode'),
    country: s('country'),
  );
}

Customer customerFromJson(Map<String, dynamic> json) => Customer(
      id: idOf(json['id']),
      displayName: '${json['displayName'] ?? ''}',
      customerRef: json['customerRef'] as String?,
      email: json['email'] as String?,
      phone: json['phone'] as String?,
      notes: json['notes'] as String?,
      addresses: listOfMaps(json['addresses']).map(addressFromJson).toList(),
      createdAt: json['createdAt'] == null ? null : DateTime.tryParse('${json['createdAt']}'),
    );

Map<String, dynamic> customerDraftToJson(CustomerDraft draft) => {
      'displayName': draft.displayName,
      if (draft.customerRef != null) 'customerRef': draft.customerRef,
      if (draft.email != null) 'email': draft.email,
      if (draft.phone != null) 'phone': draft.phone,
      if (draft.notes != null) 'notes': draft.notes,
    };

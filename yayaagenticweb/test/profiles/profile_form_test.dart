import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/features/admin/profiles/profile_form.dart';
import 'package:yayaagenticweb/models/admin/profile.dart';

void main() {
  ProfileResponse profile({int version = 2, Map<String, dynamic>? extensions}) => ProfileResponse.fromJson({
        'id': 'retail',
        'version': version,
        'tenant': 't1',
        'displayName': 'Retail',
        'introOneLiner': 'Hi.',
        'systemPromptFragment': 'Help shoppers.',
        'capabilities': ['catalog'],
        'language': 'es',
        'metadata': {'principalKinds': ['customer']},
        'extensions': extensions,
        'maxToolRounds': null,
        'metered': false,
      });

  test('snapshots round-trip through a draft', () {
    final a = ProfileDraft.fromProfile(profile(extensions: {
      'sales.store': {'storeId': 's1', 'checkoutTool': 'sales_checkout', 'cartEnabled': false},
    }));
    final b = ProfileDraft()..applySnapshot(a.toSnapshot());
    expect(b.toSnapshot(), a.toSnapshot());
  });

  test('reads the legacy {value} wrapper on the checkout tool', () {
    final d = ProfileDraft.fromProfile(profile(extensions: {
      'sales.store': {
        'storeId': 's1',
        'checkoutTool': {'value': 'legacy_checkout'},
      },
    }));
    expect(d.storeEnabled, isTrue);
    expect(d.checkoutTool, 'legacy_checkout');
    expect(d.cartEnabled, isTrue);
  });

  test('the store controls edit only the sales.store kind', () {
    final source = profile(extensions: {
      'sales.store': {'storeId': 's1', 'currency': 'DOP'},
      'ops.whatsapp': {'sender': 'main'},
    });
    final d = ProfileDraft.fromProfile(source)..storeId = 's2';
    final ext = d.toRequest(tenant: 't1', source: source).extensions!;
    expect(ext['ops.whatsapp'], {'sender': 'main'});
    expect(ext['sales.store'], {'storeId': 's2', 'currency': 'DOP', 'checkoutTool': 'checkout', 'cartEnabled': true});

    d.storeEnabled = false;
    expect(d.toRequest(tenant: 't1', source: source).extensions!.keys, ['ops.whatsapp']);
  });

  test('a brand-new profile carries nothing and leaves tool rounds to the server', () {
    final req = (ProfileDraft()
          ..id = 'new'
          ..displayName = 'New')
        .toRequest(tenant: 't1');
    expect(req.metadata, isEmpty);
    expect(req.extensions, isNull);
    expect(req.carried, isEmpty);
    expect(req.maxToolRounds, isNull);
    expect(req.model, '');
  });

  test('summaries keep the latest version per id', () {
    final s = summarizeProfiles([profile(version: 1), profile(version: 3), profile(version: 2)]);
    expect(s.single.latest.version, 3);
    expect(s.single.versions.map((v) => v.version), [3, 2, 1]);
  });
}

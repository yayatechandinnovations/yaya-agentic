import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_templates/template_list_kit.dart';
import 'package:yayaagenticweb/models/admin/platform_sender.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_sender.dart';
import 'package:yayaagenticweb/models/admin/whatsapp_template.dart';

/// The §17 wire shapes of whatsapp-shared-sender-design.md, as pinned for
/// the parallel backend build.
Map<String, dynamic> _sender({String? tenantId = 't1', String? owner, Map<String, dynamic>? assignment}) => {
      'id': 'snd_1',
      'tenantId': tenantId,
      'owner': ?owner,
      'phoneE164': '+15024874573',
      'displayName': 'Avisos',
      'approvedDisplayName': 'Avisos Smart Lottery',
      'businessProfile': {'legalName': 'SL', 'displayName': 'SL', 'contactEmail': 'a@b.co', 'vertical': 'OTHER'},
      'status': 'ACTIVE',
      'onboarding': {'status': 'ONLINE', 'twilioSenderSid': 'XE1'},
      'defaultLocale': 'es-DO',
      'assignment': assignment,
    };

void main() {
  group('SenderResponse (§17.1)', () {
    test('a platform sender carries owner and the caller’s assignment', () {
      final s = WhatsAppSenderResponse.fromJson(_sender(tenantId: null, owner: 'PLATFORM', assignment: {
        'id': 'wsa_1',
        'label': 'Banca La Suerte',
        'status': 'ACTIVE',
        'dailyOutboundCap': 1000,
        'nonMemberReply': 'Gracias…',
        'templateSends24h': 12,
        'members': 7,
      }));
      expect(s.isPlatform, isTrue);
      expect(s.tenantId, '');
      final a = s.assignment!;
      expect(a.id, 'wsa_1');
      expect(a.label, 'Banca La Suerte');
      expect(a.status, 'ACTIVE');
      expect(a.dailyOutboundCap, 1000);
      expect(a.nonMemberReply, 'Gracias…');
      expect(a.templateSends24h, 12);
      expect(a.members, 7);
    });

    test('a workspace sender (owner absent or WORKSPACE) has no assignment', () {
      final legacy = WhatsAppSenderResponse.fromJson(_sender());
      expect(legacy.owner, 'WORKSPACE');
      expect(legacy.isPlatform, isFalse);
      expect(legacy.assignment, isNull);
      final explicit = WhatsAppSenderResponse.fromJson(_sender(owner: 'WORKSPACE'));
      expect(explicit.isPlatform, isFalse);
    });
  });

  group('platform-admin surface (§17.3)', () {
    test('PlatformSenderResponse = SenderResponse + unroutedReply + assignmentCount', () {
      final p = PlatformSenderResponse.fromJson({
        ..._sender(tenantId: null, owner: 'PLATFORM'),
        'unroutedReply': 'Este número solo envía avisos.',
        'assignmentCount': 3,
      });
      expect(p.sender.isPlatform, isTrue);
      expect(p.unroutedReply, 'Este número solo envía avisos.');
      expect(p.assignmentCount, 3);
      expect(p.title, 'Avisos Smart Lottery');
    });

    test('a list row without owner still reads as a platform sender', () {
      final p = PlatformSenderResponse.fromJson(_sender(tenantId: null));
      expect(p.sender.isPlatform, isTrue);
      expect(p.assignmentCount, 0);
    });

    test('create/update bodies: PUT drops the phone, unroutedReply is always sent', () {
      final req = PlatformSenderRequest(
        phoneE164: '+15024874573',
        displayName: 'Avisos',
        businessProfile: BusinessProfileFields.empty(),
        defaultLocale: 'es-DO',
      );
      expect(req.toJson()['phoneE164'], '+15024874573');
      expect(req.toJson()['unroutedReply'], '');
      final put = req.toJson(includePhone: false);
      expect(put.containsKey('phoneE164'), isFalse);
      expect(put.keys, containsAll(['displayName', 'businessProfile', 'defaultLocale', 'unroutedReply']));
    });

    test('assignment response with stats', () {
      final a = PlatformSenderAssignment.fromJson({
        'id': 'wsa_1',
        'senderId': 'snd_1',
        'tenantId': 'banca',
        'label': 'Banca La Suerte',
        'status': 'PAUSED',
        'dailyOutboundCap': 50,
        'nonMemberReply': null,
        'assignedBy': 'op-1',
        'assignedAt': '2026-11-23T10:00:00Z',
        'endedAt': null,
        'stats': {'templateSends24h': 50, 'optOuts7d': 2, 'members': 4},
      });
      expect(a.tenantId, 'banca');
      expect(a.status, 'PAUSED');
      expect(a.isLive, isTrue);
      expect(a.stats.templateSends24h, 50);
      expect(a.stats.optOuts7d, 2);
      expect(a.stats.members, 4);
      expect(a.atCap, isTrue);
      expect(a.assignedBy, 'op-1');
    });

    test('assign body omits the cap when not given', () {
      expect(const PlatformSenderAssignmentRequest(label: 'X').toJson(), {'label': 'X'});
      expect(const PlatformSenderAssignmentRequest(label: 'X', dailyOutboundCap: 10).toJson(),
          {'label': 'X', 'dailyOutboundCap': 10});
    });

    test('end dry-run effects parse and describe every effect', () {
      final r = AssignmentEndResult.fromJson({
        'dryRun': true,
        'effects': {
          'systemSendersUnlinked': ['ss_pos', {'id': 'ss_hub', 'name': 'Hub'}],
          'bindingsDeactivated': 1,
          'keywordActionsDeleted': 2,
          'membersDeleted': 7,
          'sessionsClosed': 3,
          'parkedOutboundDropped': 0,
          'routesCleared': 5,
        },
      });
      expect(r.dryRun, isTrue);
      expect(r.effects.systemSendersUnlinked, ['ss_pos', 'Hub']);
      expect(r.effects.membersDeleted, 7);
      final lines = r.effects.describe();
      expect(lines, contains('Unlinks 2 system senders: ss_pos, Hub'));
      expect(lines, contains('Switches off 1 route on this number'));
      expect(lines, contains('Removes 7 registered members'));
      expect(lines.any((l) => l.contains('queued')), isFalse, reason: 'zero counts are left out');
      expect(lines.last, contains('Opt-outs stay'));
    });

    test('unrouted rows', () {
      final m = UnroutedInbound.fromJson(
          {'messageSid': 'SM1', 'phoneMasked': '+1809•••1234', 'receivedAt': '2026-11-23T10:00:00Z', 'reason': 'not_routed', 'body': 'hola'});
      expect(m.messageSid, 'SM1');
      expect(m.phoneMasked, '+1809•••1234');
      expect(m.reason, 'not_routed');
      expect(m.body, 'hola');
    });

    test('workspace → platform transfer body is exactly the pinned one', () {
      expect(const WhatsAppSenderTransferRequest.toPlatform().toJson(), {'destinationPlatform': true, 'copyOptOuts': true});
      expect(
        const WhatsAppSenderTransferRequest(destinationTenant: 'acme', copyOptOuts: false).toJson(),
        {'destinationTenant': 'acme', 'copyOptOuts': false},
      );
    });
  });

  group('members and keywords (§17.1)', () {
    test('members', () {
      final m = SenderMembers.fromJson({'phones': ['+18095550001', '+18095550002'], 'count': 2});
      expect(m.phones, hasLength(2));
      expect(m.count, 2);
      expect(SenderMembers.fromJson({'phones': ['+1']}).count, 1);
    });

    test('keyword scope defaults to ROUTED when absent', () {
      final items = KeywordActionItem.listFromJson({
        'actions': [
          {'prefix': 'TICKET', 'forwardUrl': 'https://x/t'},
          {'prefix': 'NO ES MIO', 'forwardUrl': 'https://x/n', 'scope': 'REPLY'},
        ],
      });
      expect(items.first.scope, KeywordScope.routed);
      expect(items.last.scope, KeywordScope.reply);
      expect(items.last.toJson(), {'prefix': 'NO ES MIO', 'forwardUrl': 'https://x/n', 'scope': 'REPLY'});
    });
  });

  group('templates: businessLabelVar (§17.1)', () {
    test('parsed on both template views and sent on declarations when set', () {
      final t = WhatsAppTemplateResponse.fromJson({'id': 1, 'name': 'n', 'businessLabelVar': 1});
      expect(t.businessLabelVar, 1);
      expect(WhatsAppTemplateResponse.fromJson({'id': 1, 'name': 'n'}).businessLabelVar, isNull);
      final a = WhatsAppAccountTemplate.fromJson({'id': 2, 'name': 'n', 'businessLabelVar': 2});
      expect(a.businessLabelVar, 2);

      final d = WhatsAppTemplateDeclaration(
          name: 'n', language: 'es', category: 'UTILITY', body: '{{1}}: hola', bodySamples: ['Banca'], businessLabelVar: 1);
      expect(d.toJson()['businessLabelVar'], 1);
      final none = WhatsAppTemplateDeclaration(name: 'n', language: 'es', category: 'UTILITY', body: 'x', bodySamples: []);
      expect(none.toJson().containsKey('businessLabelVar'), isFalse);
      expect(WhatsAppTemplateUpdate(body: 'b', category: 'UTILITY', businessLabelVar: 1).toJson()['businessLabelVar'], 1);
    });

    test('the variable must name one of the body’s placeholders', () {
      expect(businessLabelVarError('', 'Hola {{1}}'), isNull);
      expect(businessLabelVarError('1', 'Hola {{1}} y {{2}}'), isNull);
      expect(businessLabelVarError('3', 'Hola {{1}} y {{2}}'), contains('{{1}} to {{2}}'));
      expect(businessLabelVarError('1', 'Hola'), contains('no variables'));
      expect(businessLabelVarError('0', 'Hola {{1}}'), isNotNull);
      expect(parseBusinessLabelVar(' 2 '), 2);
      expect(parseBusinessLabelVar(''), isNull);
    });
  });
}

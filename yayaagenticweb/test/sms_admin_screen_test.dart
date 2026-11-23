import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/api/admin_api.dart';
import 'package:yayaagenticweb/app/selected_tenant.dart';
import 'package:yayaagenticweb/features/admin/sms/sms_admin_screen.dart';
import 'package:yayaagenticweb/models/admin/channel_binding.dart';
import 'package:yayaagenticweb/models/admin/sms_channel.dart';
import 'package:yayaagenticweb/ui/form/y_fields.dart';

class _FakeAdminApi extends AdminApi {
  _FakeAdminApi() : super(Dio());

  SmsBrandResponse? brand;
  SmsBrandRequest? createdBrand;
  SmsCampaignRequest? createdCampaign;
  SmsSenderRequest? createdSender;
  final campaigns = <SmsCampaignResponse>[];

  @override
  Future<SmsBrandResponse?> getSmsBrand(String tenantId) async => brand;

  @override
  Future<SmsBrandResponse> createSmsBrand(String tenantId, SmsBrandRequest body) async {
    createdBrand = body;
    brand = SmsBrandResponse.fromJson({
      'id': 'br_1',
      'tenantId': tenantId,
      'business': body.business.toJson(),
      'status': 'DRAFT',
    });
    return brand!;
  }

  @override
  Future<List<SmsCampaignResponse>> listSmsCampaigns(String tenantId) async => campaigns;

  @override
  Future<SmsCampaignResponse> createSmsCampaign(String tenantId, SmsCampaignRequest body) async {
    createdCampaign = body;
    return SmsCampaignResponse.fromJson({'id': 'cmp_1', ...body.toJson(), 'status': 'DRAFT'});
  }

  @override
  Future<List<SmsSenderResponse>> listSmsSenders(String tenantId) async => const [];

  @override
  Future<SmsSenderResponse> createSmsSender(String tenantId, SmsSenderRequest body) async {
    createdSender = body;
    return SmsSenderResponse.fromJson({'id': 'sms_1', ...body.toJson(), 'status': 'DRAFT'});
  }

  @override
  Future<List<ChannelBindingResponse>> listChannelBindings(String tenantId) async => const [];
}

Widget _screen(_FakeAdminApi api) => ProviderScope(
      overrides: [
        adminApiProvider.overrideWith((ref) async => api),
        currentTenantOrNull.overrideWithValue('t1'),
      ],
      child: const MaterialApp(home: Scaffold(body: SmsAdminScreen())),
    );

Finder _input(String label) =>
    find.descendant(of: find.widgetWithText(YTextField, label), matching: find.byType(TextField));

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    final binding = TestWidgetsFlutterBinding.ensureInitialized();
    binding.platformDispatcher.views.first.physicalSize = const Size(1600, 2400);
    binding.platformDispatcher.views.first.devicePixelRatio = 1.0;
  });

  testWidgets('the brand needs a legal name and EIN before it is created', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(FilledButton, 'Create brand'));
    await tester.pumpAndSettle();
    expect(find.text('Enter the legal business name.'), findsOneWidget);
    expect(find.text('Enter the tax ID (EIN).'), findsOneWidget);
    expect(api.createdBrand, isNull);

    await tester.enterText(_input('Legal business name *'), 'Acme Foods LLC');
    await tester.enterText(_input('Tax ID (EIN) *'), '12-3456789');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Create brand'));
    await tester.pumpAndSettle();

    expect(api.createdBrand?.business.legalName, 'Acme Foods LLC');
    expect(api.createdBrand?.business.taxId, '12-3456789');
    expect(find.textContaining('Created the brand'), findsOneWidget);
  });

  testWidgets('a campaign needs two sample messages and an opt-in', (tester) async {
    final api = _FakeAdminApi()
      ..brand = SmsBrandResponse.fromJson({
        'id': 'br_1',
        'tenantId': 't1',
        'business': {'legalName': 'Acme', 'taxId': '12-3456789'},
        'status': 'VERIFIED',
      });
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(FilledButton, 'New campaign'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Create campaign'));
    await tester.pumpAndSettle();

    expect(find.text('Carriers need at least two sample messages.'), findsOneWidget);
    expect(find.text('Enter how people agree to get these messages.'), findsOneWidget);
    expect(api.createdCampaign, isNull);
  });

  testWidgets('a US number cannot be added without a campaign', (tester) async {
    final api = _FakeAdminApi();
    await tester.pumpWidget(_screen(api));
    await tester.pumpAndSettle();

    await tester.tap(find.widgetWithText(FilledButton, 'New sender'));
    await tester.pumpAndSettle();
    await tester.enterText(_input('Phone number *'), '5551234');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Add sender'));
    await tester.pumpAndSettle();

    expect(find.text('Use international format: + then the country code and number, e.g. +15551234567.'), findsOneWidget);
    expect(find.text('US numbers must attach to a campaign. Create one first.'), findsOneWidget);
    expect(api.createdSender, isNull);

    // A Dominican number needs no campaign.
    await tester.tap(find.text('Dominican Republic'));
    await tester.enterText(_input('Phone number *'), '+18095551234');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Add sender'));
    await tester.pumpAndSettle();
    expect(api.createdSender?.market, 'DO');
    expect(api.createdSender?.campaignId, isNull);
  });
}

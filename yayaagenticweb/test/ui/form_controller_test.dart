import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:yayaagenticweb/ui/form/y_form_controller.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => SharedPreferences.setMockInitialValues({}));

  late Map<String, Object?> values;
  YFormController make({String? draftKey}) => YFormController(
        snapshot: () => Map.of(values),
        validate: () => {
          'id': Validators.toolId(values['id'] as String?),
          'url': (values['url'] as String? ?? '').isEmpty ? 'Enter the path.' : null,
        },
        labels: const {'id': 'Tool ID', 'url': 'Request path'},
        draftKey: draftKey,
      );

  setUp(() => values = {'id': '', 'url': ''});

  test('errors stay hidden until a field is touched or a submit is attempted', () {
    final f = make();
    expect(f.errorFor('id'), isNull);
    expect(f.summary, isEmpty);
    f.touch('id');
    expect(f.errorFor('id'), 'Enter a tool ID.');
    expect(f.errorFor('url'), isNull);
  });

  test('a failed submit reveals every error with labels for the summary', () {
    final f = make();
    expect(f.submit(), isFalse);
    expect(f.summary.map((e) => (e.label, e.message)), [
      ('Tool ID', 'Enter a tool ID.'),
      ('Request path', 'Enter the path.'),
    ]);
  });

  test('fixing the value clears the error live once it has shown', () {
    final f = make();
    f.touch('id');
    expect(f.errorFor('id'), isNotNull);
    values['id'] = 'orders_refund';
    expect(f.errorFor('id'), isNull);
  });

  test('dirty tracking counts changed fields and resets on markClean', () {
    final f = make();
    expect(f.isDirty, isFalse);
    values['id'] = 'x';
    values['url'] = '/a';
    expect(f.dirtyCount, 2);
    expect(f.dirtyLabel, '2 unsaved changes');
    f.markClean(baseVersion: 4);
    expect(f.isDirty, isFalse);
    expect(f.baseVersion, 4);
  });

  test('server fieldErrors land under the field; other errors become the form error', () {
    final f = make();
    f.applyServerError(DioException(
      requestOptions: RequestOptions(path: '/v1/admin/tools'),
      response: Response(
        requestOptions: RequestOptions(path: '/v1/admin/tools'),
        statusCode: 400,
        data: {'error': 'BAD_REQUEST', 'message': 'tool.handler.beanName is required', 'fieldErrors': {'url': 'Bad path'}},
      ),
    ));
    expect(f.errorFor('url'), 'Bad path');
    expect(f.formError, isNull);
    f.changed('url');
    expect(f.errorFor('url'), isNot('Bad path'));

    f.applyServerError(DioException(
      requestOptions: RequestOptions(path: '/v1/admin/tools'),
      response: Response(
        requestOptions: RequestOptions(path: '/v1/admin/tools'),
        statusCode: 409,
        data: {'error': 'version_conflict', 'message': 'tool x was edited from v1 but v2 has been published since', 'currentVersion': 2},
      ),
    ));
    expect(f.formError, contains('v2 has been published'));
  });

  test('drafts save while dirty, load back, and clear on markClean', () async {
    final f = make(draftKey: 'yaya.draft.test');
    values['id'] = 'draft_me';
    f.changed('id');
    await Future<void>.delayed(const Duration(milliseconds: 1400));
    final d = await f.loadDraft();
    expect(d?.values['id'], 'draft_me');
    f.markClean();
    await Future<void>.delayed(Duration.zero);
    expect(await f.loadDraft(), isNull);
  });

  group('Validators', () {
    test('tool ids follow the model tool-name rule', () {
      expect(Validators.toolId('orders_refund'), isNull);
      expect(Validators.toolId('orders.refund'), isNotNull);
      expect(Validators.toolId('a b'), isNotNull);
    });

    test('json objects report where they broke', () {
      expect(Validators.jsonObject('{"type":"object"}').$1, isNull);
      expect(Validators.jsonObject('[1]').$1, 'Must be a JSON object ({ … }).');
      expect(Validators.jsonObject('{"a":').$1, startsWith('Not valid JSON'));
    });

    test('int ranges and urls', () {
      expect(Validators.intInRange('5', 1, 16), isNull);
      expect(Validators.intInRange('17', 1, 16), 'Use a number from 1 to 16.');
      expect(Validators.intInRange('', 1, 16), isNull);
      expect(Validators.httpUrl('https://id.acme.com'), isNull);
      expect(Validators.httpUrl('id.acme.com'), isNotNull);
    });
  });
}

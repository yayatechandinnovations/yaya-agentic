import 'package:flutter_test/flutter_test.dart';
import 'package:yaya_identity_console/features/policies/domain/entities/condition_builder.dart';

void main() {
  test('single numeric row compiles to a bare clause', () {
    final json = buildConditionJson(
        [const ConditionRow(scope: 'resource', attribute: 'amount', op: '>', value: '500')]);
    expect(json, '{">":[{"var":"resource.amount"},500]}');
  });

  test('string values stay strings', () {
    final json = buildConditionJson([
      const ConditionRow(scope: 'subject', attribute: 'department', op: '==', value: 'finance')
    ]);
    expect(json, '{"==":[{"var":"subject.department"},"finance"]}');
  });

  test('multiple rows combine under and/or', () {
    final rows = [
      const ConditionRow(scope: 'resource', attribute: 'amount', op: '>', value: '500'),
      const ConditionRow(scope: 'subject', attribute: 'level', op: '<', value: '3'),
    ];
    expect(buildConditionJson(rows),
        '{"and":[{">":[{"var":"resource.amount"},500]},{"<":[{"var":"subject.level"},3]}]}');
    expect(buildConditionJson(rows, allMustMatch: false), startsWith('{"or":['));
  });

  test('in splits comma lists and parses numbers per element', () {
    final json = buildConditionJson([
      const ConditionRow(scope: 'resource', attribute: 'zone', op: 'in', value: 'north, 7')
    ]);
    expect(json, '{"in":[{"var":"resource.zone"},["north",7]]}');
  });

  test('incomplete rows are dropped; nothing complete → null', () {
    expect(buildConditionJson([const ConditionRow()]), isNull);
    final json = buildConditionJson([
      const ConditionRow(), // incomplete
      const ConditionRow(scope: 'resource', attribute: 'amount', op: '>', value: '1'),
    ]);
    expect(json, '{">":[{"var":"resource.amount"},1]}');
  });
}

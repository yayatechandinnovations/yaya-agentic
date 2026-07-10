import 'dart:convert';

import 'package:equatable/equatable.dart';

/// One structured condition row: `<scope>.<attribute> <operator> <value>`,
/// e.g. `resource.amount > 500` or `subject.department in sales, support`.
class ConditionRow extends Equatable {
  const ConditionRow({
    this.scope = 'resource',
    this.attribute = '',
    this.op = '>',
    this.value = '',
  });

  static const scopes = ['subject', 'resource', 'environment'];
  static const operators = ['==', '!=', '>', '>=', '<', '<=', 'in'];

  final String scope;
  final String attribute;
  final String op;
  final String value;

  bool get isComplete => attribute.trim().isNotEmpty && value.trim().isNotEmpty;

  ConditionRow copyWith({String? scope, String? attribute, String? op, String? value}) =>
      ConditionRow(
        scope: scope ?? this.scope,
        attribute: attribute ?? this.attribute,
        op: op ?? this.op,
        value: value ?? this.value,
      );

  @override
  List<Object?> get props => [scope, attribute, op, value];
}

/// Compiles structured rows into the JSON-logic string the PDP evaluates.
/// Numeric-looking values become numbers; `in` splits its value on commas.
/// Returns null when no complete rows exist (no condition at all).
String? buildConditionJson(List<ConditionRow> rows, {bool allMustMatch = true}) {
  final complete = rows.where((r) => r.isComplete).toList();
  if (complete.isEmpty) return null;

  Object parseValue(String raw) {
    final t = raw.trim();
    final asNum = num.tryParse(t);
    return asNum ?? t;
  }

  Object clause(ConditionRow r) {
    final variable = {'var': '${r.scope}.${r.attribute.trim()}'};
    if (r.op == 'in') {
      final list = r.value.split(',').map((s) => parseValue(s)).toList();
      return {'in': [variable, list]};
    }
    return {r.op: [variable, parseValue(r.value)]};
  }

  final clauses = complete.map(clause).toList();
  final logic = clauses.length == 1
      ? clauses.single
      : {allMustMatch ? 'and' : 'or': clauses};
  return jsonEncode(logic);
}

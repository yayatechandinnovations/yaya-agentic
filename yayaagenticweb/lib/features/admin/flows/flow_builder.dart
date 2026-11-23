/// Widgets for the visual Session Start Flow builder — step cards, value-source
/// pickers, capture/branch editors, and the greeting editor. Operators compose
/// a flow graphically; the drafts serialize to the backend JSON. No JSON typing.
///
/// Every edit mutates the draft and then calls `onChanged`, which the editor
/// uses for dirty tracking and drafts. Fields that have a rule carry the
/// [FlowFields] name so their error shows right under them.
library;

import 'package:flutter/material.dart';

import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_resource_picker.dart';
import '../../../ui/tokens.dart';
import 'flow_drafts.dart';

// ---- small shared helpers ----------------------------------------------

Widget _select(
  String label,
  String value,
  Map<String, String> options,
  ValueChanged<String> onChanged, {
  List<String> extraValues = const [],
  String? wireKey,
  String? helper,
}) {
  final keys = {...options.keys, ...extraValues, if (value.isNotEmpty) value}.toList();
  return YSelectField<String>(
    label: label,
    wireKey: wireKey,
    helper: helper,
    // Nothing chosen shows as nothing chosen — never as the first option,
    // which would look picked but save empty.
    value: value.isEmpty ? null : value,
    hint: 'Choose…',
    options: [for (final k in keys) YOption(k, options[k] ?? k)],
    onChanged: (v) => onChanged(v ?? value),
  );
}

/// A kit text field that owns its controller, for draft objects that hold
/// plain strings. Key it by the object it edits so list edits don't shuffle text.
class FlowTextField extends StatefulWidget {
  const FlowTextField({
    super.key,
    required this.label,
    required this.initial,
    required this.onChanged,
    this.form,
    this.name,
    this.wireKey,
    this.hint,
    this.helper,
    this.maxLines = 1,
    this.required = false,
    this.optional = false,
    this.mono = false,
    this.autofocus = false,
    this.readOnly = false,
  });

  final String label;
  final String initial;
  final ValueChanged<String> onChanged;
  final YFormController? form;
  final String? name;
  final String? wireKey;
  final String? hint;
  final String? helper;
  final int maxLines;
  final bool required;
  final bool optional;
  final bool mono;
  final bool autofocus;
  final bool readOnly;

  @override
  State<FlowTextField> createState() => _FlowTextFieldState();
}

class _FlowTextFieldState extends State<FlowTextField> {
  late final _c = TextEditingController(text: widget.initial);

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => YTextField(
        label: widget.label,
        controller: _c,
        form: widget.form,
        name: widget.name,
        wireKey: widget.wireKey,
        hint: widget.hint,
        helper: widget.helper,
        required: widget.required,
        optional: widget.optional,
        mono: widget.mono,
        autofocus: widget.autofocus,
        readOnly: widget.readOnly,
        maxLines: widget.maxLines,
        minLines: widget.maxLines > 1 ? 2 : null,
        onChanged: widget.onChanged,
      );
}

Widget _sectionLabel(BuildContext context, String text) => Padding(
      padding: const EdgeInsets.only(top: 18, bottom: 8),
      child: Semantics(
        header: true,
        child: Text(text.toUpperCase(),
            style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                letterSpacing: 0.5,
                color: Theme.of(context).colorScheme.onSurfaceVariant)),
      ),
    );

Widget _note(BuildContext context, String text) => Text(text,
    style: TextStyle(fontSize: 13, height: 1.45, color: Theme.of(context).colorScheme.onSurfaceVariant));

/// The error for [field] when the editor has a form, for rows that aren't a single input.
String? _errorOf(YFormController? form, String field) => form?.errorFor(field);

Widget _rowError(BuildContext context, YFormController? form, String field) {
  final err = _errorOf(form, field);
  return KeyedSubtree(
    key: form?.keyFor(field),
    child: err == null ? const SizedBox.shrink() : YFieldMessage(error: err),
  );
}

const _gap = SizedBox(height: 12);

// ---- top-level steps editor --------------------------------------------

class FlowStepsEditor extends StatelessWidget {
  const FlowStepsEditor({
    super.key,
    required this.flow,
    required this.toolIds,
    required this.onChanged,
    this.form,
  });

  final FlowDraft flow;
  final List<String> toolIds;
  final VoidCallback onChanged;
  final YFormController? form;

  void _add(String kind) {
    final key = _uniqueKey(kind.toLowerCase());
    flow.steps.add(switch (kind) {
      'Elicit' => ElicitDraft(key, promptTemplate: 'Ask the user…'),
      'Decision' => DecisionDraft(key),
      _ => RunToolDraft(key),
    });
    onChanged();
  }

  String _uniqueKey(String base) {
    final existing = flow.stepKeys.toSet();
    if (!existing.contains(base)) return base;
    var i = 2;
    while (existing.contains('$base$i')) {
      i++;
    }
    return '$base$i';
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (flow.steps.isEmpty) ...[
          _note(context, 'No steps yet — the conversation opens with the greeting below. Add a step to look the user up, ask them something, or branch.'),
          _gap,
        ],
        for (var i = 0; i < flow.steps.length; i++)
          _StepCard(
            key: ObjectKey(flow.steps[i]),
            index: i,
            total: flow.steps.length,
            flow: flow,
            toolIds: toolIds,
            onChanged: onChanged,
            form: form,
          ),
        Wrap(spacing: 8, runSpacing: 8, children: [
          OutlinedButton.icon(
              onPressed: () => _add('RunTool'),
              icon: const Icon(Icons.build_outlined, size: 18),
              label: const Text('Run a tool')),
          OutlinedButton.icon(
              onPressed: () => _add('Elicit'),
              icon: const Icon(Icons.help_outline, size: 18),
              label: const Text('Ask the user')),
          OutlinedButton.icon(
              onPressed: () => _add('Decision'),
              icon: const Icon(Icons.call_split, size: 18),
              label: const Text('Branch')),
        ]),
      ],
    );
  }
}

class _StepCard extends StatelessWidget {
  const _StepCard({
    super.key,
    required this.index,
    required this.total,
    required this.flow,
    required this.toolIds,
    required this.onChanged,
    this.form,
  });

  final int index;
  final int total;
  final FlowDraft flow;
  final List<String> toolIds;
  final VoidCallback onChanged;
  final YFormController? form;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final step = flow.steps[index];
    final (icon, label) = switch (step) {
      RunToolDraft() => (Icons.build_outlined, 'Run a tool'),
      ElicitDraft() => (Icons.help_outline, 'Ask the user'),
      DecisionDraft() => (Icons.call_split, 'Branch'),
    };
    return Card(
      margin: const EdgeInsets.only(bottom: 14),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(18, 12, 12, 18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(children: [
              Icon(icon, size: 18, color: scheme.primary),
              const SizedBox(width: 8),
              Text('${index + 1}. $label', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: scheme.onSurface)),
              const Spacer(),
              IconButton(
                  tooltip: 'Move step ${index + 1} up',
                  onPressed: index == 0 ? null : () => _swap(index, index - 1),
                  icon: const Icon(Icons.arrow_upward, size: 18)),
              IconButton(
                  tooltip: 'Move step ${index + 1} down',
                  onPressed: index == total - 1 ? null : () => _swap(index, index + 1),
                  icon: const Icon(Icons.arrow_downward, size: 18)),
              IconButton(
                  tooltip: 'Remove step ${index + 1}',
                  onPressed: () {
                    flow.steps.removeAt(index);
                    onChanged();
                  },
                  icon: const Icon(Icons.delete_outline, size: 18)),
            ]),
            const SizedBox(height: 6),
            Padding(
              padding: const EdgeInsets.only(right: 6),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                FlowTextField(
                  label: 'Step ID',
                  wireKey: 'key',
                  initial: step.key,
                  form: form,
                  name: FlowFields.step(index, 'key'),
                  required: true,
                  mono: true,
                  hint: 'e.g. lookup',
                  helper: 'Rules and “From an earlier step” refer to the step by this ID.',
                  onChanged: (v) {
                    step.key = v.trim();
                    onChanged();
                  },
                ),
                switch (step) {
                  RunToolDraft s => _RunToolEditor(step: s, index: index, flow: flow, toolIds: toolIds, onChanged: onChanged, form: form),
                  ElicitDraft s => _ElicitEditor(step: s, index: index, flow: flow, onChanged: onChanged, form: form),
                  DecisionDraft s => _DecisionEditor(step: s, index: index, flow: flow, onChanged: onChanged, form: form),
                },
              ]),
            ),
          ],
        ),
      ),
    );
  }

  void _swap(int a, int b) {
    final t = flow.steps[a];
    flow.steps[a] = flow.steps[b];
    flow.steps[b] = t;
    onChanged();
  }
}

// ---- RunTool -----------------------------------------------------------

class _RunToolEditor extends StatelessWidget {
  const _RunToolEditor(
      {required this.step, required this.index, required this.flow, required this.toolIds, required this.onChanged, this.form});
  final RunToolDraft step;
  final int index;
  final FlowDraft flow;
  final List<String> toolIds;
  final VoidCallback onChanged;
  final YFormController? form;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _gap,
        YResourcePicker(
          label: 'Tool',
          wireKey: 'tool',
          form: form,
          name: FlowFields.step(index, 'tool'),
          required: true,
          value: step.toolId.isEmpty ? null : step.toolId,
          hint: toolIds.isEmpty ? 'No tools on this tenant yet' : 'Search tools…',
          helper: 'Runs before the user sees anything; its result can be saved and tested below.',
          resources: [for (final t in toolIds) YResource(id: t)],
          onChanged: (v) {
            step.toolId = v ?? '';
            onChanged();
          },
        ),
        _sectionLabel(context, 'Inputs'),
        if (step.inputs.isEmpty) _note(context, 'No inputs — the tool runs with what the model would otherwise send.'),
        for (var i = 0; i < step.inputs.length; i++)
          _InputRow(
              key: ObjectKey(step.inputs[i]),
              input: step.inputs[i],
              field: FlowFields.input(index, i),
              flow: flow,
              form: form,
              onChanged: onChanged,
              onRemove: () {
                step.inputs.removeAt(i);
                onChanged();
              }),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
              onPressed: () {
                step.inputs.add(InputDraft());
                onChanged();
              },
              icon: const Icon(Icons.add, size: 16),
              label: const Text('Add input')),
        ),
        _sectionLabel(context, 'Save results as'),
        for (var i = 0; i < step.captures.length; i++)
          _CaptureRow(
              key: ObjectKey(step.captures[i]),
              capture: step.captures[i],
              field: FlowFields.capture(index, i),
              form: form,
              onChanged: onChanged,
              onRemove: () {
                step.captures.removeAt(i);
                onChanged();
              }),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
              onPressed: () {
                step.captures.add(CaptureDraft());
                onChanged();
              },
              icon: const Icon(Icons.add, size: 16),
              label: const Text('Save a result')),
        ),
        _sectionLabel(context, 'After this step'),
        _BranchList(branches: step.next, stepIndex: index, flow: flow, form: form, onChanged: onChanged),
        _gap,
        _select('If the tool fails', step.onErrorMode, kOnError, (v) {
          step.onErrorMode = v;
          onChanged();
        }, wireKey: 'onError'),
      ],
    );
  }
}

class _InputRow extends StatelessWidget {
  const _InputRow(
      {super.key, required this.input, required this.field, required this.flow, required this.onChanged, required this.onRemove, this.form});
  final InputDraft input;
  final String field;
  final FlowDraft flow;
  final YFormController? form;
  final VoidCallback onChanged;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    return _Boxed(
      onRemove: onRemove,
      removeLabel: 'Remove input',
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Expanded(
            child: FlowTextField(
              label: 'Argument',
              wireKey: 'argName',
              initial: input.argName,
              form: form,
              name: field,
              required: true,
              mono: true,
              hint: 'e.g. phone',
              onChanged: (v) {
                input.argName = v.trim();
                onChanged();
              },
            ),
          ),
          const SizedBox(width: 12),
          SizedBox(
            width: 180,
            child: _select('Fill', input.mode, kBindingModes, (v) {
              input.mode = v;
              onChanged();
            }, wireKey: 'mode'),
          ),
        ]),
        _gap,
        _SourceEditor(source: input.source, flow: flow, onChanged: onChanged),
      ]),
    );
  }
}

/// A removable bordered group (one input, capture or rule).
class _Boxed extends StatelessWidget {
  const _Boxed({required this.child, required this.onRemove, required this.removeLabel});
  final Widget child;
  final VoidCallback onRemove;
  final String removeLabel;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.fromLTRB(12, 8, 4, 12),
      decoration: BoxDecoration(
        border: Border.all(color: scheme.outlineVariant),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(child: Padding(padding: const EdgeInsets.only(top: 4), child: child)),
        IconButton(tooltip: removeLabel, onPressed: onRemove, icon: const Icon(Icons.close, size: 18)),
      ]),
    );
  }
}

// ---- value source picker -----------------------------------------------

class _SourceEditor extends StatelessWidget {
  const _SourceEditor({required this.source, required this.flow, required this.onChanged});
  final ValueSourceDraft source;
  final FlowDraft flow;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      SizedBox(
        width: 220,
        child: _select('Value comes from', source.kind, kSourceKinds, (v) {
          source.kind = v;
          onChanged();
        }, wireKey: 'source.kind'),
      ),
      const SizedBox(width: 12),
      Expanded(child: KeyedSubtree(key: ValueKey(source.kind), child: _sourceDetail(context))),
    ]);
  }

  Widget _sourceDetail(BuildContext context) {
    void set(void Function() f) {
      f();
      onChanged();
    }

    switch (source.kind) {
      case 'Channel':
        return _select('Field', source.channelField, {for (final f in kChannelFields) f: f},
            (v) => set(() => source.channelField = v),
            extraValues: [source.channelField], wireKey: 'field');
      case 'Principal':
        return FlowTextField(
            label: 'Claim path',
            wireKey: 'claimPath',
            initial: source.claimPath,
            mono: true,
            hint: 'e.g. sub, claims.org',
            onChanged: (v) => set(() => source.claimPath = v.trim()));
      case 'Entity':
        final ents = flow.knownEntities;
        return ents.isEmpty
            ? FlowTextField(
                label: 'Saved value name',
                wireKey: 'entityName',
                initial: source.entityName,
                mono: true,
                helper: 'No step saves a shareable value yet.',
                onChanged: (v) => set(() => source.entityName = v.trim()))
            : _select('Saved value', source.entityName,
                {for (final e in ents) e: e}, (v) => set(() => source.entityName = v),
                extraValues: [if (source.entityName.isNotEmpty) source.entityName], wireKey: 'entityName');
      case 'StepOutput':
        final keys = flow.stepKeys;
        return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          SizedBox(
            width: 170,
            child: keys.isEmpty
                ? FlowTextField(
                    label: 'Step', wireKey: 'stepKey', initial: source.stepKey, mono: true,
                    onChanged: (v) => set(() => source.stepKey = v.trim()))
                : _select('Step', source.stepKey,
                    {for (final k in keys) k: k}, (v) => set(() => source.stepKey = v),
                    extraValues: [if (source.stepKey.isNotEmpty) source.stepKey], wireKey: 'stepKey'),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: FlowTextField(
                label: 'Field in its result',
                wireKey: 'jsonPointer',
                initial: source.jsonPointer,
                mono: true,
                hint: 'e.g. /account/id',
                onChanged: (v) => set(() => source.jsonPointer = v.trim())),
          ),
        ]);
      case 'Literal':
      default:
        return FlowTextField(
            label: 'Value',
            wireKey: 'value',
            initial: source.literal,
            helper: 'true, false and numbers are sent as such; anything else as text.',
            onChanged: (v) => set(() => source.literal = v));
    }
  }
}

// ---- capture -----------------------------------------------------------

class _CaptureRow extends StatelessWidget {
  const _CaptureRow(
      {super.key, required this.capture, required this.field, required this.onChanged, required this.onRemove, this.form});
  final CaptureDraft capture;
  final String field;
  final YFormController? form;
  final VoidCallback onChanged;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    return _Boxed(
      onRemove: onRemove,
      removeLabel: 'Remove saved result',
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          SizedBox(
            width: 180,
            child: FlowTextField(
                label: 'Field in the result',
                wireKey: 'jsonPointer',
                initial: capture.jsonPointer,
                mono: true,
                hint: '/name',
                onChanged: (v) {
                  capture.jsonPointer = v.trim();
                  onChanged();
                }),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: FlowTextField(
                label: 'Save as',
                wireKey: 'target.name',
                initial: capture.name,
                form: form,
                name: field,
                required: true,
                mono: true,
                hint: 'e.g. customerName',
                onChanged: (v) {
                  capture.name = v.trim();
                  onChanged();
                }),
          ),
          const SizedBox(width: 12),
          SizedBox(
            width: 150,
            child: _select('Kept as', capture.targetKind, kCaptureKinds, (v) {
              capture.targetKind = v;
              onChanged();
            }),
          ),
        ]),
        if (capture.targetKind == 'Entity')
          YSwitchField(
            title: 'Share with the assistant',
            wireKey: 'visibleToLlm',
            description: 'The assistant can read this value during the conversation.',
            value: capture.visibleToLlm,
            onChanged: (v) {
              capture.visibleToLlm = v;
              onChanged();
            },
          ),
      ]),
    );
  }
}

/// Entity = a saved value later steps, the greeting and (optionally) the
/// assistant can use; StepVar = scratch, this flow only.
const kCaptureKinds = <String, String>{'Entity': 'Shareable', 'StepVar': 'Scratch'};

// ---- branches ----------------------------------------------------------

class _BranchList extends StatelessWidget {
  const _BranchList({required this.branches, required this.stepIndex, required this.flow, required this.onChanged, this.form});
  final List<BranchDraft> branches;
  final int stepIndex;
  final FlowDraft flow;
  final YFormController? form;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      if (branches.isEmpty) _note(context, 'Continues to the next step.'),
      for (var i = 0; i < branches.length; i++)
        _BranchEditor(
            key: ObjectKey(branches[i]),
            branch: branches[i],
            field: FlowFields.branch(stepIndex, i),
            flow: flow,
            form: form,
            onChanged: onChanged,
            onRemove: () {
              branches.removeAt(i);
              onChanged();
            }),
      Align(
        alignment: Alignment.centerLeft,
        child: TextButton.icon(
            onPressed: () {
              branches.add(BranchDraft());
              onChanged();
            },
            icon: const Icon(Icons.add, size: 16),
            label: const Text('Add a rule')),
      ),
    ]);
  }
}

class _BranchEditor extends StatelessWidget {
  const _BranchEditor(
      {super.key, required this.branch, required this.field, required this.flow, required this.onChanged, required this.onRemove, this.form});
  final BranchDraft branch;
  final String field;
  final FlowDraft flow;
  final YFormController? form;
  final VoidCallback onChanged;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final noRight = branch.op == 'EXISTS' || branch.op == 'ABSENT' || branch.op == 'TRUTHY';
    return _Boxed(
      onRemove: onRemove,
      removeLabel: 'Remove rule',
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        YSwitchField(
          title: 'Always',
          description: 'Skip the test — this rule matches every time.',
          value: branch.always,
          onChanged: (v) {
            branch.always = v;
            onChanged();
          },
        ),
        if (!branch.always) ...[
          const SizedBox(height: 6),
          _SourceEditor(source: branch.left, flow: flow, onChanged: onChanged),
          _gap,
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            SizedBox(
                width: 220,
                child: _select('Test', branch.op, kOps, (v) {
                  branch.op = v;
                  onChanged();
                }, wireKey: 'op')),
            const SizedBox(width: 12),
            if (!noRight)
              Expanded(
                  child: FlowTextField(
                      label: 'Value',
                      wireKey: 'right',
                      initial: branch.right,
                      onChanged: (v) {
                        branch.right = v;
                        onChanged();
                      })),
          ]),
        ],
        _gap,
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          SizedBox(
              width: 220,
              child: _select('Then', branch.targetKind, kTargetKinds, (v) {
                branch.targetKind = v;
                onChanged();
              }, wireKey: 'target.kind')),
          const SizedBox(width: 12),
          Expanded(child: KeyedSubtree(key: ValueKey(branch.targetKind), child: _targetDetail())),
        ]),
        _rowError(context, form, field),
      ]),
    );
  }

  Widget _targetDetail() {
    switch (branch.targetKind) {
      case 'Step':
        final keys = flow.stepKeys;
        return keys.isEmpty
            ? FlowTextField(
                label: 'Step ID',
                wireKey: 'target.key',
                initial: branch.stepKey,
                mono: true,
                onChanged: (v) {
                  branch.stepKey = v.trim();
                  onChanged();
                })
            : _select('Step', branch.stepKey, {for (final k in keys) k: k}, (v) {
                branch.stepKey = v;
                onChanged();
              }, extraValues: [if (branch.stepKey.isNotEmpty) branch.stepKey], wireKey: 'target.key');
      case 'Abort':
        return FlowTextField(
            label: 'Reason',
            wireKey: 'target.reason',
            initial: branch.reason,
            optional: true,
            helper: 'Logged for audit; the user sees the fallback greeting.',
            onChanged: (v) {
              branch.reason = v;
              onChanged();
            });
      case 'End':
      default:
        return FlowTextField(
            label: 'Outcome',
            wireKey: 'target.outcomeLabel',
            initial: branch.outcomeLabel,
            mono: true,
            hint: 'success / fallback / …',
            helper: 'Picks the greeting template for this outcome.',
            onChanged: (v) {
              branch.outcomeLabel = v.trim();
              onChanged();
            });
    }
  }
}

// ---- Elicit ------------------------------------------------------------

class _ElicitEditor extends StatelessWidget {
  const _ElicitEditor({required this.step, required this.index, required this.flow, required this.onChanged, this.form});
  final ElicitDraft step;
  final int index;
  final FlowDraft flow;
  final YFormController? form;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      _gap,
      FlowTextField(
          label: 'Prompt',
          wireKey: 'promptTemplate',
          initial: step.promptTemplate,
          form: form,
          name: FlowFields.step(index, 'prompt'),
          required: true,
          maxLines: 2,
          hint: 'e.g. Enter the code we texted you, {customerName}',
          helper: 'What the user is asked. {savedValue} inserts a value saved earlier.',
          onChanged: (v) {
            step.promptTemplate = v;
            onChanged();
          }),
      _gap,
      FlowTextField(
          label: 'Quick replies',
          wireKey: 'quickReplyTemplates',
          initial: step.quickReplies.join(', '),
          optional: true,
          hint: 'e.g. Resend code, Talk to a person',
          helper: 'Comma-separated buttons shown under the prompt.',
          onChanged: (v) {
            step.quickReplies = _splitCsv(v);
            onChanged();
          }),
      _gap,
      FlowTextField(
          label: 'Widget',
          wireKey: 'uiHintKind',
          initial: step.uiHintKind,
          optional: true,
          mono: true,
          hint: 'e.g. otp_entry',
          helper: 'A chat widget to show instead of a plain text box.',
          onChanged: (v) {
            step.uiHintKind = v.trim();
            onChanged();
          }),
      _sectionLabel(context, 'Save their answer as'),
      Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(
            child: FlowTextField(
                label: 'Name',
                wireKey: 'captureTarget.name',
                initial: step.captureName,
                form: form,
                name: FlowFields.step(index, 'captureName'),
                required: true,
                mono: true,
                hint: 'e.g. code',
                onChanged: (v) {
                  step.captureName = v.trim();
                  onChanged();
                })),
        const SizedBox(width: 12),
        SizedBox(
          width: 150,
          child: _select('Kept as', step.captureKind, kCaptureKinds, (v) {
            step.captureKind = v;
            onChanged();
          }),
        ),
      ]),
      if (step.captureKind == 'Entity')
        YSwitchField(
          title: 'Share with the assistant',
          wireKey: 'visibleToLlm',
          description: 'The assistant can read the answer during the conversation.',
          value: step.visibleToLlm,
          onChanged: (v) {
            step.visibleToLlm = v;
            onChanged();
          },
        ),
      _sectionLabel(context, 'After this step'),
      _BranchList(branches: step.next, stepIndex: index, flow: flow, form: form, onChanged: onChanged),
    ]);
  }
}

// ---- Decision ----------------------------------------------------------

class _DecisionEditor extends StatelessWidget {
  const _DecisionEditor({required this.step, required this.index, required this.flow, required this.onChanged, this.form});
  final DecisionDraft step;
  final int index;
  final FlowDraft flow;
  final YFormController? form;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      _sectionLabel(context, 'Rules (first match wins)'),
      _BranchList(branches: step.branches, stepIndex: index, flow: flow, form: form, onChanged: onChanged),
      _sectionLabel(context, 'Otherwise'),
      Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(
            width: 220,
            child: _select('Then', step.defaultKind, kTargetKinds, (v) {
              step.defaultKind = v;
              onChanged();
            }, wireKey: 'defaultTarget')),
        const SizedBox(width: 12),
        Expanded(child: KeyedSubtree(key: ValueKey(step.defaultKind), child: _defaultDetail())),
      ]),
      _rowError(context, form, FlowFields.step(index, 'default')),
    ]);
  }

  Widget _defaultDetail() {
    switch (step.defaultKind) {
      case 'Step':
        final keys = flow.stepKeys;
        return keys.isEmpty
            ? FlowTextField(
                label: 'Step ID',
                wireKey: 'defaultTarget.key',
                initial: step.defaultStep,
                mono: true,
                onChanged: (v) {
                  step.defaultStep = v.trim();
                  onChanged();
                })
            : _select('Step', step.defaultStep, {for (final k in keys) k: k}, (v) {
                step.defaultStep = v;
                onChanged();
              }, extraValues: [if (step.defaultStep.isNotEmpty) step.defaultStep], wireKey: 'defaultTarget.key');
      case 'Abort':
        return FlowTextField(
            label: 'Reason',
            wireKey: 'defaultTarget.reason',
            initial: step.defaultReason,
            optional: true,
            helper: 'Logged for audit; the user sees the fallback greeting.',
            onChanged: (v) {
              step.defaultReason = v;
              onChanged();
            });
      case 'End':
      default:
        return FlowTextField(
            label: 'Outcome',
            wireKey: 'defaultTarget.outcomeLabel',
            initial: step.defaultOutcome,
            mono: true,
            onChanged: (v) {
              step.defaultOutcome = v.trim();
              onChanged();
            });
    }
  }
}

// ---- greeting ----------------------------------------------------------

class GreetingEditor extends StatelessWidget {
  const GreetingEditor({super.key, required this.greeting, required this.flow, required this.onChanged, this.form});
  final GreetingDraft greeting;
  final FlowDraft flow;
  final VoidCallback onChanged;
  final YFormController? form;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      YEnumCards<String>(
        label: 'Who writes the first message',
        wireKey: 'greeting.mode',
        required: true,
        value: greeting.mode,
        options: const [
          YEnumOption('STATIC_TEMPLATE', 'Write it myself', wire: 'STATIC_TEMPLATE',
              description: 'Your template, filled with saved values. Predictable and instant.'),
          YEnumOption('LLM_COMPOSED', 'Let the assistant write it', wire: 'LLM_COMPOSED',
              description: 'The assistant composes it from your instruction and the values you share. Costs a model call.'),
          YEnumOption('HYBRID', 'Blend', wire: 'HYBRID',
              description: 'Your template, smoothed by the assistant within your instruction.'),
        ],
        onChanged: (v) {
          greeting.mode = v;
          onChanged();
        },
      ),
      _gap,
      if (greeting.mode != 'LLM_COMPOSED') ...[
        FlowTextField(
            label: 'Greeting when the flow succeeds',
            wireKey: 'outcomeGreetingTemplates.success',
            initial: greeting.successTemplate,
            optional: true,
            maxLines: 2,
            hint: 'Welcome back, {customerName}!',
            helper: 'Used when the flow ends with the “success” outcome. {name} inserts a saved value.',
            onChanged: (v) {
              greeting.successTemplate = v;
              onChanged();
            }),
        _gap,
      ],
      FlowTextField(
          label: 'Fallback greeting',
          wireKey: 'fallbackGreeting',
          initial: greeting.fallbackGreeting,
          optional: true,
          maxLines: 2,
          hint: 'Hi! How can I help you today?',
          helper: 'Shown when the flow stops early or a step fails.',
          onChanged: (v) {
            greeting.fallbackGreeting = v;
            onChanged();
          }),
      _gap,
      FlowTextField(
          label: 'Quick replies',
          wireKey: 'quickReplyTemplates',
          initial: greeting.quickReplies.join(', '),
          optional: true,
          hint: 'e.g. Track my order, Talk to a person',
          helper: 'Comma-separated buttons shown under the first message.',
          onChanged: (v) {
            greeting.quickReplies = _splitCsv(v);
            onChanged();
          }),
      if (greeting.isLlm) ...[
        _sectionLabel(context, 'Assistant-written opening'),
        FlowTextField(
            label: 'Instruction to the assistant',
            wireKey: 'llmOpening.instruction',
            initial: greeting.instruction,
            maxLines: 2,
            helper: 'One or two sentences on tone and what to offer.',
            onChanged: (v) {
              greeting.instruction = v;
              onChanged();
            }),
        _gap,
        YFieldShell(
          label: 'Values the assistant is briefed with',
          wireKey: 'llmOpening.briefingEntities',
          optional: true,
          helper: 'Only these saved values are shared with the assistant for the opening.',
          child: _BriefingPicker(greeting: greeting, flow: flow, onChanged: onChanged),
        ),
      ],
    ]);
  }
}

class _BriefingPicker extends StatelessWidget {
  const _BriefingPicker({required this.greeting, required this.flow, required this.onChanged});
  final GreetingDraft greeting;
  final FlowDraft flow;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    final options = {...flow.knownEntities, ...greeting.briefingEntities}.toList();
    if (options.isEmpty) {
      return _note(context, 'No shareable saved values yet — save a step’s result as “Shareable” first.');
    }
    return Wrap(spacing: 8, runSpacing: 6, children: [
      for (final e in options)
        FilterChip(
          label: Text(e, style: monoStyle(context, size: 12.5)),
          selected: greeting.briefingEntities.contains(e),
          onSelected: (sel) {
            if (sel) {
              greeting.briefingEntities.add(e);
            } else {
              greeting.briefingEntities.remove(e);
            }
            onChanged();
          },
        ),
    ]);
  }
}

List<String> _splitCsv(String v) =>
    v.split(',').map((e) => e.trim()).where((e) => e.isNotEmpty).toList();

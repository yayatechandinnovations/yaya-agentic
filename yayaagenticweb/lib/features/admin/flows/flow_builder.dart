/// Widgets for the visual Session Start Flow builder — step cards, value-source
/// pickers, capture/branch editors, and the greeting editor. Operators compose
/// a flow graphically; the drafts serialize to the backend JSON. No JSON typing.
library;

import 'package:flutter/material.dart';

import 'flow_drafts.dart';

// ---- small shared helpers ----------------------------------------------

Widget _dropdown(String label, String value, Map<String, String> options,
    ValueChanged<String> onChanged,
    {List<String> extraValues = const []}) {
  final keys = {...options.keys, ...extraValues, value}.toList();
  return DropdownButtonFormField<String>(
    initialValue: keys.contains(value) ? value : keys.first,
    isExpanded: true,
    decoration: InputDecoration(labelText: label, border: const OutlineInputBorder(), isDense: true),
    items: keys
        .map((k) => DropdownMenuItem(value: k, child: Text(options[k] ?? k, overflow: TextOverflow.ellipsis)))
        .toList(),
    onChanged: (v) => onChanged(v ?? value),
  );
}

Widget _text(String label, String initial, ValueChanged<String> onChanged,
    {int maxLines = 1, String? hint}) {
  return TextFormField(
    initialValue: initial,
    maxLines: maxLines,
    style: const TextStyle(fontSize: 13),
    decoration: InputDecoration(
        labelText: label, hintText: hint, border: const OutlineInputBorder(), isDense: true),
    onChanged: onChanged,
  );
}

Widget _sectionLabel(BuildContext context, String text) => Padding(
      padding: const EdgeInsets.only(top: 12, bottom: 6),
      child: Text(text.toUpperCase(),
          style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: Theme.of(context).colorScheme.outline, letterSpacing: 0.6)),
    );

// ---- top-level steps editor --------------------------------------------

class FlowStepsEditor extends StatelessWidget {
  const FlowStepsEditor({
    super.key,
    required this.flow,
    required this.toolIds,
    required this.onChanged,
  });

  final FlowDraft flow;
  final List<String> toolIds;
  final VoidCallback onChanged;

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
        Text('These steps run automatically when a conversation starts, top to bottom.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.outline)),
        const SizedBox(height: 8),
        for (var i = 0; i < flow.steps.length; i++)
          _StepCard(
            key: ValueKey(flow.steps[i]),
            index: i,
            total: flow.steps.length,
            flow: flow,
            toolIds: toolIds,
            onChanged: onChanged,
          ),
        const SizedBox(height: 8),
        Wrap(spacing: 8, children: [
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
  });

  final int index;
  final int total;
  final FlowDraft flow;
  final List<String> toolIds;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    final step = flow.steps[index];
    final (icon, label) = switch (step) {
      RunToolDraft() => (Icons.build_outlined, 'Run a tool'),
      ElicitDraft() => (Icons.help_outline, 'Ask the user'),
      DecisionDraft() => (Icons.call_split, 'Branch'),
    };
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(children: [
              Icon(icon, size: 18, color: Theme.of(context).colorScheme.primary),
              const SizedBox(width: 6),
              Text('${index + 1}. $label', style: Theme.of(context).textTheme.titleSmall),
              const Spacer(),
              IconButton(
                  visualDensity: VisualDensity.compact,
                  tooltip: 'Move up',
                  onPressed: index == 0 ? null : () { _swap(index, index - 1); },
                  icon: const Icon(Icons.arrow_upward, size: 18)),
              IconButton(
                  visualDensity: VisualDensity.compact,
                  tooltip: 'Move down',
                  onPressed: index == total - 1 ? null : () { _swap(index, index + 1); },
                  icon: const Icon(Icons.arrow_downward, size: 18)),
              IconButton(
                  visualDensity: VisualDensity.compact,
                  tooltip: 'Remove',
                  onPressed: () { flow.steps.removeAt(index); onChanged(); },
                  icon: const Icon(Icons.delete_outline, size: 18)),
            ]),
            const SizedBox(height: 8),
            _text('Step id', step.key, (v) { step.key = v.trim(); onChanged(); },
                hint: 'Referenced by branches and "from an earlier step"'),
            switch (step) {
              RunToolDraft s => _RunToolEditor(step: s, flow: flow, toolIds: toolIds, onChanged: onChanged),
              ElicitDraft s => _ElicitEditor(step: s, flow: flow, onChanged: onChanged),
              DecisionDraft s => _DecisionEditor(step: s, flow: flow, onChanged: onChanged),
            },
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
      {required this.step, required this.flow, required this.toolIds, required this.onChanged});
  final RunToolDraft step;
  final FlowDraft flow;
  final List<String> toolIds;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    final toolOptions = {for (final t in {...toolIds, if (step.toolId.isNotEmpty) step.toolId}) t: t};
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 8),
        _dropdown('Tool', step.toolId.isEmpty ? (toolOptions.keys.isEmpty ? '' : toolOptions.keys.first) : step.toolId,
            toolOptions.isEmpty ? {'': '— no tools —'} : toolOptions,
            (v) { step.toolId = v; onChanged(); }),
        _sectionLabel(context, 'Inputs'),
        for (var i = 0; i < step.inputs.length; i++)
          _InputRow(
              input: step.inputs[i], flow: flow, onChanged: onChanged,
              onRemove: () { step.inputs.removeAt(i); onChanged(); }),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
              onPressed: () { step.inputs.add(InputDraft()); onChanged(); },
              icon: const Icon(Icons.add, size: 16), label: const Text('Add input')),
        ),
        _sectionLabel(context, 'Save results as'),
        for (var i = 0; i < step.captures.length; i++)
          _CaptureRow(
              capture: step.captures[i], onChanged: onChanged,
              onRemove: () { step.captures.removeAt(i); onChanged(); }),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
              onPressed: () { step.captures.add(CaptureDraft()); onChanged(); },
              icon: const Icon(Icons.add, size: 16), label: const Text('Save a result')),
        ),
        _sectionLabel(context, 'After this step'),
        _BranchList(branches: step.next, flow: flow, onChanged: onChanged),
        const SizedBox(height: 8),
        _dropdown('On error', step.onErrorMode, kOnError, (v) { step.onErrorMode = v; onChanged(); }),
      ],
    );
  }
}

class _InputRow extends StatelessWidget {
  const _InputRow({required this.input, required this.flow, required this.onChanged, required this.onRemove});
  final InputDraft input;
  final FlowDraft flow;
  final VoidCallback onChanged;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Column(children: [
        Row(children: [
          Expanded(child: _text('Argument', input.argName, (v) => input.argName = v.trim())),
          const SizedBox(width: 8),
          SizedBox(
            width: 150,
            child: _dropdown('Fill', input.mode, kBindingModes, (v) { input.mode = v; onChanged(); }),
          ),
          IconButton(onPressed: onRemove, icon: const Icon(Icons.close, size: 16)),
        ]),
        const SizedBox(height: 6),
        _SourceEditor(source: input.source, flow: flow, onChanged: onChanged),
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
        width: 180,
        child: _dropdown('Source', source.kind, kSourceKinds, (v) { source.kind = v; onChanged(); }),
      ),
      const SizedBox(width: 8),
      Expanded(child: _sourceDetail(context)),
    ]);
  }

  Widget _sourceDetail(BuildContext context) {
    switch (source.kind) {
      case 'Channel':
        return _dropdown('Field', source.channelField, {for (final f in kChannelFields) f: f},
            (v) => source.channelField = v, extraValues: [source.channelField]);
      case 'Principal':
        return _text('Claim path', source.claimPath, (v) => source.claimPath = v.trim(),
            hint: 'e.g. sub, claims.org');
      case 'Entity':
        final ents = flow.knownEntities;
        return ents.isEmpty
            ? _text('Saved value name', source.entityName, (v) => source.entityName = v.trim())
            : _dropdown('Saved value', source.entityName.isEmpty ? ents.first : source.entityName,
                {for (final e in ents) e: e}, (v) => source.entityName = v,
                extraValues: [if (source.entityName.isNotEmpty) source.entityName]);
      case 'StepOutput':
        final keys = flow.stepKeys;
        return Row(children: [
          SizedBox(
            width: 130,
            child: keys.isEmpty
                ? _text('Step', source.stepKey, (v) => source.stepKey = v.trim())
                : _dropdown('Step', source.stepKey.isEmpty ? keys.first : source.stepKey,
                    {for (final k in keys) k: k}, (v) => source.stepKey = v,
                    extraValues: [if (source.stepKey.isNotEmpty) source.stepKey]),
          ),
          const SizedBox(width: 8),
          Expanded(child: _text('Field (JSON pointer)', source.jsonPointer,
              (v) => source.jsonPointer = v.trim(), hint: 'e.g. /account/id')),
        ]);
      case 'Literal':
      default:
        return _text('Value', source.literal, (v) => source.literal = v);
    }
  }
}

// ---- capture -----------------------------------------------------------

class _CaptureRow extends StatelessWidget {
  const _CaptureRow({required this.capture, required this.onChanged, required this.onRemove});
  final CaptureDraft capture;
  final VoidCallback onChanged;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Column(children: [
        Row(children: [
          SizedBox(
              width: 150,
              child: _text('Result field', capture.jsonPointer, (v) => capture.jsonPointer = v.trim(),
                  hint: '/name')),
          const SizedBox(width: 8),
          Expanded(child: _text('Save as', capture.name, (v) { capture.name = v.trim(); onChanged(); })),
          const SizedBox(width: 8),
          SizedBox(
            width: 130,
            child: _dropdown('Kind', capture.targetKind,
                const {'Entity': 'Shareable', 'StepVar': 'Scratch'},
                (v) { capture.targetKind = v; onChanged(); }),
          ),
          IconButton(onPressed: onRemove, icon: const Icon(Icons.close, size: 16)),
        ]),
        if (capture.targetKind == 'Entity')
          Align(
            alignment: Alignment.centerLeft,
            child: Row(mainAxisSize: MainAxisSize.min, children: [
              Checkbox(
                  value: capture.visibleToLlm,
                  onChanged: (v) { capture.visibleToLlm = v ?? true; onChanged(); }),
              const Text('Share with the assistant', style: TextStyle(fontSize: 12)),
            ]),
          ),
      ]),
    );
  }
}

// ---- branches ----------------------------------------------------------

class _BranchList extends StatelessWidget {
  const _BranchList({required this.branches, required this.flow, required this.onChanged});
  final List<BranchDraft> branches;
  final FlowDraft flow;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      if (branches.isEmpty)
        Text('Continues to the next step.',
            style: Theme.of(context).textTheme.bodySmall
                ?.copyWith(color: Theme.of(context).colorScheme.outline)),
      for (var i = 0; i < branches.length; i++)
        _BranchEditor(
            branch: branches[i], flow: flow, onChanged: onChanged,
            onRemove: () { branches.removeAt(i); onChanged(); }),
      Align(
        alignment: Alignment.centerLeft,
        child: TextButton.icon(
            onPressed: () { branches.add(BranchDraft()); onChanged(); },
            icon: const Icon(Icons.add, size: 16), label: const Text('Add a rule')),
      ),
    ]);
  }
}

class _BranchEditor extends StatelessWidget {
  const _BranchEditor(
      {required this.branch, required this.flow, required this.onChanged, required this.onRemove});
  final BranchDraft branch;
  final FlowDraft flow;
  final VoidCallback onChanged;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final noRight = branch.op == 'EXISTS' || branch.op == 'ABSENT' || branch.op == 'TRUTHY';
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(children: [
          Expanded(
            child: Row(children: [
              const Text('If', style: TextStyle(fontSize: 12)),
              const SizedBox(width: 6),
              Expanded(
                child: Checkbox(
                    value: branch.always,
                    onChanged: (v) { branch.always = v ?? false; onChanged(); }),
              ),
              const Text('always', style: TextStyle(fontSize: 12)),
            ]),
          ),
          IconButton(onPressed: onRemove, icon: const Icon(Icons.close, size: 16)),
        ]),
        if (!branch.always) ...[
          _SourceEditor(source: branch.left, flow: flow, onChanged: onChanged),
          const SizedBox(height: 6),
          Row(children: [
            SizedBox(width: 170, child: _dropdown('Test', branch.op, kOps, (v) { branch.op = v; onChanged(); })),
            const SizedBox(width: 8),
            if (!noRight) Expanded(child: _text('Value', branch.right, (v) => branch.right = v)),
          ]),
        ],
        const SizedBox(height: 8),
        Row(children: [
          SizedBox(width: 190, child: _dropdown('Then', branch.targetKind, kTargetKinds,
              (v) { branch.targetKind = v; onChanged(); })),
          const SizedBox(width: 8),
          Expanded(child: _targetDetail()),
        ]),
      ]),
    );
  }

  Widget _targetDetail() {
    switch (branch.targetKind) {
      case 'Step':
        final keys = flow.stepKeys;
        return keys.isEmpty
            ? _text('Step id', branch.stepKey, (v) => branch.stepKey = v.trim())
            : _dropdown('Step', branch.stepKey.isEmpty ? keys.first : branch.stepKey,
                {for (final k in keys) k: k}, (v) => branch.stepKey = v,
                extraValues: [if (branch.stepKey.isNotEmpty) branch.stepKey]);
      case 'Abort':
        return _text('Reason (audit)', branch.reason, (v) => branch.reason = v);
      case 'End':
      default:
        return _text('Outcome label', branch.outcomeLabel, (v) => branch.outcomeLabel = v.trim(),
            hint: 'success / fallback / …');
    }
  }
}

// ---- Elicit ------------------------------------------------------------

class _ElicitEditor extends StatelessWidget {
  const _ElicitEditor({required this.step, required this.flow, required this.onChanged});
  final ElicitDraft step;
  final FlowDraft flow;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const SizedBox(height: 8),
      _text('Prompt', step.promptTemplate, (v) => step.promptTemplate = v, maxLines: 2,
          hint: 'Use {savedValue} to personalize'),
      const SizedBox(height: 8),
      _text('Quick replies (comma-separated)', step.quickReplies.join(', '),
          (v) => step.quickReplies = _splitCsv(v)),
      const SizedBox(height: 8),
      _text('Widget hint', step.uiHintKind, (v) => step.uiHintKind = v.trim(),
          hint: 'e.g. otp_entry (optional)'),
      _sectionLabel(context, 'Save their answer as'),
      Row(children: [
        Expanded(child: _text('Name', step.captureName, (v) { step.captureName = v.trim(); onChanged(); })),
        const SizedBox(width: 8),
        SizedBox(
          width: 130,
          child: _dropdown('Kind', step.captureKind,
              const {'Entity': 'Shareable', 'StepVar': 'Scratch'},
              (v) { step.captureKind = v; onChanged(); }),
        ),
      ]),
      if (step.captureKind == 'Entity')
        Align(
          alignment: Alignment.centerLeft,
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            Checkbox(
                value: step.visibleToLlm,
                onChanged: (v) { step.visibleToLlm = v ?? true; onChanged(); }),
            const Text('Share with the assistant', style: TextStyle(fontSize: 12)),
          ]),
        ),
      _sectionLabel(context, 'After this step'),
      _BranchList(branches: step.next, flow: flow, onChanged: onChanged),
    ]);
  }
}

// ---- Decision ----------------------------------------------------------

class _DecisionEditor extends StatelessWidget {
  const _DecisionEditor({required this.step, required this.flow, required this.onChanged});
  final DecisionDraft step;
  final FlowDraft flow;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      _sectionLabel(context, 'Rules (first match wins)'),
      _BranchList(branches: step.branches, flow: flow, onChanged: onChanged),
      _sectionLabel(context, 'Otherwise'),
      Row(children: [
        SizedBox(width: 190, child: _dropdown('Default', step.defaultKind, kTargetKinds,
            (v) { step.defaultKind = v; onChanged(); })),
        const SizedBox(width: 8),
        Expanded(child: _defaultDetail()),
      ]),
    ]);
  }

  Widget _defaultDetail() {
    switch (step.defaultKind) {
      case 'Step':
        final keys = flow.stepKeys;
        return keys.isEmpty
            ? _text('Step id', step.defaultStep, (v) => step.defaultStep = v.trim())
            : _dropdown('Step', step.defaultStep.isEmpty ? keys.first : step.defaultStep,
                {for (final k in keys) k: k}, (v) => step.defaultStep = v,
                extraValues: [if (step.defaultStep.isNotEmpty) step.defaultStep]);
      case 'Abort':
        return _text('Reason (audit)', step.defaultReason, (v) => step.defaultReason = v);
      case 'End':
      default:
        return _text('Outcome label', step.defaultOutcome, (v) => step.defaultOutcome = v.trim());
    }
  }
}

// ---- greeting ----------------------------------------------------------

class GreetingEditor extends StatelessWidget {
  const GreetingEditor({super.key, required this.greeting, required this.flow, required this.onChanged});
  final GreetingDraft greeting;
  final FlowDraft flow;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      _dropdown('How should the first message be written?', greeting.mode, kGreetingModes,
          (v) { greeting.mode = v; onChanged(); }),
      const SizedBox(height: 10),
      if (greeting.mode != 'LLM_COMPOSED')
        _text('Success greeting (template)', greeting.successTemplate, (v) => greeting.successTemplate = v,
            maxLines: 2, hint: 'Welcome back, {customerName}!'),
      if (greeting.mode != 'LLM_COMPOSED') const SizedBox(height: 8),
      _text('Fallback greeting', greeting.fallbackGreeting, (v) => greeting.fallbackGreeting = v,
          maxLines: 2),
      const SizedBox(height: 8),
      _text('Quick replies (comma-separated)', greeting.quickReplies.join(', '),
          (v) => greeting.quickReplies = _splitCsv(v)),
      if (greeting.isLlm) ...[
        _sectionLabel(context, 'Assistant-written opening'),
        _text('Instruction to the assistant', greeting.instruction, (v) => greeting.instruction = v,
            maxLines: 2),
        const SizedBox(height: 8),
        Text('Share which saved values to brief the assistant with:',
            style: Theme.of(context).textTheme.bodySmall
                ?.copyWith(color: Theme.of(context).colorScheme.outline)),
        const SizedBox(height: 4),
        _BriefingPicker(greeting: greeting, flow: flow, onChanged: onChanged),
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
      return Text('No shareable saved values yet — add a "Save results as" entity first.',
          style: Theme.of(context).textTheme.bodySmall
              ?.copyWith(color: Theme.of(context).colorScheme.outline));
    }
    return Wrap(spacing: 6, runSpacing: 4, children: [
      for (final e in options)
        FilterChip(
          label: Text(e),
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

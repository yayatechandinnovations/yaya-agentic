import 'package:flutter/material.dart';

/// Minimal inline markdown for assistant prose (owner-ai-experience v2 §5):
/// `**bold**` and `*italic*` only — no tables, no links, no dependency.
/// Unbalanced markers degrade gracefully: a `*` or `**` without a matching
/// closer renders literally, exactly as typed.
///
/// `**` pairs are resolved first (so `**` is never read as two italics), then
/// `*` pairs inside the remaining plain segments — including inside bold runs,
/// so `**bold *both***`-style nesting mostly does the right thing.
TextSpan markdownLite(String text, {TextStyle? style}) =>
    TextSpan(style: style, children: _boldSpans(text));

final RegExp _bold = RegExp(r'\*\*(.+?)\*\*', dotAll: true);
final RegExp _italic = RegExp(r'\*([^*\n]+)\*');

List<InlineSpan> _boldSpans(String text) {
  final spans = <InlineSpan>[];
  var index = 0;
  for (final m in _bold.allMatches(text)) {
    if (m.start > index) {
      spans.addAll(_italicSpans(text.substring(index, m.start)));
    }
    spans.add(TextSpan(
      style: const TextStyle(fontWeight: FontWeight.w700),
      children: _italicSpans(m.group(1)!),
    ));
    index = m.end;
  }
  if (index < text.length) {
    spans.addAll(_italicSpans(text.substring(index)));
  }
  return spans;
}

List<InlineSpan> _italicSpans(String text) {
  final spans = <InlineSpan>[];
  var index = 0;
  for (final m in _italic.allMatches(text)) {
    if (m.start > index) {
      spans.add(TextSpan(text: text.substring(index, m.start)));
    }
    spans.add(TextSpan(
      text: m.group(1),
      style: const TextStyle(fontStyle: FontStyle.italic),
    ));
    index = m.end;
  }
  if (index < text.length) {
    spans.add(TextSpan(text: text.substring(index)));
  }
  return spans;
}

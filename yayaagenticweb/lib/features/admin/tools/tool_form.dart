import '../../../models/admin/tool.dart';

/// The editable shape of a tool, flattened for the form (dirty tracking,
/// drafts, diffs). Everything the form has no control for rides along in
/// [carried] so a republish never drops it.
class ToolDraft {
  ToolDraft({
    this.id = '',
    this.kind = 'HTTP',
    this.beanName = '',
    this.method = 'GET',
    this.urlTemplate = '',
    Map<String, String>? headers,
    this.bodyContentType = 'application/json',
    this.bodyTemplate = '',
    this.jsonPath = r'$',
    this.authForwarding = 'NONE',
    this.inputSchema = '{\n  "type": "object",\n  "properties": {}\n}',
    this.outputSchema = '{\n  "type": "object"\n}',
    this.confirmable = false,
    this.confirmPrompt = '',
    this.clientInvocable = false,
    this.secretInput = false,
    this.plannable = false,
    this.headless = false,
  }) : headers = headers ?? {};

  String id;
  String kind;
  String beanName;
  String method;
  String urlTemplate;
  Map<String, String> headers;
  String bodyContentType;
  String bodyTemplate;
  String jsonPath;
  String authForwarding;
  String inputSchema;
  String outputSchema;
  bool confirmable;
  String confirmPrompt;
  bool clientInvocable;
  bool secretInput;
  bool plannable;
  bool headless;

  static const policyKeys = {'confirmable', 'confirmPrompt', 'clientInvocable', 'secretInput', 'plannable', 'headless'};

  factory ToolDraft.fromTool(ToolResponse t) {
    final http = t.handler.httpSpec;
    return ToolDraft(
      id: t.id,
      kind: t.handler.kind.toUpperCase(),
      beanName: t.handler.beanName ?? '',
      method: http?.method ?? 'GET',
      urlTemplate: http?.urlTemplate ?? '',
      headers: Map.of(http?.headerTemplates ?? const {}),
      bodyContentType: http?.body?.contentType ?? 'application/json',
      bodyTemplate: http?.body?.template ?? '',
      jsonPath: http?.response?.jsonPath ?? r'$',
      authForwarding: http?.authForwarding ?? 'NONE',
      inputSchema: t.inputSchemaJson,
      outputSchema: t.outputSchemaJson,
      confirmable: t.policy['confirmable'] == true,
      confirmPrompt: (t.policy['confirmPrompt'] as String?) ?? '',
      clientInvocable: t.policy['clientInvocable'] == true,
      secretInput: t.policy['secretInput'] == true,
      plannable: t.policy['plannable'] == true,
      headless: t.policy['headless'] == true,
    );
  }

  Map<String, Object?> toSnapshot() => {
        'id': id,
        'kind': kind,
        if (kind == 'BEAN') 'beanName': beanName,
        if (kind == 'HTTP') ...{
          'method': method,
          'urlTemplate': urlTemplate,
          'headers': headers,
          'bodyContentType': bodyContentType,
          'bodyTemplate': bodyTemplate,
          'jsonPath': jsonPath,
          'authForwarding': authForwarding,
        },
        'inputSchema': inputSchema,
        'outputSchema': outputSchema,
        'confirmable': confirmable,
        'confirmPrompt': confirmable ? confirmPrompt : '',
        'clientInvocable': clientInvocable,
        'secretInput': secretInput,
        'plannable': plannable,
        'headless': headless,
      };

  void applySnapshot(Map<String, Object?> s) {
    id = (s['id'] as String?) ?? id;
    kind = (s['kind'] as String?) ?? kind;
    beanName = (s['beanName'] as String?) ?? beanName;
    method = (s['method'] as String?) ?? method;
    urlTemplate = (s['urlTemplate'] as String?) ?? urlTemplate;
    final h = s['headers'];
    if (h is Map) headers = h.map((k, v) => MapEntry('$k', '$v'));
    bodyContentType = (s['bodyContentType'] as String?) ?? bodyContentType;
    bodyTemplate = (s['bodyTemplate'] as String?) ?? bodyTemplate;
    jsonPath = (s['jsonPath'] as String?) ?? jsonPath;
    authForwarding = (s['authForwarding'] as String?) ?? authForwarding;
    inputSchema = (s['inputSchema'] as String?) ?? inputSchema;
    outputSchema = (s['outputSchema'] as String?) ?? outputSchema;
    confirmable = (s['confirmable'] as bool?) ?? confirmable;
    confirmPrompt = (s['confirmPrompt'] as String?) ?? confirmPrompt;
    clientInvocable = (s['clientInvocable'] as bool?) ?? clientInvocable;
    secretInput = (s['secretInput'] as bool?) ?? secretInput;
    plannable = (s['plannable'] as bool?) ?? plannable;
    headless = (s['headless'] as bool?) ?? headless;
  }

  /// Why `headless` can't be on right now, or null — mirrors CatalogMapper.headlessProblem.
  String? get headlessBlocker {
    if (kind != 'HTTP' || method != 'GET') return 'Only for HTTP GET tools — a headless run answers with nobody present.';
    final waits = [
      if (confirmable) 'Ask before running',
      if (secretInput) 'Collects a secret code',
      if (clientInvocable) 'Runnable from the chat UI',
      if (plannable) 'Group with other confirmations',
    ];
    if (waits.isNotEmpty) return 'Turn off ${waits.join(', ')} first — a headless tool can’t wait for a person.';
    return null;
  }

  String? get plannableBlocker {
    if (!confirmable) return 'Turn on Ask before running first.';
    if (secretInput) return 'Not available on a tool that collects a secret code.';
    return null;
  }

  ToolRequest toRequest({
    required String tenant,
    ToolResponse? source,
    Map<String, dynamic> carriedPolicy = const {},
  }) {
    final srcHttp = source?.handler.httpSpec;
    final handler = kind == 'BEAN'
        ? ToolHandlerDto(kind: 'BEAN', beanName: beanName.trim())
        : ToolHandlerDto(
            kind: 'HTTP',
            httpSpec: HttpHandlerDto(
              method: method,
              urlTemplate: urlTemplate.trim(),
              headerTemplates: headers,
              body: bodyTemplate.trim().isEmpty ? null : HttpBodyDto(contentType: bodyContentType.trim(), template: bodyTemplate),
              response: HttpResponseDto(
                jsonPath: jsonPath.trim().isEmpty ? r'$' : jsonPath.trim(),
                headerOutputs: srcHttp?.response?.headerOutputs ?? const {},
                mediaOutputs: srcHttp?.response?.mediaOutputs,
                tableOutputs: srcHttp?.response?.tableOutputs,
                cardFields: srcHttp?.response?.cardFields,
              ),
              authForwarding: authForwarding,
            ),
          );
    final policy = <String, dynamic>{
      ...carriedPolicy,
      'confirmable': confirmable,
      'clientInvocable': clientInvocable,
      'secretInput': secretInput,
      'plannable': plannable && plannableBlocker == null,
      'headless': headless && headlessBlocker == null,
    };
    if (confirmable && confirmPrompt.trim().isNotEmpty) {
      policy['confirmPrompt'] = confirmPrompt.trim();
    } else {
      policy.remove('confirmPrompt');
    }
    return ToolRequest(
      tenant: tenant,
      id: id.trim(),
      inputSchemaJson: inputSchema,
      outputSchemaJson: outputSchema,
      requires: source?.requires ?? const {},
      handler: handler,
      policy: policy,
      captures: source?.captures ?? const [],
      bindings: source?.bindings ?? const [],
      suggestions: source?.suggestions ?? const [],
    );
  }
}

/// Field labels shared by the editor, the error summary and diffs.
const toolFieldLabels = {
  'id': 'Tool ID',
  'kind': 'How it runs',
  'beanName': 'Handler bean',
  'method': 'Method',
  'urlTemplate': 'Request path',
  'headers': 'Headers',
  'bodyContentType': 'Body content type',
  'bodyTemplate': 'Body template',
  'jsonPath': 'Response JSON path',
  'authForwarding': 'Credentials sent',
  'inputSchema': 'Input schema',
  'outputSchema': 'Output schema',
  'confirmable': 'Ask before running',
  'confirmPrompt': 'Confirmation prompt',
  'clientInvocable': 'Runnable from the chat UI',
  'secretInput': 'Collects a secret code',
  'plannable': 'Group with other confirmations',
  'headless': 'Headless runs',
};

/// Latest version per tool id, plus how many versions exist.
class ToolSummary {
  ToolSummary(this.latest, this.versions);
  final ToolResponse latest;
  final List<ToolResponse> versions;
}

List<ToolSummary> summarizeTools(List<ToolResponse> all) {
  final byId = <String, List<ToolResponse>>{};
  for (final t in all) {
    byId.putIfAbsent(t.id, () => []).add(t);
  }
  return [
    for (final e in byId.entries)
      ToolSummary((e.value..sort((a, b) => b.version.compareTo(a.version))).first, e.value),
  ]..sort((a, b) => a.latest.id.compareTo(b.latest.id));
}

bool isAbsoluteUrl(String s) => s.startsWith('//') || s.indexOf('://') > 0;

String? originOf(String url) {
  if (url.startsWith('//')) return null;
  final schemeEnd = url.indexOf('://');
  if (schemeEnd <= 0) return null;
  final pathStart = url.indexOf('/', schemeEnd + 3);
  return pathStart < 0 ? url.toLowerCase() : url.substring(0, pathStart).toLowerCase();
}

String extractPath(String url) {
  final origin = originOf(url);
  if (origin == null) return url;
  final tail = url.substring(origin.length);
  return tail.isEmpty || !tail.startsWith('/') ? '/$tail' : tail;
}

import '../../../models/admin/profile.dart';

/// The store binding rides under the yaya-sales-owned `sales.store` kind in a
/// profile's domain-neutral `extensions` map (S7). The framework stores it
/// opaquely; this console is the app-side editor for that payload.
const profileStoreKind = 'sales.store';

/// Tool rounds per turn — mirrors backend `Profile.MIN/MAX/DEFAULT_TOOL_ROUNDS`.
const minToolRounds = 1;
const maxToolRounds = 16;
const defaultToolRounds = 5;

/// Pulls the `sales.store` payload out of a profile's extensions map, or null.
Map<String, dynamic>? storeExtensionOf(Map<String, dynamic>? extensions) {
  final store = extensions?[profileStoreKind];
  return store is Map ? Map<String, dynamic>.from(store) : null;
}

/// The editable shape of a profile, flattened for the form (dirty tracking,
/// drafts, diffs).
///
/// Each POST replaces the whole profile, so a field left out of the body is a
/// field cleared. Everything the form has no control for — metadata (which
/// carries `principalKinds` and `policy`), extensions other than the store
/// binding, store-payload keys the store controls don't cover, and response
/// fields this console doesn't model — is saved unchanged from the source
/// profile passed to [toRequest].
class ProfileDraft {
  ProfileDraft({
    this.id = '',
    this.displayName = '',
    this.introOneLiner = '',
    this.systemPrompt = '',
    this.language = 'en',
    List<String>? capabilities,
    this.authBindingId,
    this.defaultStartFlowId,
    this.storeEnabled = false,
    this.storeId = '',
    this.checkoutTool = 'checkout',
    this.cartEnabled = true,
    this.model = '',
    this.maxToolRounds = '',
    this.metered = false,
  }) : capabilities = capabilities ?? [];

  String id;
  String displayName;
  String introOneLiner;
  String systemPrompt;
  String language;
  List<String> capabilities;
  String? authBindingId;
  String? defaultStartFlowId;
  bool storeEnabled;
  String storeId;
  String checkoutTool;
  bool cartEnabled;
  String model;

  /// As typed; blank = the server default.
  String maxToolRounds;
  bool metered;

  factory ProfileDraft.fromProfile(ProfileResponse p) {
    final store = storeExtensionOf(p.extensions);
    // Tolerates the legacy {value} wrapper on checkoutTool.
    final tool = store?['checkoutTool'];
    final toolId = tool is Map ? tool['value']?.toString() : tool?.toString();
    return ProfileDraft(
      id: p.id,
      displayName: p.displayName,
      introOneLiner: p.introOneLiner,
      systemPrompt: p.systemPromptFragment,
      language: p.language,
      capabilities: [...p.capabilities],
      authBindingId: p.authBindingId == null || p.authBindingId!.isEmpty ? null : p.authBindingId,
      defaultStartFlowId: p.defaultStartFlowId == null || p.defaultStartFlowId!.isEmpty ? null : p.defaultStartFlowId,
      storeEnabled: store != null,
      storeId: store?['storeId']?.toString() ?? '',
      checkoutTool: toolId == null || toolId.isEmpty ? 'checkout' : toolId,
      cartEnabled: store == null || store['cartEnabled'] != false,
      model: p.model ?? '',
      maxToolRounds: p.maxToolRounds?.toString() ?? '',
      metered: p.metered,
    );
  }

  Map<String, Object?> toSnapshot() => {
        'id': id,
        'displayName': displayName,
        'introOneLiner': introOneLiner,
        'language': language,
        'systemPrompt': systemPrompt,
        'capabilities': capabilities,
        'authBindingId': authBindingId,
        'defaultStartFlowId': defaultStartFlowId,
        'storeEnabled': storeEnabled,
        if (storeEnabled) ...{
          'storeId': storeId,
          'checkoutTool': checkoutTool,
          'cartEnabled': cartEnabled,
        },
        'model': model,
        'maxToolRounds': maxToolRounds,
        'metered': metered,
      };

  void applySnapshot(Map<String, Object?> s) {
    id = (s['id'] as String?) ?? id;
    displayName = (s['displayName'] as String?) ?? displayName;
    introOneLiner = (s['introOneLiner'] as String?) ?? introOneLiner;
    language = (s['language'] as String?) ?? language;
    systemPrompt = (s['systemPrompt'] as String?) ?? systemPrompt;
    final caps = s['capabilities'];
    if (caps is List) capabilities = [for (final c in caps) '$c'];
    if (s.containsKey('authBindingId')) authBindingId = s['authBindingId'] as String?;
    if (s.containsKey('defaultStartFlowId')) defaultStartFlowId = s['defaultStartFlowId'] as String?;
    storeEnabled = (s['storeEnabled'] as bool?) ?? storeEnabled;
    storeId = (s['storeId'] as String?) ?? storeId;
    checkoutTool = (s['checkoutTool'] as String?) ?? checkoutTool;
    cartEnabled = (s['cartEnabled'] as bool?) ?? cartEnabled;
    model = (s['model'] as String?) ?? model;
    maxToolRounds = (s['maxToolRounds'] as String?) ?? maxToolRounds;
    metered = (s['metered'] as bool?) ?? metered;
  }

  /// The profile's `extensions` map, or null when it ends up empty. Only the
  /// `sales.store` kind is edited (set from the store controls, or removed
  /// when the store is off); every other kind of [source], and any store key
  /// the controls don't cover, is kept as it was.
  Map<String, dynamic>? buildExtensions(Map<String, dynamic>? source) {
    final extensions = <String, dynamic>{...?source}..remove(profileStoreKind);
    if (storeEnabled) {
      final sid = storeId.trim();
      final tool = checkoutTool.trim();
      extensions[profileStoreKind] = {
        ...?storeExtensionOf(source),
        'storeId': sid.isEmpty ? null : sid,
        'checkoutTool': tool.isEmpty ? 'checkout' : tool,
        'cartEnabled': cartEnabled,
      };
    }
    return extensions.isEmpty ? null : extensions;
  }

  /// [source] is the profile this save is a new version (or a duplicate) of;
  /// null for a brand-new profile, which then carries nothing over.
  ProfileRequest toRequest({required String tenant, ProfileResponse? source}) {
    final rounds = int.tryParse(maxToolRounds.trim());
    return ProfileRequest(
      tenant: tenant,
      id: id.trim(),
      displayName: displayName.trim(),
      introOneLiner: introOneLiner.trim(),
      systemPromptFragment: systemPrompt.trim(),
      capabilities: [...capabilities],
      authBindingId: authBindingId == null || authBindingId!.isEmpty ? null : authBindingId,
      language: language,
      metadata: source?.metadata ?? const <String, dynamic>{},
      defaultStartFlowId: defaultStartFlowId == null || defaultStartFlowId!.isEmpty ? null : defaultStartFlowId,
      extensions: buildExtensions(source?.extensions),
      // The server keeps a runtime field of the latest version when a save
      // leaves it out, so what the form shows is always sent: an emptied
      // model is "" (the deployment's model), and emptied rounds on an
      // existing profile are the default itself.
      model: model.trim(),
      maxToolRounds: rounds ?? (source == null ? null : defaultToolRounds),
      metered: metered,
      carried: source?.raw ?? const <String, dynamic>{},
    );
  }
}

/// Field labels shared by the editor, the error summary and diffs.
const profileFieldLabels = {
  'id': 'Profile ID',
  'displayName': 'Display name',
  'introOneLiner': 'Intro line',
  'language': 'Response language',
  'systemPrompt': 'Instructions',
  'capabilities': 'Capabilities',
  'authBindingId': 'Sign-in binding',
  'defaultStartFlowId': 'Default start flow',
  'storeEnabled': 'Sell from a store',
  'storeId': 'Store ID',
  'checkoutTool': 'Checkout tool',
  'cartEnabled': 'Cart',
  'model': 'Model',
  'maxToolRounds': 'Max tool rounds per turn',
  'metered': 'Metered',
};

/// Latest version per profile id, plus every version (newest first).
class ProfileSummary {
  ProfileSummary(this.latest, this.versions);
  final ProfileResponse latest;
  final List<ProfileResponse> versions;
}

List<ProfileSummary> summarizeProfiles(List<ProfileResponse> all) {
  final byId = <String, List<ProfileResponse>>{};
  for (final p in all) {
    byId.putIfAbsent(p.id, () => []).add(p);
  }
  return [
    for (final e in byId.entries)
      ProfileSummary((e.value..sort((a, b) => b.version.compareTo(a.version))).first, e.value),
  ]..sort((a, b) => a.latest.displayName.toLowerCase().compareTo(b.latest.displayName.toLowerCase()));
}

/// Languages offered in the picker. The backend accepts any BCP 47 tag; a
/// profile saved with another tag keeps it.
const profileLanguages = {'en': 'English', 'es': 'Spanish'};

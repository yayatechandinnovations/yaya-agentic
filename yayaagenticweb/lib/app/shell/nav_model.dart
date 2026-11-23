import 'package:flutter/material.dart';

/// Common surface for both leaf nav items and expandable groups.
sealed class NavEntry {
  const NavEntry(this.label, this.icon, this.selectedIcon, {this.platformOnly = false});
  final String label;
  final IconData icon;
  final IconData selectedIcon;

  /// Platform-tier-only entries are filtered out for non-platform-admin users
  /// (the route redirect also bounces direct URL hits).
  final bool platformOnly;
}

class NavItem extends NavEntry {
  const NavItem(super.label, super.icon, super.selectedIcon, this.location, {super.platformOnly, this.keywords = const []});
  final String location;

  /// Extra words the command palette matches ("jwt" → JWT secrets, "hs256").
  final List<String> keywords;
}

/// A collapsible group. Groups themselves are never platform-only — the
/// flag lives on the children, and a group left empty disappears.
class NavGroup extends NavEntry {
  const NavGroup(super.label, super.icon, super.selectedIcon, {required this.children});
  final List<NavItem> children;
}

// Related destinations are grouped so the rail stays scannable. "Inbound
// channels" used to hold nine unrelated items; it is split into the
// channels a message arrives on and the messaging tools around them.
const navEntries = <NavEntry>[
  NavGroup('Playground', Icons.science_outlined, Icons.science, children: [
    NavItem('Chat', Icons.chat_outlined, Icons.chat, '/playground', keywords: ['test', 'try']),
    NavItem('Chat channel', Icons.storefront_outlined, Icons.storefront, '/playground/chat'),
    NavItem('WhatsApp', Icons.phone_iphone_outlined, Icons.phone_iphone, '/playground/whatsapp'),
  ]),
  // Bot composition primitives (design §5.2–§5.3, §5.8–§5.9).
  NavGroup('Agent design', Icons.smart_toy_outlined, Icons.smart_toy, children: [
    NavItem('Profiles', Icons.person_outline, Icons.person, '/admin/profiles', keywords: ['role', 'prompt']),
    NavItem('Start flows', Icons.play_circle_outline, Icons.play_circle, '/admin/start-flows', keywords: ['greeting']),
    NavItem('Capabilities', Icons.checklist_outlined, Icons.checklist, '/admin/capabilities'),
    NavItem('Tools', Icons.build_outlined, Icons.build, '/admin/tools', keywords: ['http', 'bean']),
    NavItem('Knowledge', Icons.menu_book_outlined, Icons.menu_book, '/admin/knowledge-sources', keywords: ['rag', 'documents', 'sources']),
    NavItem('Recording', Icons.fiber_manual_record_outlined, Icons.fiber_manual_record, '/admin/recording-strategies'),
  ]),
  // Who the runtime trusts and how verified claims route to a profile (§5.4, §5.6).
  NavGroup('Authentication', Icons.shield_outlined, Icons.shield, children: [
    NavItem('Auth bindings', Icons.lock_outlined, Icons.lock, '/admin/auth-bindings'),
    NavItem('OIDC issuers', Icons.vpn_key_outlined, Icons.vpn_key, '/admin/oidc-issuers', keywords: ['jwks', 'rs256']),
    NavItem('JWT secrets', Icons.key_outlined, Icons.key, '/admin/jwt-secrets', keywords: ['hs256', 'hmac']),
    NavItem('Auth resolvers', Icons.fingerprint_outlined, Icons.fingerprint, '/admin/auth-resolvers'),
    NavItem('Identity routing', Icons.alt_route_outlined, Icons.alt_route, '/admin/identity-routing', keywords: ['claims']),
  ]),
  NavGroup('Channels', Icons.inbox_outlined, Icons.inbox, children: [
    NavItem('Network chat', Icons.hub_outlined, Icons.hub, '/admin/network'),
    NavItem('WhatsApp senders', Icons.chat_outlined, Icons.chat, '/admin/whatsapp-senders', keywords: ['number', 'twilio']),
    NavItem('SMS (A2P 10DLC)', Icons.sms_outlined, Icons.sms, '/admin/sms'),
    NavItem('System inbound', Icons.mail_outline, Icons.mail, '/admin/system-senders', keywords: ['outreach', 'integration']),
  ]),
  NavGroup('Messaging', Icons.forum_outlined, Icons.forum, children: [
    NavItem('Chats', Icons.chat_bubble_outline, Icons.chat_bubble, '/admin/chats', keywords: ['conversations', 'sessions']),
    NavItem('Forum', Icons.forum_outlined, Icons.forum, '/admin/forum'),
    NavItem('WhatsApp templates', Icons.article_outlined, Icons.article, '/admin/whatsapp-templates'),
    NavItem('Receipt templates', Icons.receipt_long_outlined, Icons.receipt_long, '/admin/receipt-templates'),
    NavItem('WhatsApp opt-outs', Icons.block_outlined, Icons.block, '/admin/whatsapp-opt-outs', keywords: ['stop', 'unsubscribe']),
  ]),
  NavGroup('Workspace', Icons.workspaces_outline, Icons.workspaces, children: [
    NavItem('Workspaces', Icons.grid_view_outlined, Icons.grid_view, '/workspaces', keywords: ['members']),
    NavItem('API keys', Icons.vpn_key_outlined, Icons.vpn_key, '/settings/api-keys'),
    NavItem('Audit', Icons.policy_outlined, Icons.policy, '/admin/audit', keywords: ['authz', 'log']),
  ]),
  NavGroup('Platform', Icons.admin_panel_settings_outlined, Icons.admin_panel_settings, children: [
    NavItem('Tenants', Icons.apartment_outlined, Icons.apartment, '/admin/tenants', platformOnly: true),
    NavItem('Operator auth', Icons.security_outlined, Icons.security, '/admin/operator-auth', platformOnly: true),
    NavItem('Shared WhatsApp templates', Icons.share_outlined, Icons.share, '/admin/whatsapp-shared-templates', platformOnly: true),
  ]),
  NavItem('Settings', Icons.tune_outlined, Icons.tune, '/settings', keywords: ['theme', 'dark', 'backend']),
];

/// Routes that bounce to `/playground` for a non-platform-admin.
const platformOnlyRoutes = <String>{
  '/admin/tenants',
  '/admin/operator-auth',
  '/admin/whatsapp-shared-templates',
};

List<NavEntry> visibleNavEntries({required bool platformAdmin}) {
  if (platformAdmin) return navEntries;
  return navEntries
      .where((e) => !e.platformOnly)
      .map<NavEntry>((e) => e is NavGroup
          ? NavGroup(e.label, e.icon, e.selectedIcon, children: e.children.where((c) => !c.platformOnly).toList())
          : e)
      .where((e) => e is! NavGroup || e.children.isNotEmpty)
      .toList();
}

Iterable<({NavItem item, NavGroup? group})> flattenNav(List<NavEntry> entries) sync* {
  for (final e in entries) {
    if (e is NavItem) yield (item: e, group: null);
    if (e is NavGroup) {
      for (final c in e.children) {
        yield (item: c, group: e);
      }
    }
  }
}

/// Longest nav location that prefixes [path] — so `/playground/chat` wins over `/playground`.
({NavItem item, NavGroup? group})? navMatch(List<NavEntry> entries, String path) {
  ({NavItem item, NavGroup? group})? best;
  for (final m in flattenNav(entries)) {
    final loc = m.item.location;
    if (path == loc || path.startsWith('$loc/')) {
      if (best == null || loc.length > best.item.location.length) best = m;
    }
  }
  return best;
}

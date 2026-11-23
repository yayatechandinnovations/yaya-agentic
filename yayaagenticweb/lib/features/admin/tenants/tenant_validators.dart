/// Client-side mirror of the backend's `TenantSlug` and `BaseUrlValidator`
/// rules, so a tenant form never sends something the server would refuse.
abstract final class TenantValidators {
  static final _slug = RegExp(r'^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$');
  static final _control = RegExp(r'[\x00-\x1f\x7f]');

  static String? tenantId(String? v) {
    final s = v?.trim() ?? '';
    if (s.isEmpty) return 'Enter a tenant ID.';
    if (!_slug.hasMatch(s)) {
      return 'Use 3–64 lowercase letters, digits or dashes, starting and ending with a letter or digit.';
    }
    return null;
  }

  static String? hostBaseUrl(String? v, {required bool requireHttps}) {
    final s = v?.trim() ?? '';
    if (s.isEmpty) return 'Enter the host base URL. HTTP tools can’t run without it.';
    if (_control.hasMatch(s)) return 'Remove control characters (tabs, line breaks).';
    if (s.contains('?') || s.contains('#')) return 'Leave out the query string and fragment (no ? or #).';
    final u = Uri.tryParse(s);
    if (u == null) return 'This isn’t a valid URL.';
    if (!(u.isScheme('http') || u.isScheme('https'))) return 'Start with https:// (or http://).';
    if (u.host.isEmpty) return 'Include a host, e.g. https://api.acme.com';
    if (requireHttps && !u.isScheme('https')) return 'Must start with https:// while “Require HTTPS” is on.';
    return null;
  }

  /// One line of the host base URL allowlist: `scheme://host[:port][/path]`, `*` allowed.
  static String? urlPattern(String p, {required bool requireHttps}) {
    if (_control.hasMatch(p)) return 'contains control characters';
    if (p.contains('?') || p.contains('#')) return 'must not contain ? or #';
    if (!(p.startsWith('http://') || p.startsWith('https://'))) return 'must start with https:// or http://';
    if (requireHttps && !p.startsWith('https://')) return 'must be https:// while “Require HTTPS” is on';
    final rest = p.substring(p.indexOf('//') + 2);
    if (rest.trim().isEmpty || rest.startsWith('/')) return 'must include a host';
    return null;
  }

  /// One line of the inbound origin allowlist: `scheme://host[:port]` only.
  static String? originPattern(String p, {required bool requireHttps}) {
    if (_control.hasMatch(p)) return 'contains control characters';
    if (!(p.startsWith('http://') || p.startsWith('https://'))) return 'must start with https:// or http://';
    if (requireHttps && !p.startsWith('https://')) return 'must be https:// while “Require HTTPS” is on';
    final host = p.substring(p.indexOf('//') + 2);
    if (host.trim().isEmpty) return 'must include a host';
    if (host.contains('/') || host.contains('?') || host.contains('#')) {
      return 'must be scheme://host[:port] only — no path, query or fragment';
    }
    return null;
  }

  /// Validates every non-blank line; reports the first bad one by line number.
  static String? lines(String raw, String? Function(String line) check) {
    final lines = raw.split('\n');
    for (var i = 0; i < lines.length; i++) {
      final l = lines[i].trim();
      if (l.isEmpty) continue;
      final err = check(l);
      if (err != null) return 'Line ${i + 1} ($l) $err.';
    }
    return null;
  }

  static List<String> splitLines(String raw) =>
      raw.split('\n').map((s) => s.trim()).where((s) => s.isNotEmpty).toList();
}

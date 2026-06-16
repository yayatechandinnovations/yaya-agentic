package com.yayatechandinnovations.yayaagentic.tool.dispatch;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pre-flight gate for every outbound HTTP tool call. Two layers:
 *
 * <ol>
 *   <li>Platform allowlist (OPTIONAL kill-switch): when configured, the
 *       target host must match at least one pattern (exact, glob with
 *       {@code *}, or {@code "host:port"}). When the list is empty —
 *       which is the default for multi-tenant SaaS deployments — this
 *       layer is bypassed: per-tenant authorization is supplied
 *       structurally by {@code BaseUrlResolver}, which already constrains
 *       the dispatched host to {@code tenant.hostBaseUrl} or an entry
 *       in {@code tenant.hostBaseUrlAllowlist}. Operators who want a
 *       deployment-wide "this cluster can only ever call …" gate can
 *       still set the list and it will enforce.</li>
 *   <li>SSRF guard (always on): the resolved IP must not be loopback,
 *       link-local, multicast, any-local, or RFC1918 private (unless
 *       {@code allow-private-networks=true}). This is a platform
 *       concern and runs regardless of allowlist configuration.</li>
 * </ol>
 *
 * Both decisions surface as a {@link Decision} that the dispatcher logs
 * and turns into a FAILED tool result.
 */
@Component
public class HttpEgressPolicy {

    public sealed interface Decision permits Decision.Allow, Decision.Deny {
        record Allow(InetAddress resolved) implements Decision {}
        record Deny(String reason) implements Decision {}
    }

    private final List<Pattern> allowed;
    private final boolean allowPrivate;

    public HttpEgressPolicy(YayaAgenticProperties props) {
        var http = props.httpTools();
        this.allowed = (http == null || http.egressAllowlist() == null
                ? List.<String>of() : http.egressAllowlist())
                .stream()
                .filter(s -> s != null && !s.isBlank())
                .map(HttpEgressPolicy::compile)
                .toList();
        this.allowPrivate = http != null && http.allowPrivateNetworks();
    }

    public Decision check(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return new Decision.Deny("egress: missing host");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);

        // Platform allowlist is an OPTIONAL kill-switch. When non-empty it
        // gates; when empty we skip to SSRF only and let per-tenant config
        // (BaseUrlResolver) carry the authorization. See class javadoc.
        if (!allowed.isEmpty()) {
            int port = uri.getPort() == -1 ? defaultPort(uri.getScheme()) : uri.getPort();
            String hostPort = host + ":" + port;
            if (!matchesAllowlist(host, hostPort)) {
                return new Decision.Deny("egress: host '" + host
                        + "' not in platform egress-allowlist"
                        + " (yaya.agentic.http-tools.egress-allowlist)");
            }
        }

        return checkPrivateAddressOnly(uri);
    }

    /**
     * SSRF check without the allowlist gate. Use this for outbound URLs
     * that are operator-configured (not LLM-influenced) — the delegate
     * login URL being the canonical case. Still blocks loopback,
     * link-local, private (RFC1918), multicast, and any-local addresses
     * unless {@code allow-private-networks=true}.
     */
    public Decision checkPrivateAddressOnly(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return new Decision.Deny("egress: missing host");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return new Decision.Deny("egress: unknown host '" + host + "'");
        }
        for (InetAddress addr : addrs) {
            String why = forbid(addr);
            if (why != null && !allowPrivate) {
                return new Decision.Deny("egress: " + why + " (" + addr.getHostAddress() + ")");
            }
        }
        return new Decision.Allow(addrs[0]);
    }

    private boolean matchesAllowlist(String host, String hostPort) {
        for (Pattern p : allowed) {
            if (p.matcher(host).matches()) return true;
            if (p.matcher(hostPort).matches()) return true;
        }
        return false;
    }

    private static String forbid(InetAddress addr) {
        if (addr.isLoopbackAddress()) return "loopback address forbidden";
        if (addr.isLinkLocalAddress()) return "link-local address forbidden";
        if (addr.isSiteLocalAddress()) return "private (RFC1918) address forbidden";
        if (addr.isMulticastAddress()) return "multicast address forbidden";
        if (addr.isAnyLocalAddress()) return "any-local address forbidden";
        return null;
    }

    private static int defaultPort(String scheme) {
        if (scheme == null) return -1;
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
    }

    private static Pattern compile(String pattern) {
        String escaped = pattern.toLowerCase(Locale.ROOT)
                .replace(".", "\\.")
                .replace("*", ".*");
        return Pattern.compile("^" + escaped + "$");
    }
}

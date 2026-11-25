package com.pawfect;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Blocks requests a normal local browser session would never make: a wrong Host header (DNS
 * rebinding), a foreign Origin, or a cross-site mutating request. Requests with no Origin, such
 * as curl or scripts on this machine, are allowed.
 */
final class RequestGuard extends Filter {
    private final Set<String> hosts;
    private final Set<String> origins;

    RequestGuard(int port) {
        this.hosts = Set.of("localhost:" + port, "127.0.0.1:" + port);
        this.origins = Set.of("http://localhost:" + port, "http://127.0.0.1:" + port);
    }

    @Override
    public void doFilter(HttpExchange x, Chain chain) throws IOException {
        String blocked = check(x);
        if (blocked != null) {
            Http.sendJson(x, 403, Map.of("error", blocked, "code", "REQUEST_BLOCKED"));
            return;
        }
        chain.doFilter(x);
    }

    @Override
    public String description() {
        return "Rejects non-local Host and Origin headers";
    }

    private String check(HttpExchange x) {
        String host = x.getRequestHeaders().getFirst("Host");
        if (host == null || !hosts.contains(host.toLowerCase(Locale.ROOT))) return "Request blocked: unexpected Host header.";

        String origin = x.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origins.contains(origin.toLowerCase(Locale.ROOT))) return "Request blocked: unexpected Origin.";

        String method = x.getRequestMethod();
        boolean mutating = !"GET".equals(method) && !"HEAD".equals(method);
        String site = x.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if (mutating && site != null && !site.equals("same-origin") && !site.equals("none")) return "Request blocked: cross-site request.";
        return null;
    }
}

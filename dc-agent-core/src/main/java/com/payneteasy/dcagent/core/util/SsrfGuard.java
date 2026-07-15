package com.payneteasy.dcagent.core.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF protection for outbound URLs (used by the fetch-url proxy). Only http/https is allowed, and the
 * target host must not resolve to a loopback, wildcard, link-local (incl. cloud metadata 169.254.169.254),
 * private, multicast, or IPv6 unique-local address.
 *
 * <p>Note: this resolves DNS and validates the addresses; the HTTP client re-resolves at connect time, so
 * it does not defend against DNS-rebinding TOCTOU — acceptable for an api-key-authenticated endpoint.
 */
public final class SsrfGuard {

    private SsrfGuard() {
    }

    /** Validate an outbound URL; returns the parsed {@link URI} or throws {@link SsrfBlockedException}. */
    public static URI validate(String aUrl) {
        URI uri;
        try {
            uri = URI.create(aUrl);
        } catch (IllegalArgumentException e) {
            throw new SsrfBlockedException("Malformed URL");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new SsrfBlockedException("Only http/https URLs are allowed");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfBlockedException("URL has no host");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SsrfBlockedException("Cannot resolve host: " + host);
        }

        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new SsrfBlockedException("Target address is not allowed for host: " + host);
            }
        }

        return uri;
    }

    private static boolean isBlocked(InetAddress aAddress) {
        return aAddress.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || aAddress.isAnyLocalAddress()   // 0.0.0.0, ::
                || aAddress.isLinkLocalAddress()  // 169.254.0.0/16 (incl. cloud metadata), fe80::/10
                || aAddress.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || aAddress.isMulticastAddress()  // 224.0.0.0/4, ff00::/8
                || isUniqueLocalIpv6(aAddress);   // fc00::/7
    }

    private static boolean isUniqueLocalIpv6(InetAddress aAddress) {
        byte[] bytes = aAddress.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}

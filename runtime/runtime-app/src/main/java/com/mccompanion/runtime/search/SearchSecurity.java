package com.mccompanion.runtime.search;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class SearchSecurity {
    @FunctionalInterface
    interface Resolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    record ResolvedTarget(URI uri, String host, List<InetAddress> addresses) {
        ResolvedTarget {
            addresses = List.copyOf(addresses);
            if (addresses.isEmpty()) throw new IllegalArgumentException("SEARCH_HOST_UNRESOLVED");
        }
    }

    private static final Resolver SYSTEM_RESOLVER =
            host -> List.copyOf(Arrays.asList(InetAddress.getAllByName(host)));

    private SearchSecurity() { }

    static String normalizedDomain(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("domain is blank");
        String domain = IDN.toASCII(value.strip().toLowerCase(Locale.ROOT));
        if (!domain.matches("[a-z0-9.-]{1,253}") || domain.startsWith(".") || domain.endsWith(".")) {
            throw new IllegalArgumentException("domain is invalid");
        }
        return domain;
    }

    static URI requirePublicHttps(String value, List<String> allowedDomains) {
        return resolvePublicHttps(value, allowedDomains, SYSTEM_RESOLVER).uri();
    }

    static ResolvedTarget resolvePublicHttps(String value, List<String> allowedDomains) {
        return resolvePublicHttps(value, allowedDomains, SYSTEM_RESOLVER);
    }

    static ResolvedTarget resolvePublicHttps(String value, List<String> allowedDomains, Resolver resolver) {
        URI uri = parseHttpUri(value, true);
        String host = normalizedDomain(uri.getHost());
        if (!allowedDomains.isEmpty() && allowedDomains.stream().noneMatch(domain ->
                host.equals(domain) || host.endsWith("." + domain))) {
            throw new IllegalArgumentException("SEARCH_DOMAIN_DENIED");
        }
        List<InetAddress> addresses = resolve(host, resolver);
        if (addresses.stream().anyMatch(address -> !isPublic(address))) {
            throw new IllegalArgumentException("SEARCH_PRIVATE_ADDRESS_DENIED");
        }
        return new ResolvedTarget(uri, host, addresses);
    }

    static ResolvedTarget resolveProvider(String value) {
        return resolveProvider(value, SYSTEM_RESOLVER);
    }

    static ResolvedTarget resolveProvider(String value, Resolver resolver) {
        URI uri = parseHttpUri(value, false);
        String host = normalizedDomain(uri.getHost());
        List<InetAddress> addresses = resolve(host, resolver);
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            if (addresses.stream().anyMatch(address -> !address.isLoopbackAddress())) {
                throw new IllegalArgumentException("non-loopback search endpoint requires HTTPS");
            }
        } else if (addresses.stream().anyMatch(address ->
                !isPublic(address) && !address.isLoopbackAddress())) {
            throw new IllegalArgumentException("SEARCH_PRIVATE_ADDRESS_DENIED");
        }
        return new ResolvedTarget(uri, host, addresses);
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) return publicIpv4(bytes);
        if (address instanceof Inet6Address) return publicIpv6(bytes);
        return false;
    }

    private static boolean publicIpv4(byte[] b) {
        int first = b[0] & 255;
        int second = b[1] & 255;
        int third = b[2] & 255;
        if (first == 0 || first == 10 || first == 127 || first >= 224) return false;
        if (first == 100 && second >= 64 && second <= 127) return false; // shared CGNAT
        if (first == 169 && second == 254) return false;
        if (first == 172 && second >= 16 && second <= 31) return false;
        if (first == 192 && second == 0 && third == 0) return false;
        if (first == 192 && second == 0 && third == 2) return false; // documentation
        if (first == 192 && second == 88 && third == 99) return false; // 6to4 relay anycast
        if (first == 192 && second == 168) return false;
        if (first == 198 && (second == 18 || second == 19)) return false; // benchmark
        if (first == 198 && second == 51 && third == 100) return false; // documentation
        return !(first == 203 && second == 0 && third == 113); // documentation
    }

    private static boolean publicIpv6(byte[] b) {
        if ((b[0] & 0xfe) == 0xfc) return false; // ULA
        if ((b[0] & 0xff) == 0xfe && (b[1] & 0xc0) == 0x80) return false; // link-local
        if ((b[0] & 0xff) == 0xff) return false; // multicast
        if (prefix(b, new int[]{0x01, 0x00, 0, 0, 0, 0, 0, 0}, 64)) return false; // discard-only
        if (prefix(b, new int[]{0x00, 0x64, 0xff, 0x9b, 0x00, 0x01}, 48)) return false; // local translation
        if (prefix(b, new int[]{0x20, 0x01, 0x00}, 23)) return false; // IETF protocol assignments
        if (prefix(b, new int[]{0x20, 0x01, 0x0d, 0xb8}, 32)) return false; // documentation
        if (prefix(b, new int[]{0x20, 0x02}, 16)) return false; // 6to4
        if (prefix(b, new int[]{0x3f, 0xff, 0x00}, 20)) return false; // documentation
        if (prefix(b, new int[]{0x5f, 0x00}, 16)) return false; // segment routing
        if (isIpv4Mapped(b)) return publicIpv4(Arrays.copyOfRange(b, 12, 16));
        return true;
    }

    private static boolean prefix(byte[] bytes, int[] prefix, int bits) {
        int whole = bits / 8;
        for (int i = 0; i < whole; i++) if ((bytes[i] & 255) != prefix[i]) return false;
        int remaining = bits % 8;
        if (remaining == 0) return true;
        int mask = 0xff << (8 - remaining);
        return ((bytes[whole] & 255) & mask) == (prefix[whole] & mask);
    }

    private static boolean isIpv4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
        return (b[10] & 255) == 255 && (b[11] & 255) == 255;
    }

    private static List<InetAddress> resolve(String host, Resolver resolver) {
        try {
            List<InetAddress> addresses = List.copyOf(resolver.resolve(host));
            if (addresses.isEmpty()) throw new IllegalArgumentException("SEARCH_HOST_UNRESOLVED");
            return addresses;
        } catch (UnknownHostException failure) {
            throw new IllegalArgumentException("SEARCH_HOST_UNRESOLVED", failure);
        }
    }

    private static URI parseHttpUri(String value, boolean httpsOnly) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("search endpoint is required");
        URI uri = URI.create(value.strip());
        boolean schemeAllowed = "https".equalsIgnoreCase(uri.getScheme())
                || (!httpsOnly && "http".equalsIgnoreCase(uri.getScheme()));
        if (!schemeAllowed || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(httpsOnly
                    ? "search source must be public HTTPS"
                    : "search endpoint must be HTTP(S) without user info");
        }
        return uri;
    }
}

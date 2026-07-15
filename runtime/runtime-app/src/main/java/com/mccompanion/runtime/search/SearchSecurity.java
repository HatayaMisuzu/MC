package com.mccompanion.runtime.search;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

final class SearchSecurity {
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
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null) throw new IllegalArgumentException("search source must be public HTTPS");
        String host = normalizedDomain(uri.getHost());
        if (!allowedDomains.isEmpty() && allowedDomains.stream().noneMatch(domain ->
                host.equals(domain) || host.endsWith("." + domain))) throw new IllegalArgumentException("SEARCH_DOMAIN_DENIED");
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("SEARCH_PRIVATE_ADDRESS_DENIED");
                }
            }
        } catch (java.net.UnknownHostException failure) {
            throw new IllegalArgumentException("SEARCH_HOST_UNRESOLVED", failure);
        }
        return uri;
    }
}

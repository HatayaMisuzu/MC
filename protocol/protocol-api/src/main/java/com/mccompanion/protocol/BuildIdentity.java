package com.mccompanion.protocol;

import java.io.IOException;
import java.util.Properties;

/** Identity supplied by the build; never inferred from the working directory. */
public final class BuildIdentity {
    public static final String PROTOCOL = "mc-companion/2";
    public static final String PRODUCT_VERSION = loadVersion();
    private BuildIdentity() { }

    private static String loadVersion() {
        try (var input = BuildIdentity.class.getResourceAsStream("/mcac/build.properties")) {
            if (input == null) throw new IllegalStateException("Missing MCAC build identity");
            Properties properties = new Properties();
            properties.load(input);
            return ProtocolFields.identifier(properties.getProperty("productVersion"), "productVersion");
        } catch (IOException invalid) {
            throw new IllegalStateException("Cannot read MCAC build identity", invalid);
        }
    }
}

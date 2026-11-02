package dev.victor.plug.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/*
 * Version markers reported by plug --version. Both constants
 * come from the build itself: version.properties is filtered by
 * Maven from the pom's <version>, so the CLI, the language, and
 * the artifact always report one consistent value — no release
 * numbers are assigned independently in code. A plain javac
 * build (which does not filter resources) falls back to a
 * non-versioned dev marker rather than inventing a number.
 */
public final class Version {

    private static final String VERSION = load();

    public static final String CLI = VERSION;

    public static final String LANGUAGE = VERSION;

    private Version() {
    }

    private static String load() {

        try (InputStream in =
                     Version.class
                             .getResourceAsStream(
                                     "/version.properties"
                             )) {

            if (in != null) {

                Properties properties = new Properties();

                properties.load(in);

                String version =
                        properties.getProperty("version");

                if (version == null || version.isBlank()) {

                    return "dev build";
                }

                return version.trim();
            }

            return "dev build";

        } catch (IOException e) {

            return "dev build";
        }
    }
}

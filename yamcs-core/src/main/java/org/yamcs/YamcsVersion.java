package org.yamcs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Provides access to build-time information of the Yamcs project.
 */
public class YamcsVersion {

    /*
     * This class reads a property file that is generated by Maven.
     */

    public static final String VERSION;
    public static final String REVISION;

    static {
        String ver = null;
        String rev = null;

        try (var resourceIn = YamcsVersion.class.getResourceAsStream("/org.yamcs.core.properties")) {
            if (resourceIn != null) {
                var props = new Properties();
                props.load(resourceIn);

                ver = props.getProperty("version");
                if (ver != null && ver.isBlank()) {
                    ver = null;
                }

                rev = props.getProperty("revision");
                if (rev != null && rev.isBlank()) {
                    rev = null;
                }
            }
        } catch (IOException e) { // ignore errors
        }

        VERSION = ver;
        REVISION = rev;
    }

    /*
     * Main called by Maven to generate org.yamcs.core.properties
     */
    public static void main(String[] args) throws IOException {
        var versionArg = args[0].substring("VERSION=".length());
        var revisionArg = args[1].substring("REVISION=".length());
        var version = versionArg.isBlank() ? null : versionArg;
        var revision = revisionArg.isBlank() ? null : revisionArg;

        // Attempt to resolve revision using git. We allow this functionality to
        // be overriden for when builds are done without an attached git repo.
        if (revision == null) {
            var processBuilder = new ProcessBuilder("git", "rev-parse", "HEAD");
            try {
                var process = processBuilder.start();
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isBlank()) {
                            revision = line;
                        }
                    }
                }
            } catch (IOException e) {
                // Ignore
            }
        }

        var targetFile = Path.of("target/generated-resources/version/org.yamcs.core.properties");
        Files.createDirectories(targetFile.getParent());
        var fileContent = "";
        if (version != null) {
            fileContent += "version=" + version + "\n";
        }
        if (revision != null) {
            fileContent += "revision=" + revision + "\n";
        }
        Files.writeString(targetFile, fileContent);
    }
}

package studio.epicquest.schema.reconciler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The desired state declared as a SQL script (spec 3.2). The script text is
 * read once, as UTF-8.
 */
public record DesiredState(String script) {

    public DesiredState {
        Objects.requireNonNull(script, "script");
    }

    public static DesiredState ofScript(String sql) {
        return new DesiredState(sql);
    }

    public static DesiredState ofResource(ClassLoader loader, String resourceName) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(resourceName, "resourceName");
        try (InputStream in = loader.getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new DesiredStateException("script resource not found: " + resourceName);
            }
            return new DesiredState(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DesiredStateException("could not read script resource: " + resourceName, e);
        }
    }

    public static DesiredState ofPath(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            return new DesiredState(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DesiredStateException("could not read script: " + path, e);
        }
    }
}

package studio.epicquest.schema.reconciler;

/** Invalid builder input, or no or ambiguous dialect (spec 7). */
public class ConfigurationException extends SchemaReconcilerException {

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}

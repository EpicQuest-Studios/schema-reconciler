package studio.epicquest.schema.reconciler.internal;

import java.util.Locale;
import java.util.regex.Pattern;
import studio.epicquest.schema.reconciler.DesiredStateException;
import studio.epicquest.schema.reconciler.spi.Dialect;

/**
 * Prepares the desired script for execution in the scratch namespace (spec 3.2):
 * the literal-target scan (rule 5), the quoted-placeholder rejection, and the
 * {@code ${namespace}} substitution with {@code $${namespace}} escaping.
 */
public final class ScriptProcessor {

    public static final String TOKEN = "${namespace}";
    private static final String ESCAPED = "$" + TOKEN;

    private final String substituted;
    private final boolean hasPlaceholder;

    /**
     * @param script the raw script
     * @param targetNamespace the configured target namespace (for rule 5)
     * @param scratchNamespace the scratch namespace the placeholder binds to
     */
    public ScriptProcessor(String script, String targetNamespace, String scratchNamespace, Dialect dialect) {
        final var open = identifierQuote(dialect)[0];
        final var close = identifierQuote(dialect)[1];
        if (script.contains(open + TOKEN + close)) {
            throw new DesiredStateException(
                    "namespace '" + targetNamespace + "': the placeholder " + TOKEN
                            + " must not be quoted; the replacement is already quoted");
        }
        checkLiteralTarget(script, targetNamespace, dialect);
        final var quotedScratch = dialect.quoteIdentifier(scratchNamespace);
        final var out = new StringBuilder(script.length() + 64);
        var found = false;
        var i = 0;
        while (i < script.length()) {
            if (script.startsWith(ESCAPED, i)) {
                out.append('\0');
                i += ESCAPED.length();
            } else if (script.startsWith(TOKEN, i)) {
                out.append(quotedScratch);
                found = true;
                i += TOKEN.length();
            } else {
                out.append(script.charAt(i++));
            }
        }
        this.substituted = out.toString().replace("\0", TOKEN);
        this.hasPlaceholder = found;
    }

    /** The script with every placeholder replaced by the quoted scratch namespace. */
    public String substituted() {
        return substituted;
    }

    /** Whether the script contained at least one {@code ${namespace}} placeholder. */
    public boolean hasPlaceholder() {
        return hasPlaceholder;
    }

    /**
     * Spec 3.2 rule 5: rejects literal references to the target namespace —
     * bare or in identifier quotes, immediately followed by {@code .}, not
     * preceded by an identifier character or {@code .}. The comparison is the
     * mirror image of namespace normalisation: the quoted form is exact
     * ({@code "APP"} is not {@code app}), and the bare form is matched
     * case-insensitively only when the target is fold-safe — unquoted
     * references fold to lowercase, so they cannot name a mixed-case target
     * anyway. The scan runs on text with the placeholders masked out, so
     * {@code ${namespace}.} can never trip it even if the target is
     * literally named "namespace".
     */
    private static void checkLiteralTarget(String script, String targetNamespace, Dialect dialect) {
        final var masked = maskPlaceholders(script);
        var alternatives = Pattern.quote(dialect.quoteIdentifier(targetNamespace));
        if (targetNamespace.equals(targetNamespace.toLowerCase(Locale.ROOT))) {
            alternatives += "|(?i:" + Pattern.quote(targetNamespace) + ")";
        }
        final var pattern = Pattern.compile(
                "(?<![A-Za-z0-9_$.])(?:" + alternatives + ")\\.");
        final var matcher = pattern.matcher(masked);
        if (matcher.find()) {
            throw new DesiredStateException(
                    "the script refers to the target namespace '" + targetNamespace
                            + "' literally at position " + matcher.start()
                            + "; use ${namespace} or unqualified names instead");
        }
    }

    private static String maskPlaceholders(String script) {
        final var masked = new StringBuilder(script);
        var i = 0;
        while (i < masked.length()) {
            int len;
            if (masked.indexOf(ESCAPED, i) == i) {
                len = ESCAPED.length();
            } else if (masked.indexOf(TOKEN, i) == i) {
                len = TOKEN.length();
            } else {
                i++;
                continue;
            }
            masked.replace(i, i + len, " ".repeat(len));
            i += len;
        }
        return masked.toString();
    }

    private static String[] identifierQuote(Dialect dialect) {
        final var quoted = dialect.quoteIdentifier("rcl_probe");
        return new String[] {quoted.substring(0, 1), quoted.substring(quoted.length() - 1)};
    }
}

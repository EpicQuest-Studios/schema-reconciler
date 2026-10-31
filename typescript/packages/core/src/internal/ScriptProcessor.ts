import { DesiredStateException } from "../DesiredStateException.ts";
import type { Dialect } from "../spi/Dialect.ts";

const TOKEN = "${namespace}";
const ESCAPED = "$" + TOKEN;

/**
 * Prepares the desired script for execution in the scratch namespace (spec 3.2):
 * the literal-target scan (rule 5), the quoted-placeholder rejection, and the
 * {@code ${namespace}} substitution with {@code $${namespace}} escaping.
 */
export class ScriptProcessor {
    static readonly TOKEN = TOKEN;

    /** The script with every placeholder replaced by the quoted scratch namespace. */
    readonly substituted: string;

    /** Whether the script contained at least one {@code ${namespace}} placeholder. */
    readonly hasPlaceholder: boolean;

    /**
     * @param script the raw script
     * @param targetNamespace the configured target namespace (for rule 5)
     * @param scratchNamespace the scratch namespace the placeholder binds to
     */
    constructor(script: string, targetNamespace: string, scratchNamespace: string,
                dialect: Dialect) {
        const [open, close] = identifierQuote(dialect);
        if (script.includes(open + TOKEN + close)) {
            throw new DesiredStateException(
                    "namespace '" + targetNamespace + "': the placeholder " + TOKEN
                            + " must not be quoted; the replacement is already quoted");
        }
        checkLiteralTarget(script, targetNamespace, dialect);
        const quotedScratch = dialect.quoteIdentifier(scratchNamespace);
        const out: string[] = [];
        let found = false;
        let i = 0;
        while (i < script.length) {
            if (script.startsWith(ESCAPED, i)) {
                out.push("\u0000");
                i += ESCAPED.length;
            } else if (script.startsWith(TOKEN, i)) {
                out.push(quotedScratch);
                found = true;
                i += TOKEN.length;
            } else {
                out.push(script.charAt(i++));
            }
        }
        this.substituted = out.join("").replaceAll("\u0000", TOKEN);
        this.hasPlaceholder = found;
    }
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
function checkLiteralTarget(script: string, targetNamespace: string, dialect: Dialect): void {
    const masked = maskPlaceholders(script);
    let hit = search(masked, quote(targetNamespace, dialect), false);
    if (hit < 0 && targetNamespace === targetNamespace.toLowerCase()) {
        hit = search(masked, targetNamespace, true);
    }
    if (hit >= 0) {
        throw new DesiredStateException(
                "the script refers to the target namespace '" + targetNamespace
                        + "' literally at position " + hit
                        + "; use ${namespace} or unqualified names instead");
    }
}

/**
 * Finds {@code (?<![A-Za-z0-9_$.])<name>\.} in {@code text}; Java performs
 * the whole match with one case-sensitive/case-insensitive alternation —
 * JavaScript has no inline flag groups, so the two alternatives run as two
 * searches and the earlier position wins.
 */
function search(text: string, name: string, ignoreCase: boolean): number {
    const flags = ignoreCase ? "gi" : "g";
    const pattern = new RegExp(
            "(?<![A-Za-z0-9_$.])" + quotePattern(name) + "\\.", flags);
    const match = pattern.exec(text);
    return match === null ? -1 : match.index;
}

/** {@code java.util.regex.Pattern.quote} — escapes every regex metacharacter. */
function quotePattern(s: string): string {
    return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function quote(identifier: string, dialect: Dialect): string {
    return dialect.quoteIdentifier(identifier);
}

function maskPlaceholders(script: string): string {
    let masked = script;
    let i = 0;
    while (i < masked.length) {
        let len: number;
        if (masked.startsWith(ESCAPED, i)) {
            len = ESCAPED.length;
        } else if (masked.startsWith(TOKEN, i)) {
            len = TOKEN.length;
        } else {
            i++;
            continue;
        }
        masked = masked.substring(0, i) + " ".repeat(len) + masked.substring(i + len);
        i += len;
    }
    return masked;
}

function identifierQuote(dialect: Dialect): [string, string] {
    const quoted = dialect.quoteIdentifier("rcl_probe");
    return [quoted.substring(0, 1), quoted.substring(quoted.length - 1)];
}

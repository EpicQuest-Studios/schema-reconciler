import { IllegalArgumentError } from "@epicquest-studios/schema-reconciler-core";

/** Identifier quoting and the constant-default classification of spec A.6. */

const NUMERIC = /^[+-]?\d+(\.\d+)?([eE][+-]?\d+)?$/;

/**
 * A single `::type` cast: an optionally schema-qualified type name
 * (quoted or unquoted identifier parts), optional precision and optional
 * array markers — and nothing else. Anything past the type name, for
 * example an operator, a call, a comment or a second statement, makes the
 * whole expression not a plain cast (A.6).
 */
const TYPE_CAST = new RegExp(
        "^::\\s*(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)"
        + "(?:\\s*\\.\\s*(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*))?"
        + "(?:\\s*\\(\\s*\\d+(?:\\s*,\\s*\\d+)*\\s*\\))?"
        + "(?:\\s*\\[\\s*\\])*\\s*$");

const utf8 = new TextEncoder();
const utf8Decoder = new TextDecoder();

/**
 * `"<identifier>"` with embedded quotes doubled (6.1, 8.3). Rejects
 * identifiers that are empty, contain NUL, or exceed 63 UTF-8 bytes.
 */
export function quote(identifier: string): string {
    if (identifier == null || identifier.length === 0
            || utf8.encode(identifier).length > 63
            || identifier.indexOf("\u0000") >= 0) {
        throw new IllegalArgumentError("illegal PostgreSQL identifier: " + identifier);
    }
    return '"' + identifier.replaceAll('"', '""') + '"';
}

/**
 * The conservative lexical rule of spec A.6: a default is constant only if
 * it is a numeric literal, a boolean literal, `NULL`, or a quoted
 * string literal, each optionally followed by one `::type` cast.
 * Anything else (functions, arithmetic, `nextval`) is not constant.
 */
export function isConstantDefault(expression: string | null | undefined): boolean {
    if (expression == null) {
        return false;
    }
    let s = expression.trim();
    const idx = s.indexOf("::");
    if (idx > 0) {
        const cast = s.substring(idx);
        if (!TYPE_CAST.test(cast)) {
            return false;
        }
        s = s.substring(0, idx).trim();
        if (s.indexOf("::") >= 0) {
            return false;
        }
    }
    if (s.length === 0) {
        return false;
    }
    const upper = s.toUpperCase();
    if (upper === "NULL" || upper === "TRUE" || upper === "FALSE") {
        return true;
    }
    if (NUMERIC.test(s)) {
        return true;
    }
    if (s.startsWith("'") && s.endsWith("'") && s.length >= 2) {
        // a single-quoted literal with '' escapes only — no unescaped quotes inside
        const inner = s.substring(1, s.length - 1);
        return inner.replaceAll("''", "").indexOf("'") < 0;
    }
    return false;
}

/**
 * A scratch namespace name (6.3.1): `<target>__rcl_<12 hex>`,
 * truncated to 63 bytes. The prefix is lower-cased: unquoted references
 * fold to lowercase, so only a fold-safe scratch name makes the script
 * check's case-insensitive qualifier comparison sound.
 */
export function scratchName(targetNamespace: string, seed: bigint | number): string {
    const suffix = "__rcl_" + seed.toString(16).padStart(12, "0");
    let prefix = targetNamespace.toLowerCase();
    const max = 63 - suffix.length;
    if (utf8.encode(prefix).length > max) {
        // truncate on a UTF-8 boundary
        const bytes = utf8.encode(prefix);
        let len = max;
        while (len > 0 && (bytes[len]! & 0xC0) === 0x80) {
            len--;
        }
        prefix = utf8Decoder.decode(bytes.subarray(0, len));
    }
    return prefix + suffix;
}

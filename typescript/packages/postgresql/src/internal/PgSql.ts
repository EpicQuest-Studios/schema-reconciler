import { IllegalArgumentError } from "@schema-reconciler/core";

/**
 * PostgreSQL-aware SQL text handling: the normalisation of spec 5.3 and the
 * lexical statement scan the script constraints of spec 3.2 need. Both share
 * one lexer that understands `'...'` strings (with `''` escape), `"..."`
 * quoted identifiers (with `""` escape), `$tag$` dollar-quoted bodies and
 * line/block comments.
 */

/** Normalisation rule 5.3: namespace reference replacement + whitespace collapse. */
export function normalize(text: string, namespace: string): string {
    return collapseWhitespace(replaceNamespaceRefs(text, namespace));
}

function escapeRegex(s: string): string {
    return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** The Java `(?i:…)` equivalent — ASCII case-insensitive char classes. */
function caseInsensitive(s: string): string {
    let out = "";
    for (const c of s) {
        const lower = c.toLowerCase();
        const upper = c.toUpperCase();
        if (lower.length === 1 && upper.length === 1 && lower !== upper
                && c >= "A" && c <= "z") {
            out += "[" + upper.replace(/[\]\\^-]/g, "\\$&")
                    + lower.replace(/[\]\\^-]/g, "\\$&") + "]";
        } else {
            out += escapeRegex(c);
        }
    }
    return out;
}

/**
 * Rule 5.3(1): `<ns>.` and `"<ns>".` become `${namespace}.`
 * at identifier boundaries. The quoted form is matched case-sensitively:
 * `"APP"` is a different namespace from `app` in PostgreSQL.
 * The unquoted form is matched case-insensitively when `ns` is
 * fold-safe (it is its own lowercase fold): unquoted references fold to
 * lowercase, so `COREX.t` also means `corex`.
 */
export function replaceNamespaceRefs(text: string, namespace: string): string {
    let alternatives = escapeRegex(`"${namespace}"`) + "\\.";
    if (namespace === namespace.toLowerCase()) {
        alternatives += "|" + caseInsensitive(namespace) + "\\.";
    }
    const pattern = new RegExp(`(?<![A-Za-z0-9_$.])(?:${alternatives})`, "g");
    return text.replace(pattern, "${namespace}.");
}

/**
 * Splits on `separator` occurrences outside string literals,
 * quoted identifiers, dollar-quoted bodies and comments — canonical
 * `key=value` fields can carry quoted punctuation inside
 * expression, label and identifier values.
 */
export function splitOutsideLiterals(text: string, separator: string): string[] {
    if (separator.length !== 1) {
        throw new IllegalArgumentError("separator must be a single character");
    }
    const parts: string[] = [];
    let start = 0;
    for (let i = 0; i < text.length; i++) {
        const span = nextSpan(text, i);
        if (span !== null) {
            i = span[1] - 1;
        } else if (text.charAt(i) === separator) {
            parts.push(text.substring(start, i));
            start = i + 1;
        }
    }
    parts.push(text.substring(start));
    return parts;
}

/** One trailing `;`, trimmed — deparsed definitions carry one. */
export function dropTerminator(text: string): string {
    const t = text.trimEnd();
    return t.endsWith(";") ? t.substring(0, t.length - 1).trimEnd() : t;
}

/**
 * Rule 5.3(2): whitespace runs outside string literals, quoted identifiers
 * and dollar-quoted bodies collapse to one space; the result is trimmed.
 */
export function collapseWhitespace(text: string): string {
    let out = "";
    let i = 0;
    const n = text.length;
    let pendingSpace = false;
    while (i < n) {
        const span = nextSpan(text, i);
        if (span === null) {
            const c = text.charAt(i);
            if (isWhitespace(c)) {
                pendingSpace = out.length > 0;
                i++;
            } else {
                if (pendingSpace) {
                    out += " ";
                    pendingSpace = false;
                }
                out += c;
                i++;
            }
        } else {
            if (pendingSpace) {
                out += " ";
                pendingSpace = false;
            }
            out += text.substring(i, span[1]);
            i = span[1];
        }
    }
    return out;
}

/** A top-level statement with its significant tokens (outside literals). */
export class StatementTokens {
    readonly text: string;
    readonly tokens: readonly string[];
    readonly offset: number;

    constructor(text: string, tokens: readonly string[], offset: number) {
        this.text = text;
        this.tokens = tokens;
        this.offset = offset;
        Object.freeze(this);
    }
}

/**
 * Splits a script into top-level statements (separated by `;`) and
 * extracts each statement's tokens outside string literals, dollar-quoted
 * bodies and comments. Tokens are upper-cased keywords/punctuation plus
 * quoted identifiers verbatim with their quotes — the quotes distinguish
 * a case-sensitive `"Schema".t` reference from a keyword, which the
 * checks of spec 3.2 need. A `BEGIN ATOMIC … END` routine body
 * (SQL-standard routine syntax) is opaque like a literal: its `;`s
 * separate body statements, not script statements, and its tokens are
 * not part of the outer statement.
 */
export function statements(script: string): StatementTokens[] {
    const out: StatementTokens[] = [];
    let stmtStart = 0;
    let i = 0;
    const n = script.length;
    while (i < n) {
        const span = nextSpan(script, i);
        if (span !== null) {
            i = span[1];
        } else if (script.charAt(i) === ";") {
            addStatement(out, script, stmtStart, i);
            i++;
            stmtStart = i;
        } else if (isIdentStart(script.charAt(i))) {
            const end = wordEnd(script, i);
            if (word(script, i, end) === "BEGIN") {
                const next = nextWord(script, end);
                if (next !== null && word(script, next[0], next[1]) === "ATOMIC") {
                    i = atomicBodyEnd(script, next[1]);
                    continue;
                }
            }
            i = end;
        } else {
            i++;
        }
    }
    addStatement(out, script, stmtStart, n);
    return out;
}

function addStatement(out: StatementTokens[], script: string, from: number, to: number): void {
    const tokens: string[] = [];
    let i = from;
    while (i < to) {
        const span = nextSpan(script, i);
        if (span !== null) {
            if (span[2] === SPAN_IDENT_QUOTED) {
                tokens.push(script.substring(i, span[1]));
            }
            i = span[1];
            continue;
        }
        const c = script.charAt(i);
        if (isWhitespace(c)) {
            i++;
        } else if (isIdentStart(c)) {
            let j = i + 1;
            while (j < to && isIdentPart(script.charAt(j))) {
                j++;
            }
            if (script.substring(i, j).toUpperCase() === "BEGIN") {
                const next = nextWord(script, j);
                if (next !== null && next[1] <= to
                        && script.substring(next[0], next[1]).toUpperCase() === "ATOMIC") {
                    i = atomicBodyEnd(script, next[1]);
                    continue;
                }
            }
            tokens.push(script.substring(i, j).toUpperCase());
            i = j;
        } else if (!isIdentPart(c)) {
            tokens.push(c);
            i++;
        } else {
            i++;
        }
    }
    if (tokens.length > 0) {
        out.push(new StatementTokens(script.substring(from, to), tokens, from));
    }
}

/**
 * The text inside each string or dollar-quoted literal of `text`
 * (delimiters excluded, escapes not decoded) — the bodies a `DO`
 * or `EXECUTE` statement hands to the inner check.
 */
export function literalBodies(text: string): string[] {
    const out: string[] = [];
    let i = 0;
    while (i < text.length) {
        const span = nextSpan(text, i);
        if (span === null) {
            i++;
            continue;
        }
        if (span[2] === SPAN_STRING || span[2] === SPAN_DOLLAR) {
            out.push(literalBody(text, span));
        }
        i = span[1];
    }
    return out;
}

/** The content (delimiters excluded) of a string or dollar-quoted span. */
function literalBody(text: string, span: [number, number, number]): string {
    if (span[2] === SPAN_STRING) {
        // an unterminated span ends at the text length with no closing
        // quote — only strip a real closing delimiter, never content
        const closed = span[1] - 1 > span[0] && text.charAt(span[1] - 1) === "'";
        return text.substring(span[0] + 1, closed ? span[1] - 1 : span[1]);
    }
    const tag = dollarTag(text, span[0])!;
    const innerEnd = text.startsWith(tag, span[1] - tag.length)
            && span[1] - tag.length >= span[0] + tag.length
            ? span[1] - tag.length : span[1];
    return text.substring(span[0] + tag.length, innerEnd);
}

const SPAN_STRING = 1;
const SPAN_IDENT_QUOTED = 2;
const SPAN_DOLLAR = 3;
const SPAN_COMMENT = 4;

/** Block-closing keywords that may trail an `END` (`END IF`, `END LOOP`, …). */
const END_TRAILERS = new Set([
    "IF", "LOOP", "CASE", "WHILE", "FOR", "REPEAT"]);

/** The end of the identifier starting at `pos`. */
function wordEnd(text: string, pos: number): number {
    let i = pos + 1;
    while (i < text.length && isIdentPart(text.charAt(i))) {
        i++;
    }
    return i;
}

/** The upper-cased word between `from` and `to`. */
function word(text: string, from: number, to: number): string {
    return text.substring(from, to).toUpperCase();
}

/** Whitespace and comments. */
function skipFiller(text: string, i: number): number {
    while (i < text.length) {
        if (isWhitespace(text.charAt(i))) {
            i++;
            continue;
        }
        const span = nextSpan(text, i);
        if (span !== null && span[2] === SPAN_COMMENT) {
            i = span[1];
            continue;
        }
        break;
    }
    return i;
}

/** `[start, end]` of the next identifier after `from`, or `null`. */
function nextWord(text: string, from: number): [number, number] | null {
    const i = skipFiller(text, from);
    return i < text.length && isIdentStart(text.charAt(i))
            ? [i, wordEnd(text, i)] : null;
}

/** The index right after the parenthesized group opening at `open`. */
function skipParen(text: string, open: number): number {
    let depth = 0;
    for (let i = open; i < text.length; i++) {
        const span = nextSpan(text, i);
        if (span !== null) {
            i = span[1] - 1;
            continue;
        }
        if (text.charAt(i) === "(") {
            depth++;
        } else if (text.charAt(i) === ")" && --depth === 0) {
            return i + 1;
        }
    }
    return text.length;
}

/**
 * Whether the `FOR` at `from` opens a loop — `FOR var
 * [, var] IN …` — rather than starting a row-lock clause (`FOR
 * UPDATE`), a cursor query (`OPEN c FOR SELECT …`) or a cursor
 * declaration. The operand is a word, comma or parenthesized list and
 * the loop form ends it with `IN`.
 */
function forIsLoop(text: string, from: number): boolean {
    let i = from;
    for (;;) {
        i = skipFiller(text, i);
        if (i >= text.length) {
            return false;
        }
        const c = text.charAt(i);
        if (isIdentStart(c)) {
            if (word(text, i, wordEnd(text, i)) === "IN") {
                return true;
            }
            i = wordEnd(text, i);
        } else if (c === "(") {
            i = skipParen(text, i);
        } else if (c === ",") {
            i++;
        } else {
            return false;
        }
    }
}

/**
 * The end of the `BEGIN ATOMIC` routine body whose `ATOMIC`
 * keyword ended at `from`. The body runs to its matching `END`;
 * nested compound blocks and `CASE` expressions open and close
 * inside it, and `FOR`/`WHILE` headers merge with the `LOOP`
 * that follows them.
 */
function atomicBodyEnd(text: string, from: number): number {
    const blocks: string[] = ["BEGIN"];
    let i = from;
    const n = text.length;
    while (i < n) {
        const span = nextSpan(text, i);
        if (span !== null) {
            i = span[1];
            continue;
        }
        const c = text.charAt(i);
        if (!isIdentStart(c)) {
            i++;
            continue;
        }
        const end = wordEnd(text, i);
        const w = word(text, i, end);
        switch (w) {
            case "BEGIN":
            case "CASE":
            case "IF":
            case "WHILE":
            case "REPEAT":
                blocks.push(w);
                break;
            case "FOR":
                if (forIsLoop(text, end)) {
                    blocks.push("FOR");
                }
                break;
            case "LOOP": {
                const top = blocks[blocks.length - 1];
                if (top !== "FOR" && top !== "WHILE") {
                    blocks.push("LOOP");
                }
                break;
            }
            case "END": {
                blocks.pop();
                const trailer = nextWord(text, end);
                if (blocks.length === 0) {
                    return trailer === null ? end : trailer[1];
                }
                if (trailer !== null
                        && END_TRAILERS.has(word(text, trailer[0], trailer[1]))) {
                    i = trailer[1];
                    continue;
                }
                break;
            }
            default:
                break;
        }
        i = end;
    }
    return n;
}

/**
 * If a literal/comment span starts at `pos`, returns
 * `[start, end, kind]`; otherwise `null`.
 */
function nextSpan(text: string, pos: number): [number, number, number] | null {
    const c = text.charAt(pos);
    const n = text.length;
    if (c === "'") {
        // E'...' strings also escape with a backslash: \' does not end them
        const escapes = pos > 0 && (text.charAt(pos - 1) === "E" || text.charAt(pos - 1) === "e")
                && (pos < 2 || !isIdentPart(text.charAt(pos - 2)));
        let i = pos + 1;
        while (i < n) {
            if (escapes && text.charAt(i) === "\\") {
                i += 2;
                continue;
            }
            if (text.charAt(i) === "'") {
                if (i + 1 < n && text.charAt(i + 1) === "'") {
                    i += 2;
                    continue;
                }
                return [pos, i + 1, SPAN_STRING];
            }
            i++;
        }
        return [pos, n, SPAN_STRING];
    }
    if (c === '"') {
        let i = pos + 1;
        while (i < n) {
            if (text.charAt(i) === '"') {
                if (i + 1 < n && text.charAt(i + 1) === '"') {
                    i += 2;
                    continue;
                }
                return [pos, i + 1, SPAN_IDENT_QUOTED];
            }
            i++;
        }
        return [pos, n, SPAN_IDENT_QUOTED];
    }
    // a '$' after an identifier character is part of that identifier
    if (c === "$" && (pos === 0 || !isIdentPart(text.charAt(pos - 1)))) {
        const tag = dollarTag(text, pos);
        if (tag !== null) {
            const end = text.indexOf(tag, pos + tag.length);
            return [pos, end < 0 ? n : end + tag.length, SPAN_DOLLAR];
        }
    }
    if (c === "-" && pos + 1 < n && text.charAt(pos + 1) === "-") {
        const end = text.indexOf("\n", pos);
        return [pos, end < 0 ? n : end, SPAN_COMMENT];
    }
    if (c === "/" && pos + 1 < n && text.charAt(pos + 1) === "*") {
        let depth = 1;
        let i = pos + 2;
        while (i < n && depth > 0) {
            if (i + 1 < n && text.charAt(i) === "/" && text.charAt(i + 1) === "*") {
                depth++;
                i += 2;
            } else if (i + 1 < n && text.charAt(i) === "*" && text.charAt(i + 1) === "/") {
                depth--;
                i += 2;
            } else {
                i++;
            }
        }
        return [pos, i, SPAN_COMMENT];
    }
    return null;
}

/** The `$tag$` opener at `pos`, or `null`. */
function dollarTag(text: string, pos: number): string | null {
    const n = text.length;
    if (pos + 1 < n && text.charAt(pos + 1) === "$") {
        return "$$";
    }
    let i = pos + 1;
    while (i < n && text.charAt(i) !== "$") {
        const c = text.charAt(i);
        if (i === pos + 1 ? !isIdentStart(c) : !isIdentPart(c)) {
            return null;
        }
        i++;
    }
    return i < n ? text.substring(pos, i + 1) : null;
}

function isWhitespace(c: string): boolean {
    // java.lang.Character.isWhitespace: ASCII whitespace + U+001C–U+001F
    return c === " " || (c >= "\t" && c <= "\r") || (c >= "\u001c" && c <= "\u001f");
}

function isIdentStart(c: string): boolean {
    // java.lang.Character.isLetter(c) || c == '_'
    return c === "_" || /\p{L}/u.test(c);
}

function isIdentPart(c: string): boolean {
    // java.lang.Character.isLetterOrDigit(c) || c == '_' || c == '$'
    return c === "_" || c === "$" || /[\p{L}\p{Nd}]/u.test(c);
}

import { DesiredStateException } from "../DesiredStateException.ts";
import { ObjectId } from "../model/ObjectId.ts";
import { ObjectKind } from "../model/ObjectKind.ts";
import { RenameHint } from "../model/RenameHint.ts";

/**
 * Parses `-- @reconciler rename <KIND> [<parent>.]<old_name> -> <new_name>`
 * comment directives out of a desired-state script (spec 3.2). Identifier
 * paths follow SQL quoting: a `"double quoted"` segment is verbatim text
 * (`""` escapes a quote); a bare segment is used as written. The last path
 * segment is the object name, the rest (at most one) its parent.
 */
const DIRECTIVE = /^\s*--\s*@reconciler\s+rename\s+([A-Za-z_]+)\s+(\S+)\s*->\s*(\S+)\s*$/;

export function parse(script: string): RenameHint[] {
    const hints: RenameHint[] = [];
    for (const line of script.split("\n")) {
        const m = DIRECTIVE.exec(line);
        if (m === null) {
            continue;
        }
        const kind = (ObjectKind as Record<string, string>)[m[1]!.toUpperCase()];
        if (kind === undefined) {
            throw new DesiredStateException(
                    "@reconciler rename: unknown object kind '" + m[1] + "'");
        }
        hints.push(new RenameHint(idPath(kind as ObjectKind, m[2]!),
                toId(kind as ObjectKind, m[2]!, m[3]!)));
    }
    return hints;
}

/** `<KIND> [parent.]name` — the parent comes from the `from` path when `to` carries none. */
function toId(kind: ObjectKind, fromPath: string, toPath: string): ObjectId {
    const to = idPath(kind, toPath);
    if (to.parent !== undefined) {
        return to;
    }
    const from = idPath(kind, fromPath);
    return new ObjectId(kind, to.name, from.parent);
}

function idPath(kind: ObjectKind, text: string): ObjectId {
    const parts = splitPath(text);
    if (parts.length === 0 || parts.length > 2 || parts.some(p => p === "")) {
        throw new DesiredStateException(
                "@reconciler rename: bad identifier path '" + text + "'");
    }
    const parent = parts.length === 2 ? parts[0] : undefined;
    return new ObjectId(kind, parts[parts.length - 1]!, parent);
}

/** Splits a `.`-separated identifier path outside double quotes and unquotes each segment. */
function splitPath(text: string): string[] {
    const parts: string[] = [];
    let cur = "";
    let inQuote = false;
    for (let i = 0; i < text.length; i++) {
        const c = text.charAt(i);
        if (inQuote) {
            if (c === '"') {
                if (text.charAt(i + 1) === '"') {
                    cur += '"';
                    i++;
                } else {
                    inQuote = false;
                }
            } else {
                cur += c;
            }
        } else if (c === '"') {
            inQuote = true;
        } else if (c === ".") {
            parts.push(cur);
            cur = "";
        } else {
            cur += c;
        }
    }
    if (inQuote) {
        throw new DesiredStateException(
                "@reconciler rename: unterminated quote in '" + text + "'");
    }
    parts.push(cur);
    return parts;
}

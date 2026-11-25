import { DesiredStateException } from "../DesiredStateException.ts";
import { ObjectId } from "../model/ObjectId.ts";
import { ObjectKind } from "../model/ObjectKind.ts";
import { RenameHint } from "../model/RenameHint.ts";

/**
 * Parses `-- @reconciler rename` and `-- @reconciler retire` comment directives
 * out of a desired-state script (spec 3.2). Identifier paths follow SQL
 * quoting: a `"double quoted"` segment is verbatim text (`""` escapes a
 * quote); a bare segment is used as written.
 */
const DIRECTIVE = /^\s*--\s*@reconciler\s+rename\s+([A-Za-z_]+)\s+(\S+)\s*->\s*(\S+)\s*$/;
const RETIRE = /^\s*--\s*@reconciler\s+retire\s+([A-Za-z_]+)\s+(\S+)\s*$/;

export interface Directives {
    readonly renameHints: readonly RenameHint[];
    readonly retireTargets: readonly ObjectId[];
}

export function parse(script: string): RenameHint[] {
    return parseDirectives(script).renameHints.slice();
}

export function parseDirectives(script: string): Directives {
    const hints: RenameHint[] = [];
    const retire: ObjectId[] = [];
    for (const line of script.split("\n")) {
        const m = DIRECTIVE.exec(line);
        if (m !== null) {
            const kind = kindOf("rename", m[1]!);
            const from = idPath("rename", kind, m[2]!);
            hints.push(new RenameHint(from, toId(kind, from, m[3]!)));
            continue;
        }
        const r = RETIRE.exec(line);
        if (r !== null) {
            const kind = kindOf("retire", r[1]!);
            if (kind === ObjectKind.OTHER) {
                throw new DesiredStateException(
                        "@reconciler retire: OTHER objects cannot be retired");
            }
            retire.push(idPath("retire", kind, r[2]!));
        }
    }
    return { renameHints: hints, retireTargets: retire };
}

function kindOf(directive: string, text: string): ObjectKind {
    const kind = (ObjectKind as Record<string, string>)[text.toUpperCase()];
    if (kind === undefined) {
        throw new DesiredStateException(
                "@reconciler " + directive + ": unknown object kind '" + text + "'");
    }
    return kind as ObjectKind;
}

/** `<KIND> [parent.]name` — the parent comes from the `from` path when `to` carries none. */
function toId(kind: ObjectKind, from: ObjectId, toPath: string): ObjectId {
    const parts = splitPath("rename", toPath);
    if (parts.length === 0 || parts.length > 2 || parts.some(p => p === "")) {
        throw new DesiredStateException(
                "@reconciler rename: bad identifier path '" + toPath + "'");
    }
    const parent = parts.length === 2 ? parts[0] : from.parent;
    try {
        return new ObjectId(kind, parts[parts.length - 1]!, parent);
    } catch (e) {
        throw new DesiredStateException(
                "@reconciler rename: bad identifier path '" + toPath + "'");
    }
}

function idPath(directive: string, kind: ObjectKind, text: string): ObjectId {
    const parts = splitPath(directive, text);
    if (parts.length === 0 || parts.length > 2 || parts.some(p => p === "")) {
        throw new DesiredStateException(
                "@reconciler " + directive + ": bad identifier path '" + text + "'");
    }
    const parent = parts.length === 2 ? parts[0] : undefined;
    try {
        return new ObjectId(kind, parts[parts.length - 1]!, parent);
    } catch (e) {
        throw new DesiredStateException(
                "@reconciler " + directive + ": bad identifier path '" + text + "'");
    }
}

/** Splits a `.`-separated identifier path outside double quotes and unquotes each segment. */
function splitPath(directive: string, text: string): string[] {
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
                "@reconciler " + directive + ": unterminated quote in '" + text + "'");
    }
    parts.push(cur);
    return parts;
}

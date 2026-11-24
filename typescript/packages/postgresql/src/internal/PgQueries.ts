import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { IllegalArgumentError } from "@epicquest-studios/schema-reconciler-core";

/**
 * Loads the shared PostgreSQL catalog queries from `sql/postgresql/*.sql`
 * (spec A.5 — the same files the Java dialect reads as resources). Each
 * file holds named sections:
 *
 * <pre>
 *   -- name: &lt;query_name&gt;
 *   SELECT ... WHERE n.nspname = ?
 * </pre>
 *
 * `?` marks a positional parameter; {@link get} rewrites every `?` that
 * lies outside single-quoted string literals, `--` line comments and
 * dollar-quoted bodies to `$1, $2, ...` in textual order, the `pg`
 * driver's bind syntax. Parsed queries are cached for the process.
 */

const FILES = ["catalog.sql", "annotations.sql", "dependencies.sql"];

let queries: ReadonlyMap<string, string> | undefined;

/** The shared-query directory — `dist/sql/postgresql` when built, the
 *  repository's `sql/postgresql` when run from `src` under `node --test`. */
function sqlDirectory(): string {
    const here = dirname(fileURLToPath(import.meta.url));
    const candidates = [
        join(here, "..", "sql", "postgresql"),
        join(here, "..", "..", "..", "..", "..", "sql", "postgresql"),
    ];
    for (const dir of candidates) {
        if (existsSync(join(dir, FILES[0]!))) {
            return dir;
        }
    }
    throw new Error("sql/postgresql not found (looked in "
            + candidates.join(", ") + ")");
}

/**
 * Splits one `.sql` file on its `-- name: <ident>` markers. Lines before
 * the first marker (the file header) must be blank or comments.
 */
function parseFile(text: string, file: string): Map<string, string> {
    const sections = new Map<string, string>();
    let name: string | undefined;
    let body: string[] = [];
    let lineNo = 0;
    const flush = () => {
        if (name !== undefined) {
            const sql = body.join("\n").trim();
            if (sql === "") {
                throw new IllegalArgumentError(file + ": query '" + name
                        + "' has no body");
            }
            sections.set(name, sql);
        }
    };
    for (const line of text.split("\n")) {
        lineNo++;
        const marker = /^--\s*name:\s*([A-Za-z_][A-Za-z0-9_]*)\s*$/.exec(line);
        if (marker !== null) {
            flush();
            name = marker[1]!;
            if (sections.has(name)) {
                throw new IllegalArgumentError(file + ": duplicate query '"
                        + name + "'");
            }
            body = [];
        } else if (name === undefined) {
            const trimmed = line.trim();
            if (trimmed !== "" && !trimmed.startsWith("--")) {
                throw new IllegalArgumentError(file + ":" + lineNo
                        + ": SQL before the first '-- name:' marker");
            }
        } else {
            body.push(line);
        }
    }
    flush();
    return sections;
}

/**
 * Rewrites `?` markers to `$1, $2, ...` in textual order. A `?` inside a
 * single-quoted literal (`'..'`, `''` escape), a `--` line comment or a
 * dollar-quoted body (`$$..$$`, `$tag$..$tag$`) is not a parameter.
 */
function bind(sql: string): string {
    let out = "";
    let n = 0;
    let i = 0;
    while (i < sql.length) {
        const c = sql.charAt(i);
        if (c === "'") {
            const end = literalEnd(sql, i);
            out += sql.substring(i, end);
            i = end;
        } else if (c === "-" && sql.charAt(i + 1) === "-") {
            const nl = sql.indexOf("\n", i);
            const end = nl < 0 ? sql.length : nl;
            out += sql.substring(i, end);
            i = end;
        } else if (c === "$") {
            const tag = /^\$[A-Za-z_]*\$/.exec(sql.substring(i));
            if (tag === null) {
                out += c;
                i++;
            } else {
                const close = sql.indexOf(tag[0], i + tag[0].length);
                const end = close < 0 ? sql.length : close + tag[0].length;
                out += sql.substring(i, end);
                i = end;
            }
        } else if (c === "?") {
            out += "$" + ++n;
            i++;
        } else {
            out += c;
            i++;
        }
    }
    return out;
}

/** Index just past the single-quoted literal starting at `from` (`''` escapes). */
function literalEnd(sql: string, from: number): number {
    let i = from + 1;
    while (i < sql.length) {
        if (sql.charAt(i) === "'") {
            if (sql.charAt(i + 1) === "'") {
                i += 2;
            } else {
                return i + 1;
            }
        } else {
            i++;
        }
    }
    return sql.length;
}

function load(): ReadonlyMap<string, string> {
    if (queries === undefined) {
        const dir = sqlDirectory();
        const all = new Map<string, string>();
        for (const file of FILES) {
            for (const [name, sql] of parseFile(
                    readFileSync(join(dir, file), "utf8"), file)) {
                if (all.has(name)) {
                    throw new IllegalArgumentError(
                            "duplicate query '" + name + "'");
                }
                all.set(name, bind(sql));
            }
        }
        queries = all;
    }
    return queries;
}

/** The named catalog query with `$n` bind markers, or throws. */
export function get(name: string): string {
    const sql = load().get(name);
    if (sql === undefined) {
        throw new IllegalArgumentError("unknown catalog query '" + name + "'");
    }
    return sql;
}

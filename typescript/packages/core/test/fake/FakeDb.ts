import { IllegalArgumentError } from "../../src/IllegalArgumentError.ts";
import { Duration } from "../../src/Duration.ts";
import { Dialect } from "../../src/spi/Dialect.ts";
import type { DatabaseMetadata, SqlConnection, SqlDataSource } from "../../src/spi/Sql.ts";
import { Definition } from "../../src/model/Definition.ts";
import { ObjectId } from "../../src/model/ObjectId.ts";
import { ObjectIds } from "../../src/model/ObjectIds.ts";
import { ObjectKind } from "../../src/model/ObjectKind.ts";
import { ObjectMap } from "../../src/model/ObjectMap.ts";
import { ObjectSet } from "../../src/model/ObjectSet.ts";
import { SchemaObject } from "../../src/model/SchemaObject.ts";

/** A statement executed on a connection. */
export interface ExecutedStatement {
    readonly namespace: string;
    readonly sql: string;
}

/**
 * An in-memory database exposing the {@link SqlDataSource}/{@link SqlConnection}
 * SPI, so the whole engine can run in tests without a real database. The fake
 * understands a minimal DDL language:
 *
 * <pre>
 *   CREATE &lt;KIND&gt; &lt;parent.&gt;?&lt;name&gt; [REQUIRES &lt;KIND&gt;:&lt;name&gt;,...] [COMPARED] [DEF &lt;canonical&gt;]
 *   DROP   &lt;KIND&gt; &lt;parent.&gt;?&lt;name&gt;
 *   ALTER  &lt;KIND&gt; &lt;parent.&gt;?&lt;name&gt; DEF &lt;canonical&gt;
 * </pre>
 *
 * {@code COMPARED} sets the content-compared attribute (spec 5.4). A {@code CREATE} stores a {@link SchemaObject} whose canonical definition is
 * the {@code DEF} token (empty when absent); {@code ALTER} replaces it.
 * Transactions copy the committed state on first write and merge on commit.
 */
export class FakeDb {

    private readonly committed = new Map<string, ObjectMap<SchemaObject>>();
    private readonly locks = new Map<string, FakeConnection>();
    private readonly statementLog_: ExecutedStatement[] = [];
    private scratchSequence = 0;

    dataSource(): SqlDataSource {
        return { connect: () => Promise.resolve(this.newConnection()) };
    }

    /** Every statement ever executed through {@code execute}. */
    statementLog(): readonly ExecutedStatement[] {
        return [...this.statementLog_];
    }

    /** Records a statement executed on a connection. */
    logStatement(statement: ExecutedStatement): void {
        this.statementLog_.push(statement);
    }

    /** Statements executed against a namespace, excluding scratch work. */
    statementsOn(namespace: string): ExecutedStatement[] {
        return this.statementLog_.filter(s => s.namespace === namespace);
    }

    /** Reads the committed objects of a namespace, as the catalog sees them. */
    objectsIn(namespace: string): ObjectMap<SchemaObject> {
        return new ObjectMap(this.committed.get(namespace) ?? []);
    }

    /** Executes DDL directly, bypassing the connection — test fixture setup. */
    executeDirect(namespace: string, script: string): void {
        let objects = this.committed.get(namespace);
        if (objects === undefined) {
            objects = new ObjectMap();
            this.committed.set(namespace, objects);
        }
        for (const chunk of script.split(";")) {
            const statement = chunk.trim();
            if (statement !== "") {
                this.applyStatement(objects, statement);
            }
        }
    }

    /** Whether the namespace lock is currently held by any connection. */
    lockHeld(namespace: string): boolean {
        return this.locks.has(namespace);
    }

    /** Inserts an object directly — fixture setup for objects the DDL cannot express. */
    putObject(namespace: string, object: SchemaObject): void {
        let objects = this.committed.get(namespace);
        if (objects === undefined) {
            objects = new ObjectMap();
            this.committed.set(namespace, objects);
        }
        objects.set(object.id, object);
    }

    /** Builds a snapshot object, for fixture setup with {@link putObject}. */
    static object(id: ObjectId, def: string, ...requires: ObjectId[]): SchemaObject {
        return new SchemaObject(id, new Definition(def), new ObjectSet(requires), new Map());
    }

    /** Builds an external-dependent marker object (spec 5.8.3). */
    static externalDependent(id: ObjectId, ...requires: ObjectId[]): SchemaObject {
        return new SchemaObject(id, new Definition(""), new ObjectSet(requires),
                new Map([[Dialect.EXTERNAL_DEPENDENT_ATTRIBUTE, "true"]]));
    }

    nextScratch(): string {
        return "rcl_scratch_" + ++this.scratchSequence;
    }

    tryLock(handle: FakeConnection, namespace: string, _timeout: Duration): boolean {
        // single-threaded: a held lock is either ours or held until released
        const holder = this.locks.get(namespace);
        if (holder !== undefined && holder !== handle) {
            return false;
        }
        this.locks.set(namespace, handle);
        handle.heldLocks.add(namespace);
        return true;
    }

    unlock(handle: FakeConnection, namespace: string): void {
        if (this.locks.get(namespace) === handle) {
            this.locks.delete(namespace);
            handle.heldLocks.delete(namespace);
        }
    }

    /** Applies one DDL statement to a namespace map — connection {@code execute} path. */
    applyStatement(objects: ObjectMap<SchemaObject>, sql: string): void {
        const tokens = sql.split("\n")
                .filter(line => !line.trim().startsWith("--"))
                .join("\n")
                .trim().split(/\s+/);
        if (tokens.length === 1 && tokens[0] === "") {
            return;
        }
        if (tokens.length < 3) {
            throw new Error("syntax error: " + sql);
        }
        switch (tokens[0]) {
            case "CREATE": {
                const id = FakeDb.parseObjectId(tokens[1]!, tokens[2]!);
                if (objects.has(id)) {
                    throw new Error("object already exists: " + id);
                }
                const parsed = parseTail(tokens, 3);
                objects.set(id, new SchemaObject(id,
                        new Definition(parsed.def ?? ""), parsed.requires,
                        parsed.compared
                                ? new Map([[Dialect.CONTENT_COMPARED_ATTRIBUTE, "true"]])
                                : new Map()));
                break;
            }
            case "DROP": {
                const id = FakeDb.parseObjectId(tokens[1]!, tokens[2]!);
                if (!objects.delete(id)) {
                    throw new Error("object does not exist: " + id);
                }
                break;
            }
            case "ALTER": {
                const id = FakeDb.parseObjectId(tokens[1]!, tokens[2]!);
                const existing = objects.get(id);
                if (existing === undefined) {
                    throw new Error("object does not exist: " + id);
                }
                const parsed = parseTail(tokens, 3);
                if (parsed.def === undefined) {
                    throw new Error("ALTER requires DEF: " + sql);
                }
                objects.set(id, new SchemaObject(id, new Definition(parsed.def),
                        parsed.requires.isEmpty ? existing.dependsOn : parsed.requires,
                        existing.attributes));
                break;
            }
            default:
                throw new Error("syntax error: " + sql);
        }
    }

    /** Parses {@code KIND:parent.name} or {@code KIND:name} (last dot splits parent). */
    static parseReference(text: string): ObjectId {
        const colon = text.indexOf(":");
        const kind = text.substring(0, colon) as ObjectKind;
        return FakeDb.parseObjectId(kind, text.substring(colon + 1));
    }

    static parseObjectId(kindToken: string, nameToken: string): ObjectId {
        const kind = kindToken as ObjectKind;
        const dot = nameToken.lastIndexOf(".");
        const parented = new Set<ObjectKind>([
            ObjectKind.COLUMN, ObjectKind.PRIMARY_KEY, ObjectKind.UNIQUE,
            ObjectKind.FOREIGN_KEY, ObjectKind.CHECK, ObjectKind.INDEX,
            ObjectKind.TRIGGER, ObjectKind.POLICY, ObjectKind.COMMENT]);
        if (parented.has(kind)) {
            if (dot < 0) {
                throw new IllegalArgumentError(kind + " requires parent.name: " + nameToken);
            }
            return new ObjectId(kind, nameToken.substring(dot + 1),
                    nameToken.substring(0, dot));
        }
        return new ObjectId(kind, nameToken);
    }

    /** Renders a {@code CREATE} statement for an object of the fake language. */
    static createStatement(object: SchemaObject): string {
        let sql = "CREATE " + object.id.kind + " " + qualified(object.id);
        if (!object.dependsOn.isEmpty) {
            const deps = [...object.dependsOn];
            deps.sort(ObjectIds.ORDER);
            sql += " REQUIRES " + deps.map(d => reference(d)).join(",");
        }
        if (object.attributes.get(Dialect.CONTENT_COMPARED_ATTRIBUTE) === "true") {
            sql += " COMPARED";
        }
        if (object.definition.canonical !== "") {
            sql += " DEF " + object.definition.canonical;
        }
        return sql;
    }

    static dropStatement(id: ObjectId): string {
        return "DROP " + id.kind + " " + qualified(id);
    }

    static alterStatement(object: SchemaObject): string {
        return "ALTER " + object.id.kind + " " + qualified(object.id)
                + " DEF " + object.definition.canonical;
    }

    static qualified(id: ObjectId): string {
        return id.parent !== undefined ? id.parent + "." + id.name : id.name;
    }

    static reference(id: ObjectId): string {
        return id.kind + ":" + qualified(id);
    }

    newConnection(): FakeConnection {
        return new FakeConnection(this);
    }

    /** The committed-or-overlay namespace state a connection sees. */
    stateOf(conn: FakeConnection, ns: string): ObjectMap<SchemaObject> {
        if (!conn.autoCommit && conn.overlay === null) {
            conn.overlay = new Map();
        }
        if (conn.overlay !== null) {
            let objects = conn.overlay.get(ns);
            if (objects === undefined) {
                objects = new ObjectMap(this.committed.get(ns) ?? []);
                conn.overlay.set(ns, objects);
            }
            return objects;
        }
        let objects = this.committed.get(ns);
        if (objects === undefined) {
            objects = new ObjectMap();
            this.committed.set(ns, objects);
        }
        return objects;
    }

    commitOverlay(conn: FakeConnection): void {
        if (conn.overlay !== null) {
            for (const [ns, objects] of conn.overlay) {
                this.committed.set(ns, objects);
            }
            conn.overlay = null;
        }
    }
}

interface Parsed {
    readonly requires: ObjectSet;
    readonly def: string | undefined;
    readonly compared: boolean;
}

function parseTail(tokens: readonly string[], from: number): Parsed {
    const requires = new ObjectSet();
    let def: string | undefined;
    let compared = false;
    for (let i = from; i < tokens.length; i++) {
        if (tokens[i] === "COMPARED") {
            compared = true;
        } else if (tokens[i] === "REQUIRES" && i + 1 < tokens.length) {
            for (const ref of tokens[i + 1]!.split(",")) {
                requires.add(FakeDb.parseReference(ref));
            }
            i++;
        } else if (tokens[i] === "DEF" && i + 1 < tokens.length) {
            const text: string[] = [tokens[++i]!];
            while (i + 1 < tokens.length) {
                text.push(tokens[++i]!);
            }
            def = text.join(" ");
        }
    }
    return { requires, def, compared };
}

function qualified(id: ObjectId): string {
    return FakeDb.qualified(id);
}

function reference(id: ObjectId): string {
    return FakeDb.reference(id);
}

/** Connection state and SPI behaviour. */
export class FakeConnection implements SqlConnection {

    autoCommit = true;
    closed = false;
    namespace = "main";
    overlay: Map<string, ObjectMap<SchemaObject>> | null = null;
    readonly heldLocks = new Set<string>();
    private readonly db: FakeDb;

    constructor(db: FakeDb) {
        this.db = db;
    }

    metadata(): Promise<DatabaseMetadata> {
        return Promise.resolve({
            databaseProductName: "FakeDB",
            databaseMajorVersion: 1,
        });
    }

    getAutoCommit(): boolean {
        return this.autoCommit;
    }

    async setAutoCommit(on: boolean): Promise<void> {
        this.autoCommit = on;
        if (on && this.overlay !== null) {
            this.db.commitOverlay(this);
        }
    }

    async commit(): Promise<void> {
        this.db.commitOverlay(this);
    }

    async rollback(): Promise<void> {
        this.overlay = null;
    }

    async close(): Promise<void> {
        if (!this.closed) {
            this.closed = true;
            this.overlay = null;
            for (const ns of this.heldLocks) {
                this.db.unlock(this, ns);
            }
            this.heldLocks.clear();
        }
    }

    async execute(sql: string): Promise<void> {
        const trimmed = sql.trim();
        if (trimmed === "") {
            return;
        }
        this.db.logStatement({ namespace: this.namespace, sql: trimmed });
        this.db.applyStatement(this.db.stateOf(this, this.namespace), trimmed);
    }

    query<R = Record<string, unknown>>(_sql: string, _params?: readonly unknown[]): Promise<R[]> {
        return Promise.resolve([]);
    }
}

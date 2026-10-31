import { IllegalArgumentError } from "../../src/IllegalArgumentError.ts";
import { LockTimeoutException } from "../../src/LockTimeoutException.ts";
import { Dialect } from "../../src/spi/Dialect.ts";
import { DialectCapabilities } from "../../src/spi/DialectCapabilities.ts";
import type { DatabaseMetadata, SqlConnection } from "../../src/spi/Sql.ts";
import { ChangeAction } from "../../src/model/ChangeAction.ts";
import { ChangeSafety } from "../../src/model/ChangeSafety.ts";
import { Difference } from "../../src/model/Difference.ts";
import { DifferenceType } from "../../src/model/DifferenceType.ts";
import type { ObjectId } from "../../src/model/ObjectId.ts";
import { ObjectIds } from "../../src/model/ObjectIds.ts";
import { ObjectKind } from "../../src/model/ObjectKind.ts";
import { PlannedChange } from "../../src/model/PlannedChange.ts";
import type { SchemaObject } from "../../src/model/SchemaObject.ts";
import { SchemaSnapshot } from "../../src/model/SchemaSnapshot.ts";
import { FakeDb, FakeConnection } from "./FakeDb.ts";
import type { Duration } from "../../src/Duration.ts";

/** OTHER categories whose stripped names occupy the relation namespace (mirrors PgRender). */
const RELATION_NAMESPACE_OTHERS: ReadonlySet<string> = new Set([
    "inherited", "partindex", "toast", "relation", "type"]);

/**
 * A {@link Dialect} over {@link FakeDb}, used to exercise the whole engine in
 * core unit tests. Mirroring the PostgreSQL profile: a missing table's create
 * covers its columns and owned sequences; drifted views/routines/etc. are
 * recreate pairs ({@code REBUILD}), drifted tables recreate {@code DESTRUCTIVE},
 * drifted sequences are a destructive {@code ALTER}, and drifted columns are
 * manual destructive changes.
 */
export class FakeDialect implements Dialect {

    private readonly db: FakeDb;
    private readonly supportedKinds: ReadonlySet<ObjectKind>;

    constructor(db: FakeDb, supportedKinds?: Iterable<ObjectKind>) {
        this.db = db;
        this.supportedKinds = new Set(supportedKinds ?? Object.values(ObjectKind));
    }

    id(): string {
        return "fake";
    }

    supports(metadata: DatabaseMetadata): boolean {
        return metadata.databaseProductName === "FakeDB";
    }

    capabilities(): DialectCapabilities {
        return new DialectCapabilities(true, true, this.supportedKinds);
    }

    acquireLock(connection: SqlConnection, namespace: string, timeout: Duration): Promise<void> {
        if (!this.db.tryLock(handle(connection), namespace, timeout)) {
            throw new LockTimeoutException("namespace '" + namespace
                    + "': lock not acquired within " + timeout);
        }
        return Promise.resolve();
    }

    releaseLock(connection: SqlConnection, namespace: string): Promise<void> {
        this.db.unlock(handle(connection), namespace);
        return Promise.resolve();
    }

    bindSessionNamespace(connection: SqlConnection, namespace: string): Promise<void> {
        handle(connection).namespace = namespace;
        return Promise.resolve();
    }

    createScratchNamespace(connection: SqlConnection, _targetNamespace: string): Promise<string> {
        const scratch = this.db.nextScratch();
        this.db.stateOf(handle(connection), scratch);
        return Promise.resolve(scratch);
    }

    dropScratchNamespace(connection: SqlConnection, scratchNamespace: string): Promise<void> {
        this.db.stateOf(handle(connection), scratchNamespace).clear();
        return Promise.resolve();
    }

    async executeScript(connection: SqlConnection, script: string,
                        _scratchNamespace: string): Promise<void> {
        for (const chunk of script.split(";")) {
            const statement = chunk.trim();
            if (statement !== "") {
                await connection.execute(statement);
            }
        }
    }

    async readSnapshot(connection: SqlConnection, namespace: string): Promise<SchemaSnapshot> {
        return SchemaSnapshot.of(this.db.stateOf(handle(connection), namespace));
    }

    render(difference: Difference, desired: SchemaSnapshot,
           _targetNamespace: string): PlannedChange[] {
        const id = difference.id;
        switch (difference.type) {
            case DifferenceType.MISSING: {
                const want = desired.objects.get(id)!;
                const statements: string[] = [FakeDb.createStatement(want)];
                const children = [...(this.coveredChildren(difference, desired) ?? [])];
                children.sort(ObjectIds.ORDER);
                for (const child of children) {
                    statements.push(FakeDb.createStatement(desired.objects.get(child)!));
                }
                return [new PlannedChange(0, id, ChangeAction.CREATE,
                        ChangeSafety.ADDITIVE, statements, want.definition,
                        "missing " + id)];
            }
            case DifferenceType.DRIFTED:
                switch (id.kind) {
                    case ObjectKind.TABLE:
                        return this.recreate(desired, id, ChangeSafety.DESTRUCTIVE,
                                "drifted table");
                    case ObjectKind.SEQUENCE: {
                        const want = desired.objects.get(id)!;
                        return [new PlannedChange(0, id, ChangeAction.ALTER,
                                ChangeSafety.DESTRUCTIVE, [FakeDb.alterStatement(want)],
                                want.definition, "drifted sequence")];
                    }
                    case ObjectKind.COLUMN:
                        return [new PlannedChange(0, id, ChangeAction.ALTER,
                                ChangeSafety.DESTRUCTIVE, [], undefined,
                                "drifted column requires manual change")];
                    default:
                        return this.recreate(desired, id, ChangeSafety.REBUILD,
                                "drifted " + id.kind);
                }
            default:
                return [];
        }
    }

    private recreate(desired: SchemaSnapshot, id: ObjectId, safety: ChangeSafety,
                     reason: string): PlannedChange[] {
        const want = desired.objects.get(id)!;
        return [
            new PlannedChange(0, id, ChangeAction.DROP, safety,
                    [FakeDb.dropStatement(id)], undefined, reason),
            new PlannedChange(0, id, ChangeAction.CREATE, safety,
                    [FakeDb.createStatement(want)], want.definition, reason),
        ];
    }

    quoteIdentifier(identifier: string): string {
        if (identifier == null || identifier === "" || identifier.length > 63
                || identifier.indexOf("\u0000") >= 0) {
            throw new IllegalArgumentError("illegal identifier: " + identifier);
        }
        return '"' + identifier.replaceAll('"', '""') + '"';
    }

    coveredChildren(created: Difference, desired: SchemaSnapshot): ObjectId[] {
        if (created.id.kind !== ObjectKind.TABLE) {
            return [];
        }
        const table = created.id;
        const children: ObjectId[] = [];
        for (const object of desired.objects.values()) {
            const id = object.id;
            if (id.kind === ObjectKind.COLUMN && (id.parent ?? "") === table.name) {
                children.push(id);
            }
            if (id.kind === ObjectKind.SEQUENCE && object.dependsOn.has(table)) {
                children.push(id);
            }
        }
        return children;
    }

    nameScopes(id: ObjectId): string[] {
        switch (id.kind) {
            case ObjectKind.OTHER: {
                const name = id.name;
                const colon = name.indexOf(":");
                if (colon > 0) {
                    const category = name.substring(0, colon);
                    const stripped = name.substring(colon + 1);
                    if (RELATION_NAMESPACE_OTHERS.has(category)) {
                        return ["relation:" + stripped];
                    }
                    if (category === "constraint") {
                        return ["constraint:" + stripped,
                                "relation:" + stripped.substring(stripped.lastIndexOf(".") + 1)];
                    }
                    if (category === "routine") {
                        return ["routine:" + stripped];
                    }
                }
                return ["relation:" + name];
            }
            case ObjectKind.TABLE:
            case ObjectKind.VIEW:
            case ObjectKind.SEQUENCE:
            case ObjectKind.INDEX:
            case ObjectKind.MATERIALIZED_VIEW:
            case ObjectKind.TYPE:
            case ObjectKind.DOMAIN:
            case ObjectKind.PARTITION:
            case ObjectKind.FOREIGN_TABLE:
                return ["relation:" + id.name];
            case ObjectKind.PRIMARY_KEY:
            case ObjectKind.UNIQUE:
            case ObjectKind.EXCLUSION:
                return ["constraint:" + (id.parent ?? "") + "." + id.name,
                        "relation:" + id.name];
            case ObjectKind.FOREIGN_KEY:
            case ObjectKind.CHECK:
                return ["constraint:" + (id.parent ?? "") + "." + id.name];
            case ObjectKind.COLUMN:
                return ["column:" + (id.parent ?? "") + "." + id.name];
            case ObjectKind.TRIGGER:
                return ["trigger:" + (id.parent ?? "") + "." + id.name];
            case ObjectKind.ROUTINE:
            case ObjectKind.AGGREGATE:
                return ["routine:" + id.name];
            default:
                return [];
        }
    }

    /**
     * The fake language round-trips its own {@code CREATE} text, so every
     * managed object dumps as a creatable statement; {@code OTHER} objects
     * and external-dependent markers have no create fragment (spec 6.1).
     */
    dumpStatements(object: SchemaObject): string[] {
        if (object.id.kind === ObjectKind.OTHER
                || object.attributes.has(Dialect.EXTERNAL_DEPENDENT_ATTRIBUTE)) {
            return [];
        }
        return [FakeDb.createStatement(object)];
    }
}

function handle(connection: SqlConnection): FakeConnection {
    return connection as FakeConnection;
}

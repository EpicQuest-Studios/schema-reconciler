import assert from "node:assert/strict";
import test from "node:test";
import { ConfigurationException } from "../src/ConfigurationException.ts";
import { DesiredState } from "../src/DesiredState.ts";
import { Duration } from "../src/Duration.ts";
import { SchemaReconciler } from "../src/SchemaReconciler.ts";
import { registerDialect, unregisterDialect } from "../src/internal/dialects.ts";
import type { Difference } from "../src/model/Difference.ts";
import { PlannedChange } from "../src/model/PlannedChange.ts";
import type { SchemaSnapshot } from "../src/model/SchemaSnapshot.ts";
import type { Dialect } from "../src/spi/Dialect.ts";
import { DialectCapabilities } from "../src/spi/DialectCapabilities.ts";
import type { DatabaseMetadata, SqlConnection, SqlDataSource }
        from "../src/spi/Sql.ts";
import { FakeDb } from "./fake/FakeDb.ts";
import { FakeDialect } from "./fake/FakeDialect.ts";

/** Spec 3.4 (selection), 6.3.2 (same-engine scratch) and 10.3 (core major version). */

const NS = "corex";

/** A dialect with no behaviour: selection only ever asks it {@code supports}. */
class Stub implements Dialect {
    private readonly id_: string;
    private readonly supported: boolean;
    private readonly coreMajor: number;

    constructor(id: string, supported: boolean, coreMajor: number) {
        this.id_ = id;
        this.supported = supported;
        this.coreMajor = coreMajor;
    }

    id(): string {
        return this.id_;
    }

    supports(metadata: DatabaseMetadata): boolean {
        return this.supported && metadata.databaseProductName === "FakeDB";
    }

    coreMajorVersion(): number {
        return this.coreMajor;
    }

    capabilities(): DialectCapabilities {
        return new DialectCapabilities(true, true, new Set());
    }

    acquireLock(_c: SqlConnection, _ns: string, _t: Duration): Promise<void> {
        return Promise.resolve();
    }

    releaseLock(_c: SqlConnection, _ns: string): Promise<void> {
        return Promise.resolve();
    }

    createScratchNamespace(_c: SqlConnection, _ns: string): Promise<string> {
        throw new Error("unsupported");
    }

    dropScratchNamespace(_c: SqlConnection, _ns: string): Promise<void> {
        return Promise.resolve();
    }

    executeScript(_c: SqlConnection, _s: string, _ns: string): Promise<void> {
        return Promise.resolve();
    }

    readSnapshot(_c: SqlConnection, _ns: string): Promise<SchemaSnapshot> {
        throw new Error("unsupported");
    }

    render(_d: Difference, _s: SchemaSnapshot, _ns: string): PlannedChange[] {
        throw new Error("unsupported");
    }

    quoteIdentifier(identifier: string): string {
        return identifier;
    }
}

/** Registers {@code dialects} for discovery, unregistering them afterwards. */
async function withProviders<T>(body: () => Promise<T>, ...dialects: Dialect[]): Promise<T> {
    for (const d of dialects) {
        registerDialect(d);
    }
    try {
        return await body();
    } finally {
        for (const d of dialects) {
            unregisterDialect(d);
        }
    }
}

function discovering(db: FakeDb): SchemaReconciler {
    return SchemaReconciler.builder().dataSource(db.dataSource()).namespace(NS)
            .desiredState(DesiredState.ofScript("CREATE TABLE t DEF v")).build();
}

test("severalMatchingDialectsAreAmbiguous", async () => {
    let e: unknown;
    try {
        await withProviders(() => discovering(new FakeDb()).plan(),
                new Stub("alpha", true, 1), new Stub("beta", true, 1));
    } catch (x) {
        e = x;
    }
    assert.ok(e instanceof ConfigurationException, "expected ConfigurationException: " + e);
    assert.match((e as Error).message, /several dialects/);
    assert.match((e as Error).message, /alpha, beta/);
    assert.match((e as Error).message, new RegExp("namespace '" + NS + "'"));
});

test("noMatchingDialectNamesTheProductAndTheCandidates", async () => {
    let e: unknown;
    try {
        await withProviders(() => discovering(new FakeDb()).plan(),
                new Stub("unsupporting", false, 1));
    } catch (x) {
        e = x;
    }
    assert.ok(e instanceof ConfigurationException);
    assert.match((e as Error).message, /no dialect supports/);
    assert.match((e as Error).message, /FakeDB/);
    assert.match((e as Error).message, /unsupporting/);
});

test("aDialectBuiltForAnotherCoreMajorVersionIsRefused", async () => {
    let discovered: unknown;
    try {
        await withProviders(() => discovering(new FakeDb()).plan(),
                new Stub("future", true, 2));
    } catch (x) {
        discovered = x;
    }
    assert.ok(discovered instanceof ConfigurationException);
    assert.match((discovered as Error).message, /core major version/);

    const db = new FakeDb();
    const real = new FakeDialect(db);
    const explicit: Dialect = Object.create(real);
    explicit.coreMajorVersion = () => 2;
    let e: unknown;
    try {
        await SchemaReconciler.builder().dataSource(db.dataSource()).namespace(NS)
                .desiredState(DesiredState.ofScript("CREATE TABLE t DEF v"))
                .dialect(explicit).build().plan();
    } catch (x) {
        e = x;
    }
    assert.ok(e instanceof ConfigurationException);
    assert.match((e as Error).message, /core major version/);
});

/** A SqlDataSource whose connections report a different product or major version. */
function disguised(real: SqlDataSource, product: string, major: number): SqlDataSource {
    return {
        async connect(): Promise<SqlConnection> {
            const connection = await real.connect();
            const disguisedConnection: SqlConnection = Object.create(connection);
            disguisedConnection.metadata = async () => ({
                databaseProductName: product,
                databaseMajorVersion: major,
            });
            return disguisedConnection;
        },
    };
}

test("scratchDataSourceMustBeTheSameEngineAndMajorVersion", async () => {
    for (const [product, major] of [["FakeDB", 2], ["OtherDB", 1]] as const) {
        const db = new FakeDb();
        const reconciler = SchemaReconciler.builder().dataSource(db.dataSource())
                .scratchDataSource(disguised(db.dataSource(), product, major))
                .namespace(NS).desiredState(DesiredState.ofScript("CREATE TABLE t DEF v"))
                .dialect(new FakeDialect(db)).build();
        let e: unknown;
        try {
            await reconciler.plan();
        } catch (x) {
            e = x;
        }
        assert.ok(e instanceof ConfigurationException, "expected ConfigurationException: " + e);
        assert.match((e as Error).message, /not the same engine/);
        assert.match((e as Error).message, new RegExp("namespace '" + NS + "'"));
    }
});

/** Spec 6.1: checkAccess runs per connection before the lock; executeScript gets the scratch name. */
test("spiContractCallsMatchTheJavadoc", async () => {
    const db = new FakeDb();
    const real = new FakeDialect(db);
    const calls: string[] = [];
    const scratchNames: string[] = [];
    const recording: Dialect = Object.create(real);
    recording.checkAccess = async (c, ns, create) => {
        calls.push("checkAccess(" + ns + "," + create + ")");
        return (real as Dialect).checkAccess?.(c, ns, create);
    };
    recording.acquireLock = async (c, ns, t) => {
        calls.push("acquireLock");
        return real.acquireLock(c, ns, t);
    };
    recording.executeScript = async (c, s, ns) => {
        calls.push("executeScript(" + ns + ")");
        return real.executeScript(c, s, ns);
    };
    recording.createScratchNamespace = async (c, ns) => {
        const name = await real.createScratchNamespace(c, ns);
        scratchNames.push(name);
        return name;
    };
    await SchemaReconciler.builder().dataSource(db.dataSource())
            .scratchDataSource(db.dataSource()).namespace(NS)
            .desiredState(DesiredState.ofScript("CREATE TABLE t DEF v"))
            .dialect(recording).build().plan();
    // target connection first (namespace named, scratch not created there), then the
    // scratch connection (no namespace, scratch creation needed), all before the lock
    assert.deepEqual(["checkAccess(" + NS + ",false)", "acquireLock",
            "checkAccess(undefined,true)", "executeScript(" + scratchNames[0] + ")"], calls);
});

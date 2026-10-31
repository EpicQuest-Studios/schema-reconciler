import type { DatabaseMetadata, SqlConnection, SqlDataSource }
        from "@epicquest-studios/schema-reconciler-core";

/** One recorded statement. */
export interface ExecutedSql {
    readonly sql: string;
    readonly prepared: boolean;
}

/**
 * A `SqlDataSource` that records every SQL string passed to
 * `execute`/`query`. Used by conformance scenarios that must observe which
 * statements ran where (for example C-02's "no statements against the
 * target namespace").
 */
export class TrackingDataSource implements SqlDataSource {
    private readonly delegate: SqlDataSource;
    private readonly log: ExecutedSql[] = [];

    constructor(delegate: SqlDataSource) {
        this.delegate = delegate;
    }

    /** All recorded SQL, in execution order. */
    statements(): readonly ExecutedSql[] {
        return [...this.log];
    }

    /** SQL recorded through `execute` (not `query`). */
    executedSql(): readonly string[] {
        return this.log.filter(s => !s.prepared).map(s => s.sql);
    }

    async connect(): Promise<SqlConnection> {
        return new TrackingConnection(await this.delegate.connect(),
                (sql, prepared) => this.log.push({ sql, prepared }));
    }
}

class TrackingConnection implements SqlConnection {
    private readonly target: SqlConnection;
    private readonly record: (sql: string, prepared: boolean) => void;

    constructor(target: SqlConnection,
            record: (sql: string, prepared: boolean) => void) {
        this.target = target;
        this.record = record;
    }

    metadata(): Promise<DatabaseMetadata> {
        return this.target.metadata();
    }

    async execute(sql: string): Promise<void> {
        this.record(sql, false);
        await this.target.execute(sql);
    }

    query<R>(sql: string, params?: readonly unknown[]): Promise<R[]> {
        this.record(sql, true);
        return this.target.query(sql, params);
    }

    getAutoCommit(): boolean {
        return this.target.getAutoCommit();
    }

    setAutoCommit(on: boolean): Promise<void> {
        return this.target.setAutoCommit(on);
    }

    commit(): Promise<void> {
        return this.target.commit();
    }

    rollback(): Promise<void> {
        return this.target.rollback();
    }

    close(): Promise<void> {
        return this.target.close();
    }
}

/**
 * The asynchronous analog of {@code java.sql.DatabaseMetaData} — the only
 * part the engine reads (product name and major version, spec 3.4, 5.1).
 */
export interface DatabaseMetadata {
    readonly databaseProductName: string;
    readonly databaseMajorVersion: number;
}

/**
 * The asynchronous analog of {@code java.sql.Connection}: one dedicated
 * session — a {@code pg.PoolClient}, a single-connection client, or a test
 * double. All operations are Promise-returning; the engine drives the
 * transaction lifecycle through {@link commit}/{@link rollback} exactly as
 * it does JDBC's.
 *
 * A dialect MAY depend on a richer subtype of this interface (extra query
 * helpers, driver-specific accessors); the engine only ever calls what is
 * declared here.
 */
export interface SqlConnection {
    /** Product metadata for dialect selection and engine-equality checks. */
    metadata(): Promise<DatabaseMetadata>;

    /**
     * Runs statements that return no rows — the analog of
     * {@code Statement.execute}. Multi-statement scripts are the dialect's
     * responsibility (its {@code executeScript}), not this method's.
     */
    execute(sql: string): Promise<void>;

    /**
     * Runs a parameterized statement and returns its rows. Parameter
     * placeholders are dialect-specific ({@code $1} on PostgreSQL).
     */
    query<R = Record<string, unknown>>(sql: string, params?: readonly unknown[]): Promise<R[]>;

    /** Whether the connection commits every statement implicitly. */
    getAutoCommit(): boolean;

    /** Turns implicit commit off (begins the unit of work) or back on. */
    setAutoCommit(on: boolean): Promise<void>;

    commit(): Promise<void>;

    rollback(): Promise<void>;

    /** Returns the session to its pool or closes it. */
    close(): Promise<void>;
}

/**
 * The asynchronous analog of {@code javax.sql.DataSource}: a factory of
 * dedicated connections — typically a {@code pg.Pool}. The engine calls
 * {@link connect} once per run (and once more for the scratch connection
 * when a separate {@code scratchDataSource} is configured).
 */
export interface SqlDataSource {
    connect(): Promise<SqlConnection>;
}

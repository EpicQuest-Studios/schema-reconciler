import { ConfigurationException } from "../ConfigurationException.ts";
import type { Dialect } from "../spi/Dialect.ts";
import type { DatabaseMetadata, SqlConnection } from "../spi/Sql.ts";
import { registeredDialects } from "./dialects.ts";

/**
 * Dialect discovery and deferred selection (spec 3.4, 10.3). Discovery runs at
 * {@code build()} time for presence only; the {@code supports} selection runs
 * on first use, when a connection exists.
 */
export class DialectResolver {

    /** The core major version this implementation is (10.3). */
    static readonly CORE_MAJOR_VERSION = 1;

    private resolved: Dialect | undefined;
    private readonly explicit: Dialect | undefined;
    private readonly candidates: readonly Dialect[];

    constructor(explicit: Dialect | undefined,
                candidates: readonly Dialect[] = registeredDialects()) {
        this.explicit = explicit;
        this.candidates = candidates;
    }

    static quoteOf(dialect: Dialect, identifier: string): string {
        try {
            return dialect.quoteIdentifier(identifier);
        } catch (e) {
            throw new ConfigurationException(
                    "namespace '" + identifier + "' is not a legal identifier for dialect '"
                            + dialect.id() + "'", e);
        }
    }

    /** The dialect when already known — explicit or previously resolved. */
    peek(): Dialect | undefined {
        return this.explicit ?? this.resolved;
    }

    async resolve(connection: SqlConnection): Promise<Dialect> {
        if (this.resolved !== undefined) {
            return this.resolved;
        }
        const selected = await this.select(connection);
        this.resolved = selected;
        return selected;
    }

    private async select(connection: SqlConnection): Promise<Dialect> {
        let metaData: DatabaseMetadata;
        try {
            metaData = await connection.metadata();
        } catch (e) {
            throw new ConfigurationException("could not read database metadata", e);
        }
        let selected: Dialect;
        if (this.explicit !== undefined) {
            await checkSupports(this.explicit, metaData);
            selected = this.explicit;
        } else {
            const matching: Dialect[] = [];
            const discovered: string[] = [];
            for (const candidate of this.candidates) {
                discovered.push(candidate.id());
                let supports: boolean;
                try {
                    supports = await candidate.supports(metaData);
                } catch (e) {
                    throw new ConfigurationException(
                            "dialect '" + candidate.id() + "' failed its supports check", e);
                }
                if (supports) {
                    matching.push(candidate);
                }
            }
            if (matching.length === 0) {
                throw new ConfigurationException(
                        "no dialect supports this database: " + productName(metaData)
                                + " (discovered dialects: "
                                + (discovered.length === 0 ? "none" : discovered.join(", ")) + ")");
            }
            if (matching.length > 1) {
                throw new ConfigurationException(
                        "several dialects support " + productName(metaData) + ": "
                                + matching.map(d => d.id()).sort().join(", "));
            }
            selected = matching[0]!;
        }
        const coreVersion = selected.coreMajorVersion?.() ?? 1;
        if (coreVersion !== DialectResolver.CORE_MAJOR_VERSION) {
            throw new ConfigurationException(
                    "dialect '" + selected.id() + "' was built against core major version "
                            + coreVersion + "; this core is " + DialectResolver.CORE_MAJOR_VERSION);
        }
        return selected;
    }
}

async function checkSupports(dialect: Dialect, metaData: DatabaseMetadata): Promise<void> {
    try {
        if (!(await dialect.supports(metaData))) {
            throw new ConfigurationException(
                    "explicitly configured dialect '" + dialect.id()
                            + "' does not support this database: " + productName(metaData));
        }
    } catch (e) {
        if (e instanceof ConfigurationException) {
            throw e;
        }
        throw new ConfigurationException(
                "dialect '" + dialect.id() + "' failed its supports check", e);
    }
}

function productName(metaData: DatabaseMetadata): string {
    return metaData.databaseProductName + " " + metaData.databaseMajorVersion;
}

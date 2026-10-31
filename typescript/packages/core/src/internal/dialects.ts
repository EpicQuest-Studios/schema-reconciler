import type { Dialect } from "../spi/Dialect.ts";

/**
 * The TypeScript replacement for {@code ServiceLoader} discovery (spec 3.4):
 * explicit registration. Applications register dialects once (or pass one
 * directly to {@code Builder.dialect}); the engine selects among the
 * registered candidates by {@code supports} on first use.
 */
const registered: Dialect[] = [];

export function registerDialect(dialect: Dialect): void {
    registered.push(dialect);
}

export function unregisterDialect(dialect: Dialect): void {
    const i = registered.indexOf(dialect);
    if (i >= 0) {
        registered.splice(i, 1);
    }
}

export function registeredDialects(): readonly Dialect[] {
    return registered;
}

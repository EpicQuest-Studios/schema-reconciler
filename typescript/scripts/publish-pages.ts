/**
 * Publishes the workspace packages to a static npm registry served from
 * the gh-pages branch — the npm registry protocol is plain HTTPS GETs:
 *
 *   GET <registry>/<url-encoded name> -> packument JSON (versions map)
 *   GET <dist.tarball>                -> the .tgz
 *
 * so GitHub Pages can host a full registry anonymously — the same
 * mechanism the Maven repository already uses.
 *
 * Layout it writes into the gh-pages checkout:
 *
 *   npm/tarballs/<name>-<version>.tgz        packed artifacts
 *   npm/@scope/<name>                        packument (decoded-path form)
 *   npm/@scope%2f<name>                      packument (literal-encoded form)
 *
 * Both packument file forms are written: npm requests the name with the
 * slash percent-encoded (`@scope%2fname`); whether Pages decodes `%2f`
 * back to a path separator before serving depends on the CDN layer, so
 * covering both makes installs work either way.
 *
 * Usage:
 *
 *   node scripts/publish-pages.ts --pages <checkout-of-gh-pages>
 *
 * Re-running with an existing packument merges the new version into the
 * versions map, so previously published versions stay installable.
 * Afterwards: commit + push the gh-pages branch.
 */

import { execFileSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readFileSync,
        writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const REGISTRY_BASE =
        "https://epicquest-studios.github.io/schema-reconciler/npm";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");

function arg(flag: string): string | undefined {
    const i = process.argv.indexOf(flag);
    return i >= 0 ? process.argv[i + 1] : undefined;
}

const pagesDir = arg("--pages");
if (pagesDir === undefined) {
    console.error("usage: node scripts/publish-pages.ts"
            + " --pages <checkout-of-gh-pages>"
            + " [--registry <public-registry-base-url>]");
    process.exit(2);
}
if (!existsSync(pagesDir)) {
    console.error("gh-pages checkout not found: " + pagesDir);
    process.exit(2);
}
const registryBase = (arg("--registry") ?? REGISTRY_BASE)
        .replace(/\/+$/, "");
const registryDir = join(pagesDir, "npm");
const tarballsDir = join(registryDir, "tarballs");

interface PackInfo {
    name: string;
    version: string;
    shasum: string;
    integrity: string;
    unpackedSize: number;
    filename: string;
}

interface Packument {
    name?: string;
    "dist-tags"?: Record<string, string>;
    versions?: Record<string, unknown>;
    time?: Record<string, string>;
    [key: string]: unknown;
}

/** Numeric semver compare: returns negative when a < b. */
function compareVersions(a: string, b: string): number {
    const pa = a.split(/[.-]/), pb = b.split(/[.-]/);
    for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
        const x = pa[i] ?? "", y = pb[i] ?? "";
        const nx = Number(x), ny = Number(y);
        if (Number.isNaN(nx) || Number.isNaN(ny)) {
            if (x !== y) return x < y ? -1 : 1;
        } else if (nx !== ny) {
            return nx - ny;
        }
    }
    return 0;
}

function pack(packageDir: string): PackInfo {
    const out = execFileSync("npm",
            ["pack", "--json", "--pack-destination", tmpdir()],
            { cwd: packageDir, encoding: "utf8" });
    const byName = JSON.parse(out) as Record<string, PackInfo>;
    const info = Object.values(byName)[0];
    if (info === undefined) {
        throw new Error("npm pack produced no output for " + packageDir);
    }
    return info;
}

function publishPackage(packageDir: string): string {
    const manifest = JSON.parse(
            readFileSync(join(packageDir, "package.json"), "utf8"));
    const info = pack(packageDir);
    const { name, version } = info;
    const unscoped = name.slice(name.indexOf("/") + 1);
    const tarballName = `${unscoped}-${version}.tgz`;

    mkdirSync(tarballsDir, { recursive: true });
    cpSync(join(tmpdir(), info.filename), join(tarballsDir, tarballName));

    manifest.dist = {
        tarball: `${registryBase}/tarballs/${tarballName}`,
        integrity: info.integrity,
        shasum: info.shasum,
        unpackedSize: info.unpackedSize,
    };

    const scope = name.slice(0, name.indexOf("/"));
    const decodedPath = join(registryDir, scope, unscoped);
    const packumentPath = existsSync(decodedPath) ? decodedPath
            : join(registryDir, `${scope}%2f${unscoped}`);
    const packument: Packument = existsSync(packumentPath)
            ? JSON.parse(readFileSync(packumentPath, "utf8"))
            : { name, "dist-tags": {}, versions: {}, time: {} };

    packument.versions ??= {};
    if (packument.versions[version] !== undefined) {
        throw new Error(`${name}@${version} is already published —`
                + " bump the version first");
    }
    packument.versions[version] = manifest;

    const tags = packument["dist-tags"] ??= {};
    const latest = tags.latest;
    if (latest === undefined || compareVersions(latest, version) < 0) {
        tags.latest = version;
    }

    const time = packument.time ??= {};
    const now = new Date().toISOString();
    time.created ??= now;
    time.modified = now;
    time[version] = now;

    const body = JSON.stringify(packument, null, 2) + "\n";
    mkdirSync(dirname(decodedPath), { recursive: true });
    writeFileSync(decodedPath, body);
    writeFileSync(join(registryDir, `${scope}%2f${unscoped}`), body);

    return `${name}@${version} -> npm/tarballs/${tarballName}`
            + ` (latest: ${tags.latest})`;
}

if (!existsSync(join(pagesDir, ".nojekyll"))) {
    writeFileSync(join(pagesDir, ".nojekyll"), "");
    console.log("created .nojekyll (serve files raw, no Jekyll pass)");
}

for (const dir of ["core", "postgresql", "tck"]) {
    console.log(publishPackage(join(root, "packages", dir)));
}
console.log("\nDone. Now commit and push the gh-pages branch, and point"
        + " consumers at:\n  @epicquest-studios:registry="
        + registryBase);

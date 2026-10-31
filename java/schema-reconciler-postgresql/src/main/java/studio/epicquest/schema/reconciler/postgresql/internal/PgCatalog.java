package studio.epicquest.schema.reconciler.postgresql.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import studio.epicquest.schema.reconciler.model.Definition;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectIds;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.spi.Dialect;

/**
 * The catalog snapshot reader of spec A.5. Reads with {@code search_path} bound
 * to the namespace so engine-rendered definitions omit its qualifier; any
 * remaining qualifier is tokenised by the 5.3 normaliser.
 */
public final class PgCatalog {

    private PgCatalog() {
    }

    /** Attribute keys used by the renderer. */
    public static final String CREATE_SQL = "createSql";
    public static final String DROP_SQL = "dropSql";
    public static final String POST_SQL = "postSql";
    public static final String OWNED_SQL = "ownedSql";
    public static final String OPTIONS_SQL = "optionsSql";
    public static final String COLUMN_SQL = "columnSql";
    public static final String ATTNUM = "attnum";
    public static final String NOTNULL = "notnull";
    public static final String DEFAULT = "default";
    public static final String SIG_ARGS = "sigargs";
    /** {@code f} for a function, {@code p} for a procedure (pg_proc.prokind). */
    public static final String PROKIND = "prokind";
    public static final String RETURN_TYPE = "returnType";
    /** Table storage options ({@code fillfactor=70, ...}) for {@code CREATE TABLE ... WITH}. */
    public static final String TABLE_OPTIONS = "tableOptions";
    /** The {@code relreplident} letter of a table. */
    public static final String REPLIDENT = "replident";
    public static final String PARTKEY = "partkey";
    /** The {@code <KEYWORD> ${namespace}.<name>} phrase of a {@code GRANT|REVOKE ... ON} target. */
    public static final String GRANT_TARGET = "grantTarget";
    /** The quoted column name of a column-level grant, parenthesized after
     *  the privilege word ({@code GRANT SELECT ("c") ON TABLE t}). */
    public static final String GRANT_COLUMN = "grantColumn";
    /** A statement prefix ahead of the GRANT/REVOKE verb — the
     *  {@code ALTER DEFAULT PRIVILEGES FOR ROLE "r" IN SCHEMA ${namespace}}
     *  clause of a scoped default-privileges object. */
    public static final String GRANT_PREFIX = "grantPrefix";
    /** Foreign server name and rendered OPTIONS fragment of a FOREIGN_TABLE (A.5). */
    public static final String FT_SERVER = "ftServer";
    public static final String FT_OPTIONS = "ftOptions";
    /** Whether a materialized view currently holds its result rows. */
    public static final String POPULATED = "populated";
    /**
     * The object's complete {@code ${namespace}}-token create-fragment script
     * for {@code dump()} — statements joined by U+0000, which PostgreSQL text
     * can never contain (6.1). Absent when the object has no fragment of its
     * own (folded or unrenderable).
     */
    public static final String DUMP_SQL = "dumpSql";

    public static SchemaSnapshot read(Connection conn, String namespace) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("SET LOCAL search_path = " + PgIdentifiers.quote(namespace));
        }

        final var data = new Data(namespace);
        data.serverMajor = conn.getMetaData().getDatabaseMajorVersion();
        data.loadExtensionOwned(conn);
        data.loadAutoTypeFamily(conn);
        data.loadRelations(conn);
        data.loadColumns(conn);
        data.loadConstraints(conn);
        data.loadIndexes(conn);
        data.loadViews(conn);
        data.loadSequences(conn);
        data.loadRoutines(conn);
        data.loadAggregates(conn);
        data.loadTriggers(conn);
        data.loadEnums(conn);
        data.loadDomains(conn);
        data.loadComposites(conn);
        data.loadRanges(conn);
        data.loadPolicies(conn);
        data.loadStatistics(conn);
        data.loadRules(conn);
        data.loadCollations(conn);
        data.loadConversions(conn);
        data.loadTextSearch(conn);
        data.loadOperators(conn);
        data.loadSecLabels(conn);
        data.loadOther(conn);
        data.loadComments(conn);
        data.loadDependencies(conn);
        data.loadExternalDependents(conn);
        data.computeDumpSql();
        return data.snapshot();
    }

    /** Rows accumulated while reading, then assembled into SchemaObjects. */
    private static final class Data {

        private final String ns;
        private final String qNs;
        private int serverMajor;
        private final Map<ObjectId, Obj> objects = new LinkedHashMap<>();
        private final Map<Long, ObjectId> relByOid = new HashMap<>();
        private final Map<Long, ObjectId> procByOid = new HashMap<>();
        private final Map<Long, ObjectId> conByOid = new HashMap<>();
        /** Backing-index oid → the constraint it backs (pk/unique constraint deps). */
        private final Map<Long, ObjectId> conByIndexOid = new HashMap<>();
        private final Map<Long, ObjectId> typeByOid = new HashMap<>();
        private final Map<Long, ObjectId> polByOid = new HashMap<>();
        private final Map<Long, ObjectId> tgByOid = new HashMap<>();
        private final Map<Long, ObjectId> stxByOid = new HashMap<>();
        private final Map<Long, ObjectId> ruleByOid = new HashMap<>();
        private final Map<Long, ObjectId> convByOid = new HashMap<>();
        private final Map<Long, ObjectId> collByOid = new HashMap<>();
        private final Map<Long, ObjectId> langByOid = new HashMap<>();
        private final Map<Long, ObjectId> tmplByOid = new HashMap<>();
        private final Map<Long, ObjectId> prsByOid = new HashMap<>();
        private final Map<Long, ObjectId> dictByOid = new HashMap<>();
        private final Map<Long, ObjectId> cfgByOid = new HashMap<>();
        private final Map<Long, ObjectId> oprByOid = new HashMap<>();
        private final Map<Long, ObjectId> opcByOid = new HashMap<>();
        private final Map<Long, ObjectId> opfByOid = new HashMap<>();
        /** Catalog-row oid -> the OTHER object it loaded as, per classid, so
         *  an unsupported object that depends on a managed one still records
         *  the edge and blocks an unrecreatable drop (5.8.3). */
        private final Map<String, Map<Long, ObjectId>> otherByOid = new HashMap<>();
        private final Map<ObjectId, Set<ObjectId>> deps = new HashMap<>();
        /** Catalog oids owned by an extension (pg_depend deptype 'e'). Member
         *  objects cannot be created or dropped standalone, so they never
         *  enter the model; the extension itself is reported as OTHER. */
        private final Set<Long> extOwned = new HashSet<>();
        /** A range type's generated members (its multirange and the
         *  constructor routines and {@code range→multirange} cast recorded
         *  with {@code deptype 'i'}) fold into the range like a table's
         *  rowtype — they are never objects, their oids remap to the
         *  {@code TYPE} id so dependents still order after the range. */
        private final Map<Long, Long> autoProcs = new HashMap<>();
        private final Map<Long, Long> autoCasts = new HashMap<>();
        /** Oids that must never resolve to an annotation/acl target: a
         *  comment or grant on a folded member has no renderable target
         *  (GRANT on a multirange is even rejected by the server). */
        private final Set<Long> foldedMembers = new HashSet<>();

        Data(String ns) {
            this.ns = ns;
            this.qNs = PgIdentifiers.quote(ns);
        }

        private Obj obj(ObjectId id) {
            return objects.computeIfAbsent(id, k -> new Obj());
        }

        private void define(ObjectId id, String canonical, Map<String, String> attrs) {
            final var o = obj(id);
            o.def = new Definition(PgSql.normalize(canonical, ns));
            attrs.forEach((k, v) -> o.attrs.put(k, PgSql.normalize(v, ns)));
        }

        private void dep(ObjectId from, ObjectId to) {
            if (from != null && to != null && !to.equals(from)) {
                deps.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
            }
        }

        private void otherOid(String cls, long oid, ObjectId id) {
            otherByOid.computeIfAbsent(cls, k -> new HashMap<>()).put(oid, id);
        }

        /** Resolves a catalog oid to the snapshot object it names, if it is in this namespace. */
        private ObjectId byOid(Long oid) {
            var to = relByOid.get(oid);
            if (to == null) {
                to = procByOid.get(oid);
            }
            if (to == null) {
                to = typeByOid.get(oid);
            }
            if (to == null) {
                to = langByOid.get(oid);
            }
            if (to == null) {
                to = tmplByOid.get(oid);
            }
            if (to == null) {
                to = prsByOid.get(oid);
            }
            if (to == null) {
                to = dictByOid.get(oid);
            }
            if (to == null) {
                to = cfgByOid.get(oid);
            }
            if (to == null) {
                to = oprByOid.get(oid);
            }
            if (to == null) {
                to = opcByOid.get(oid);
            }
            if (to == null) {
                to = opfByOid.get(oid);
            }
            return to;
        }

        private void depOid(ObjectId from, Long refOid) {
            dep(from, byOid(refOid));
        }

        /**
         * Propagates a covered column's dependency up to its parent table so
         * that a {@code CREATE TABLE} change still orders after the referenced
         * object. Skipped when the referenced object already depends on the
         * table — a serial-owned sequence, for example — because a mutual
         * edge would read as a dependency cycle.
         */
        private void depTableForCoveredChild(ObjectId table, ObjectId ref) {
            if (ref != null && !deps.getOrDefault(ref, Set.of()).contains(table)) {
                dep(table, ref);
            }
        }

        // ---- loads ------------------------------------------------------

        private boolean extMember(long oid) {
            return extOwned.contains(oid);
        }

        void loadExtensionOwned(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(
                    // relations, routines and types dominate the member count (a
                    // PostGIS install owns thousands), so only those in this
                    // namespace are read; other classes are few and read whole
                    "SELECT d.objid FROM pg_depend d"
                            + " WHERE d.refclassid = 'pg_extension'::regclass AND d.deptype = 'e'"
                            + " AND CASE d.classid"
                            + " WHEN 'pg_class'::regclass THEN EXISTS (SELECT 1 FROM pg_class o"
                            + "   WHERE o.oid = d.objid AND o.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = ?))"
                            + " WHEN 'pg_proc'::regclass THEN EXISTS (SELECT 1 FROM pg_proc o"
                            + "   WHERE o.oid = d.objid AND o.pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = ?))"
                            + " WHEN 'pg_type'::regclass THEN EXISTS (SELECT 1 FROM pg_type o"
                            + "   WHERE o.oid = d.objid AND o.typnamespace = (SELECT oid FROM pg_namespace WHERE nspname = ?))"
                            + " ELSE true END")) {
                ps.setString(1, ns);
                ps.setString(2, ns);
                ps.setString(3, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        extOwned.add(rs.getLong(1));
                    }
                }
            }
        }

        /**
         * A range type's generated family: the {@code typtype 'm'}
         * multirange, the constructor routines and the
         * {@code range→multirange} cast, all internally bound to the range
         * ({@code pg_depend deptype 'i'}). They fold into the managed
         * {@code TYPE} like a table's rowtype — never objects of their own;
         * their oids remap to the range so dependents and annotations on
         * the family still resolve. Runs before every other type and
         * routine loader so the oid maps are populated early.
         */
        void loadAutoTypeFamily(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT t.oid, t.typname, r.rngmultitypid
                    FROM pg_range r
                    JOIN pg_type t ON t.oid = r.rngtypid
                    JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = ?
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var id = new ObjectId(ObjectKind.TYPE,
                                rs.getString("typname"));
                        typeByOid.put(oid, id);
                        final var mr = rs.getLong("rngmultitypid");
                        typeByOid.put(mr, id);
                        foldedMembers.add(mr);
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT d.objid, coalesce(rg.rngtypid, d.refobjid) AS rng
                    FROM pg_depend d
                    JOIN pg_type t ON t.oid = d.refobjid
                    JOIN pg_namespace n ON n.oid = t.typnamespace
                    LEFT JOIN pg_range rg ON rg.rngmultitypid = d.refobjid
                    WHERE n.nspname = ? AND t.typtype IN ('r','m')
                      AND d.classid = 'pg_proc'::regclass
                      AND d.refclassid = 'pg_type'::regclass
                      AND d.deptype = 'i'
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        autoProcs.put(rs.getLong("objid"), rs.getLong("rng"));
                        foldedMembers.add(rs.getLong("objid"));
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT d.objid, coalesce(rg.rngtypid, d.refobjid) AS rng
                    FROM pg_depend d
                    JOIN pg_type t ON t.oid = d.refobjid
                    JOIN pg_namespace n ON n.oid = t.typnamespace
                    LEFT JOIN pg_range rg ON rg.rngmultitypid = d.refobjid
                    WHERE n.nspname = ? AND t.typtype IN ('r','m')
                      AND d.classid = 'pg_cast'::regclass
                      AND d.refclassid = 'pg_type'::regclass
                      AND d.deptype = 'i'
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        autoCasts.put(rs.getLong("objid"), rs.getLong("rng"));
                        foldedMembers.add(rs.getLong("objid"));
                    }
                }
            }
        }

        void loadRelations(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT c.oid, c.relname, c.relkind, c.relpersistence,
                           c.reloptions::text AS options, c.relreplident,
                           c.relrowsecurity, c.relforcerowsecurity,
                           pg_get_partkeydef(c.oid) AS partkey,
                           pg_get_expr(c.relpartbound, c.oid) AS partbound,
                           (SELECT p.relname FROM pg_inherits i
                              JOIN pg_class p ON p.oid = i.inhparent
                             WHERE i.inhrelid = c.oid AND p.relkind = 'p'
                             LIMIT 1) AS pparent,
                           (SELECT string_agg(p.relkind, '') FROM pg_inherits i
                              JOIN pg_class p ON p.oid = i.inhparent
                             WHERE i.inhrelid = c.oid) AS parentkinds,
                           (SELECT ic.relname FROM pg_index ix JOIN pg_class ic ON ic.oid = ix.indexrelid
                             WHERE ix.indrelid = c.oid AND ix.indisreplident) AS replindex,
                           (SELECT t.oid FROM pg_type t
                             WHERE t.typrelid = c.oid) AS typeoid,
                           s.srvname AS ftserver, f.ftoptions::text AS ftoptions
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    LEFT JOIN pg_foreign_table f ON f.ftrelid = c.oid
                    LEFT JOIN pg_foreign_server s ON s.oid = f.ftserver
                    WHERE n.nspname = ? AND c.relkind IN ('r','v','m','f','p','c','t','I','S')
                    ORDER BY c.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var name = rs.getString("relname");
                        final var kind = rs.getString("relkind");
                        final var persistence = rs.getString("relpersistence");
                        final var options = reloptions(rs.getString("options"));
                        final var parentkinds = nullToEmpty(rs.getString("parentkinds"));
                        final var inherited = !parentkinds.isEmpty();
                        // a plain table whose parents are also plain tables is a
                        // classic-inheritance child — not faithfully creatable
                        // as a standalone TABLE, so it stays OTHER. A partition
                        // child ('r' under a 'p' parent) is managed as PARTITION
                        // parented on the partitioned table; the 'p' parent
                        // itself is a TABLE whose canonical carries the key.
                        final var id = switch (kind) {
                            case "r" -> !inherited ? new ObjectId(ObjectKind.TABLE, name)
                                    : parentkinds.contains("p")
                                            ? new ObjectId(ObjectKind.PARTITION, name,
                                                    rs.getString("pparent"))
                                            : new ObjectId(ObjectKind.OTHER,
                                                    "inherited:" + name);
                            case "v" -> new ObjectId(ObjectKind.VIEW, name);
                            case "S" -> new ObjectId(ObjectKind.SEQUENCE, name);
                            // managed: canonical definition lands in loadViews
                            case "m" -> new ObjectId(ObjectKind.MATERIALIZED_VIEW, name);
                            // a 'p' under a 'p' parent is a partition that is
                            // itself partitioned — a PARTITION whose create
                            // carries both the bound and the sub-partition key
                            case "p" -> !inherited ? new ObjectId(ObjectKind.TABLE, name)
                                    : parentkinds.contains("p")
                                            ? new ObjectId(ObjectKind.PARTITION, name,
                                                    rs.getString("pparent"))
                                            : new ObjectId(ObjectKind.OTHER,
                                                    "inherited:" + name);
                            case "f" -> new ObjectId(ObjectKind.FOREIGN_TABLE, name);
                            // a documented category prefix per relkind (3.3):
                            // tolerating one non-managed relation kind must
                            // not exempt the others
                            case "I" -> new ObjectId(ObjectKind.OTHER, "partindex:" + name);
                            // a standalone composite's 'c' row is the type's
                            // backing — the managed TYPE object claims it in
                            // loadComposites
                            case "c" -> null;
                            case "t" -> new ObjectId(ObjectKind.OTHER, "toast:" + name);
                            default -> new ObjectId(ObjectKind.OTHER, "relation:" + name);
                        };
                        if (id == null) {
                            continue;
                        }
                        relByOid.put(oid, id);
                        // the companion composite rowtype — a column typed as
                        // the relation resolves the pg_type oid, so it maps to
                        // the same object (OTHER rowtypes included: an
                        // inherited child's rowtype is equally unsupported)
                        final var typeoid = rs.getLong("typeoid");
                        if (typeoid != 0) {
                            typeByOid.putIfAbsent(typeoid, id);
                            if (id.kind() == ObjectKind.OTHER) {
                                otherOid("pg_type", typeoid, id);
                            }
                        }
                        if (id.kind() == ObjectKind.TABLE) {
                            final var replident = rs.getString("relreplident");
                            final var replindex = rs.getString("replindex");
                            final var partkey = rs.getString("partkey");
                            final var attrs = new LinkedHashMap<String, String>();
                            attrs.put(TABLE_OPTIONS, String.join(", ", options));
                            attrs.put(REPLIDENT, replident);
                            if (partkey != null) {
                                attrs.put(PARTKEY, partkey);
                            }
                            define(id, "relkind=" + kind + ";relpersistence=" + persistence
                                    + ";options=" + options
                                    + ";replident=" + replident
                                    + (replindex != null ? ";replindex=" + replindex : "")
                                    + ";rls=" + rs.getBoolean("relrowsecurity")
                                    + ";forcerls=" + rs.getBoolean("relforcerowsecurity")
                                    + (partkey != null ? ";partkey=" + partkey : ""),
                                    attrs);
                        } else if (id.kind() == ObjectKind.PARTITION) {
                            final var bound = rs.getString("partbound");
                            final var partkey = rs.getString("partkey");
                            final var qName = tableRef(name);
                            define(id, "bound=" + bound
                                            + (partkey != null
                                                    ? ";partkey=" + partkey : ""),
                                    Map.of(
                                            CREATE_SQL, "CREATE TABLE " + qName
                                                    + " PARTITION OF "
                                                    + tableRef(id.parent().get())
                                                    + " " + bound
                                                    + (partkey != null
                                                            ? " PARTITION BY "
                                                                    + partkey
                                                            : ""),
                                            DROP_SQL, "DROP TABLE " + qName));
                        } else if (id.kind() == ObjectKind.FOREIGN_TABLE) {
                            // the server name is a cluster object reference
                            // (4.1): it lives inside the canonical so drift on
                            // it reconciles the table, never the server
                            final var server = rs.getString("ftserver");
                            final var ftoptions = reloptions(rs.getString("ftoptions"));
                            final var optionSql = ftoptions.stream()
                                    .map(o -> {
                                        final var eq = o.indexOf('=');
                                        return o.substring(0, eq) + " "
                                                + sqlLiteral(o.substring(eq + 1));
                                    })
                                    .collect(Collectors.joining(", "));
                            final var attrs = new LinkedHashMap<String, String>();
                            attrs.put(FT_SERVER, server);
                            attrs.put(FT_OPTIONS, optionSql);
                            attrs.put(DROP_SQL, "DROP FOREIGN TABLE " + tableRef(name));
                            define(id, "server=" + server
                                    + ";ftoptions=" + String.join(", ", ftoptions)
                                    + ";options=" + options,
                                    attrs);
                        } else if (id.kind() == ObjectKind.OTHER) {
                            define(id, "relkind=" + kind + (inherited ? ";inherited" : ""),
                                    Map.of());
                        }
                    }
                }
            }
        }

        void loadColumns(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT c.relname AS parent, c.oid AS reloid, a.attnum,
                           a.attname, format_type(a.atttypid, a.atttypmod) AS typ,
                           a.attgenerated, a.attcollation, t.typcollation, a.attidentity,
                           a.attstorage, t.typstorage, a.attcompression,
                           co.collname,
                           (a.attnotnull OR EXISTS (
                               SELECT 1 FROM pg_constraint cn
                               WHERE cn.conrelid = a.attrelid AND cn.contype = 'n'
                                 AND a.attnum = ANY(cn.conkey))) AS notnull,
                           pg_get_expr(d.adbin, d.adrelid) AS defexpr,
                           format_type(ids.seqtypid, NULL) AS idtype,
                           ids.seqincrement, ids.seqmin, ids.seqmax,
                           ids.seqstart, ids.seqcache, ids.seqcycle
                    FROM pg_attribute a
                    JOIN pg_class c ON c.oid = a.attrelid AND c.relkind IN ('r', 'p', 'f')
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_type t ON t.oid = a.atttypid
                    LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                    LEFT JOIN pg_collation co ON co.oid = a.attcollation
                    LEFT JOIN LATERAL (
                        SELECT ps.* FROM pg_depend dep
                        JOIN pg_class sq ON sq.oid = dep.objid AND sq.relkind = 'S'
                        JOIN pg_sequence ps ON ps.seqrelid = sq.oid
                        WHERE dep.classid = 'pg_class'::regclass
                          AND dep.refclassid = 'pg_class'::regclass
                          AND dep.refobjid = a.attrelid
                          AND dep.refobjsubid = a.attnum
                          AND dep.deptype = 'i'
                    ) ids ON true
                    WHERE n.nspname = ? AND a.attnum > 0 AND NOT a.attisdropped
                    ORDER BY c.oid, a.attnum
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("reloid"))) {
                            continue;
                        }
                        final var parent = rs.getString("parent");
                        final var name = rs.getString("attname");
                        final var type = rs.getString("typ");
                        final var notnull = rs.getBoolean("notnull");
                        final var generated = rs.getString("attgenerated");
                        final var identity = nullToEmpty(rs.getString("attidentity"));
                        final var defexpr = rs.getString("defexpr");
                        final var collname = rs.getString("collname");
                        final var customCollation = rs.getLong("attcollation") != 0
                                && rs.getLong("attcollation") != rs.getLong("typcollation");
                        final var storage = rs.getString("attstorage");
                        final var compression = nullToEmpty(rs.getString("attcompression"));

                        final var canonical = new StringBuilder("type=").append(type)
                                .append(";notnull=").append(notnull);
                        final var sql = new StringBuilder()
                                .append(PgIdentifiers.quote(name)).append(' ').append(type);
                        // storage/compression precede COLLATE in the column-def
                        // grammar; emitted only when they deviate from the
                        // type default (like a custom collation)
                        if (!storage.equals(rs.getString("typstorage"))) {
                            sql.append(" STORAGE ").append(switch (storage) {
                                case "e" -> "EXTERNAL";
                                case "m" -> "MAIN";
                                case "p" -> "PLAIN";
                                default -> "EXTENDED";
                            });
                        }
                        if (!compression.isEmpty()) {
                            sql.append(" COMPRESSION ")
                                    .append(compression.equals("l") ? "lz4" : "pglz");
                        }
                        if (customCollation && collname != null) {
                            canonical.append(";collation=").append(collname);
                            sql.append(" COLLATE ").append(PgIdentifiers.quote(collname));
                        }
                        final var attrs = new LinkedHashMap<String, String>();
                        attrs.put(ATTNUM, rs.getString("attnum"));
                        attrs.put(NOTNULL, Boolean.toString(notnull));
                        if (!generated.isEmpty()) {
                            canonical.append(";generated=").append(generated)
                                    .append(":").append(defexpr);
                            sql.append(" GENERATED ALWAYS AS (").append(defexpr).append(") ")
                                    .append(generated.equals("v") ? "VIRTUAL" : "STORED");
                        } else if (!identity.isEmpty()) {
                            final var sqlOptions = identitySqlOptions(rs);
                            canonical.append(";identity=").append(identity)
                                    .append(";identityOptions=").append(identityCanonical(rs));
                            sql.append(" GENERATED ")
                                    .append(identity.equals("a") ? "ALWAYS" : "BY DEFAULT")
                                    .append(" AS IDENTITY");
                            if (!sqlOptions.isEmpty()) {
                                sql.append(" (").append(sqlOptions).append(')');
                            }
                        } else if (defexpr != null) {
                            canonical.append(";default=").append(defexpr);
                            sql.append(" DEFAULT ").append(defexpr);
                            attrs.put(DEFAULT, defexpr);
                        }
                        if (notnull) {
                            sql.append(" NOT NULL");
                        }
                        canonical.append(";storage=").append(storage)
                                .append(";compression=").append(compression);
                        attrs.put(COLUMN_SQL, sql.toString());
                        define(new ObjectId(ObjectKind.COLUMN, name, parent),
                                canonical.toString(), attrs);
                    }
                }
            }
        }

        private String identitySqlOptions(ResultSet rs) throws SQLException {
            if (rs.getString("seqincrement") == null) {
                return "";
            }
            return "INCREMENT BY " + rs.getLong("seqincrement")
                    + " MINVALUE " + rs.getLong("seqmin")
                    + " MAXVALUE " + rs.getLong("seqmax")
                    + " START WITH " + rs.getLong("seqstart")
                    + " CACHE " + rs.getLong("seqcache")
                    + (rs.getBoolean("seqcycle") ? " CYCLE" : " NO CYCLE");
        }

        private String identityCanonical(ResultSet rs) throws SQLException {
            if (rs.getString("seqincrement") == null) {
                return "";
            }
            return "type=" + rs.getString("idtype")
                    + ";increment=" + rs.getLong("seqincrement")
                    + ";min=" + rs.getLong("seqmin")
                    + ";max=" + rs.getLong("seqmax")
                    + ";start=" + rs.getLong("seqstart")
                    + ";cache=" + rs.getLong("seqcache")
                    + ";cycle=" + rs.getBoolean("seqcycle");
        }

        void loadConstraints(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT c.oid, c.conname, c.contype, rel.relname AS parent,
                           pg_get_constraintdef(c.oid) AS def,
                           ref.oid AS refoid, c.conindid,
                           EXISTS (SELECT 1 FROM pg_index ix
                                    WHERE ix.indexrelid = c.conindid AND ix.indisreplident
                                      AND c.contype IN ('p','u')) AS replident
                    FROM pg_constraint c
                    JOIN pg_class rel ON rel.oid = c.conrelid
                    JOIN pg_namespace n ON n.oid = rel.relnamespace
                    LEFT JOIN pg_class ref ON ref.oid = c.confrelid
                    WHERE n.nspname = ? AND c.contype IN ('p','u','f','c','x')
                      AND c.conislocal
                    ORDER BY c.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        final var contype = rs.getString("contype");
                        final var kind = switch (contype) {
                            case "p" -> ObjectKind.PRIMARY_KEY;
                            case "u" -> ObjectKind.UNIQUE;
                            case "f" -> ObjectKind.FOREIGN_KEY;
                            case "c" -> ObjectKind.CHECK;
                            case "x" -> ObjectKind.EXCLUSION;
                            default -> ObjectKind.OTHER;
                        };
                        final var name = rs.getString("conname");
                        final var parent = rs.getString("parent");
                        final var def = rs.getString("def");
                        final var id = kind == ObjectKind.OTHER
                                ? new ObjectId(kind, "constraint:" + parent + "." + name)
                                : new ObjectId(kind, name, parent);
                        conByOid.put(rs.getLong("oid"), id);
                        final var conindid = rs.getLong("conindid");
                        // conindid is the backing index for p/u/x only; on an
                        // FK it is the referenced index on the other table.
                        if (conindid != 0 && kind != ObjectKind.FOREIGN_KEY
                                && kind != ObjectKind.CHECK) {
                            conByIndexOid.put(conindid, id);
                        }
                        final var qParent = tableRef(parent);
                        final var qName = PgIdentifiers.quote(name);
                        final var attrs = new LinkedHashMap<String, String>();
                        attrs.put(CREATE_SQL, "ALTER TABLE " + qParent
                                + " ADD CONSTRAINT " + qName + " " + def);
                        attrs.put(DROP_SQL, "ALTER TABLE " + qParent
                                + " DROP CONSTRAINT " + qName);
                        if (rs.getBoolean("replident")) {
                            attrs.put(POST_SQL, "ALTER TABLE " + qParent
                                    + " REPLICA IDENTITY USING INDEX " + qName);
                        }
                        define(id, def, attrs);
                        final var refoid = rs.getLong("refoid");
                        if (!rs.wasNull()) {
                            depOid(id, refoid);
                        }
                    }
                }
            }
        }

        void loadIndexes(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT i.indexrelid AS oid, ic.relname, tc.relname AS parent,
                           pg_get_indexdef(i.indexrelid) AS def, i.indisreplident AS replident
                    FROM pg_index i
                    JOIN pg_class ic ON ic.oid = i.indexrelid
                    JOIN pg_class tc ON tc.oid = i.indrelid
                    JOIN pg_namespace n ON n.oid = ic.relnamespace
                    WHERE n.nspname = ? AND ic.relkind = 'i'
                      AND NOT EXISTS (SELECT 1 FROM pg_constraint co
                                      WHERE co.conindid = i.indexrelid
                                        AND co.contype IN ('p','u','x'))
                      AND NOT EXISTS (SELECT 1 FROM pg_inherits inh WHERE inh.inhrelid = i.indexrelid)
                    ORDER BY i.indexrelid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        final var name = rs.getString("relname");
                        final var parent = rs.getString("parent");
                        final var def = rs.getString("def");
                        final var id = new ObjectId(ObjectKind.INDEX, name, parent);
                        relByOid.put(rs.getLong("oid"), id);
                        final var attrs = new LinkedHashMap<String, String>();
                        attrs.put(CREATE_SQL, def);
                        attrs.put(DROP_SQL, "DROP INDEX " + qNs + "." + PgIdentifiers.quote(name));
                        if (rs.getBoolean("replident")) {
                            attrs.put(POST_SQL, "ALTER TABLE " + tableRef(parent)
                                    + " REPLICA IDENTITY USING INDEX " + PgIdentifiers.quote(name));
                        }
                        define(id, def, attrs);
                    }
                }
            }
        }

        void loadViews(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT c.oid, c.relname, c.relkind, c.relpersistence, c.relispopulated,
                           pg_get_viewdef(c.oid, true) AS def,
                           c.reloptions::text AS options,
                           (SELECT string_agg(a.attname || ' ' || format_type(a.atttypid, a.atttypmod),
                                              ', ' ORDER BY a.attnum)
                              FROM pg_attribute a
                             WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped) AS cols
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relkind IN ('v','m')
                    ORDER BY c.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        final var name = rs.getString("relname");
                        final var def = rs.getString("def");
                        final var cols = nullToEmpty(rs.getString("cols"));
                        final var options = reloptions(rs.getString("options"));
                        final var qName = qNs + "." + PgIdentifiers.quote(name);
                        if (rs.getString("relkind").equals("m")) {
                            final var populated = rs.getBoolean("relispopulated");
                            final var attrs = new LinkedHashMap<String, String>();
                            attrs.put(CREATE_SQL, "CREATE "
                                    + (rs.getString("relpersistence").equals("u") ? "UNLOGGED " : "")
                                    + "MATERIALIZED VIEW " + qName
                                    + (options.isEmpty() ? "" : " WITH (" + String.join(",", options) + ")")
                                    + " AS " + def);
                            attrs.put(DROP_SQL, "DROP MATERIALIZED VIEW " + qName);
                            attrs.put(POPULATED, Boolean.toString(populated));
                            define(new ObjectId(ObjectKind.MATERIALIZED_VIEW, name),
                                    "viewdef=" + def + ";cols=" + cols + ";options=" + options
                                            + ";relpersistence=" + rs.getString("relpersistence")
                                            + ";populated=" + populated,
                                    attrs);
                        } else {
                            final var id = new ObjectId(ObjectKind.VIEW, name);
                            final var create = "CREATE VIEW " + qName
                                    + (options.isEmpty() ? "" : " WITH (" + String.join(",", options) + ")")
                                    + " AS " + def;
                            define(id, "viewdef=" + def + ";cols=" + cols + ";options=" + options,
                                    Map.of(CREATE_SQL, create, DROP_SQL, "DROP VIEW " + qName));
                        }
                    }
                }
            }
        }

        void loadSequences(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT c.oid, c.relname, c.relpersistence,
                           format_type(s.seqtypid, NULL) AS stype,
                           s.seqincrement, s.seqmin, s.seqmax, s.seqstart, s.seqcache, s.seqcycle
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_sequence s ON s.seqrelid = c.oid
                    WHERE n.nspname = ? AND c.relkind = 'S'
                      AND NOT EXISTS (
                          SELECT 1 FROM pg_depend d
                          WHERE d.classid = 'pg_class'::regclass
                            AND d.refclassid = 'pg_class'::regclass
                            AND d.objid = c.oid AND d.deptype = 'i')
                    ORDER BY c.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        final var name = rs.getString("relname");
                        final var id = new ObjectId(ObjectKind.SEQUENCE, name);
                        final var stype = rs.getString("stype");
                        final var inc = rs.getLong("seqincrement");
                        final var min = rs.getLong("seqmin");
                        final var max = rs.getLong("seqmax");
                        final var start = rs.getLong("seqstart");
                        final var cache = rs.getLong("seqcache");
                        final var cycle = rs.getBoolean("seqcycle");
                        final var options = "AS " + stype + " INCREMENT BY " + inc
                                + " MINVALUE " + min + " MAXVALUE " + max
                                + " START " + start + " CACHE " + cache
                                + (cycle ? " CYCLE" : " NO CYCLE");
                        final var persistence = rs.getString("relpersistence");
                        final var qSeq = qNs + "." + PgIdentifiers.quote(name);
                        final var attrs = new LinkedHashMap<String, String>();
                        attrs.put(OPTIONS_SQL, options);
                        attrs.put(CREATE_SQL, "CREATE "
                                + (persistence.equals("u") ? "UNLOGGED " : "")
                                + "SEQUENCE " + qSeq + " " + options);
                        attrs.put(DROP_SQL, "DROP SEQUENCE " + qSeq);
                        seqOptions.put(id, "type=" + stype + ";increment=" + inc + ";min=" + min
                                + ";max=" + max + ";start=" + start + ";cache=" + cache
                                + ";cycle=" + cycle + ";relpersistence=" + persistence);
                        // the canonical needs ownership, resolved by the
                        // second query — attrs accumulate here, define() runs
                        // (and normalizes them) once below
                        obj(id).attrs.putAll(attrs);
                    }
                }
            }
            // serial-style ownership (deptype 'a')
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT dep.objid AS seq_oid, dep.refobjid AS tbl_oid,
                           rc.relname AS tbl, ra.attname AS col,
                           rn.nspname AS tblns
                    FROM pg_depend dep
                    JOIN pg_class sq ON sq.oid = dep.objid AND sq.relkind = 'S'
                    JOIN pg_namespace sn ON sn.oid = sq.relnamespace
                    JOIN pg_class rc ON rc.oid = dep.refobjid
                    JOIN pg_namespace rn ON rn.oid = rc.relnamespace
                    JOIN pg_attribute ra ON ra.attrelid = dep.refobjid AND ra.attnum = dep.refobjsubid
                    WHERE dep.classid = 'pg_class'::regclass
                      AND dep.refclassid = 'pg_class'::regclass
                      AND dep.deptype = 'a' AND sn.nspname = ?
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var seq = relByOid.get(rs.getLong("seq_oid"));
                        if (seq == null) {
                            continue;
                        }
                        final var tbl = rs.getString("tbl");
                        final var col = rs.getString("col");
                        final var sameNs = ns.equals(rs.getString("tblns"));
                        final var ownedBy = (sameNs ? "${namespace}" : PgIdentifiers.quote(rs.getString("tblns")))
                                + "." + PgIdentifiers.quote(tbl) + "." + PgIdentifiers.quote(col);
                        seqOwnedBy.put(seq, ownedBy);
                        if (sameNs) {
                            // the owner may be a PARTITION or an
                            // inherited: OTHER, not always a TABLE
                            dep(seq, relByOid.getOrDefault(
                                    rs.getLong("tbl_oid"),
                                    new ObjectId(ObjectKind.TABLE, tbl)));
                        }
                    }
                }
            }
            // finish sequence canonicals now that ownership is known
            seqOptions.forEach((id, canonical) -> {
                final var ownedBy = seqOwnedBy.get(id);
                final var def = new StringBuilder(canonical);
                if (ownedBy != null) {
                    def.append(";owned_by=").append(ownedBy);
                }
                final var attrs = new LinkedHashMap<String, String>();
                attrs.put(OPTIONS_SQL, objects.get(id).attrs.get(OPTIONS_SQL));
                attrs.put(CREATE_SQL, objects.get(id).attrs.get(CREATE_SQL));
                attrs.put(DROP_SQL, objects.get(id).attrs.get(DROP_SQL));
                if (ownedBy != null) {
                    attrs.put(OWNED_SQL, "ALTER SEQUENCE " + seqRef(id) + " OWNED BY " + ownedBy);
                }
                define(id, def.toString(), attrs);
            });
        }

        private final Map<ObjectId, String> seqOptions = new HashMap<>();
        private final Map<ObjectId, String> seqOwnedBy = new HashMap<>();

        void loadRoutines(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT p.oid, p.proname, p.prokind,
                           coalesce((SELECT string_agg(format_type(a, NULL), ', '
                                                     ORDER BY x.i)
                                       FROM unnest(string_to_array(p.proargtypes::text, ' ')::oid[])
                                            WITH ORDINALITY AS x(a, i)), '') AS sigargs,
                           (CASE WHEN p.proretset THEN 'SETOF ' ELSE '' END)
                               || format_type(p.prorettype, NULL) AS rettype,
                           pg_get_functiondef(p.oid) AS def
                    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = ? AND p.prokind IN ('f','p','w')
                    ORDER BY p.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        if (autoProcs.containsKey(oid)) {
                            // a range/multirange constructor folds into its
                            // range type — dependents resolve its oid to the
                            // TYPE object
                            procByOid.put(oid, typeByOid.get(autoProcs.get(oid)));
                            continue;
                        }
                        final var proname = rs.getString("proname");
                        final var prokind = rs.getString("prokind");
                        final var sigargs = rs.getString("sigargs");
                        final var rettype = rs.getString("rettype");
                        final var def = rs.getString("def");
                        final var id = new ObjectId(ObjectKind.ROUTINE,
                                PgSql.normalize(proname + "(" + sigargs + ")", ns));
                        procByOid.put(rs.getLong("oid"), id);
                        final var qName = qNs + "." + PgIdentifiers.quote(proname);
                        final var dropWord = prokind.equals("p") ? "PROCEDURE" : "FUNCTION";
                        define(id, def, Map.of(
                                CREATE_SQL, def,
                                DROP_SQL, "DROP " + dropWord + " " + qName + "(" + sigargs + ")",
                                SIG_ARGS, sigargs,
                                PROKIND, prokind,
                                RETURN_TYPE, rettype));
                    }
                }
            }
        }

        /**
         * Aggregates ({@code prokind 'a'}): {@code pg_get_functiondef}
         * refuses them, so the canonical definition is composed from the
         * {@code pg_aggregate} row — every option that can appear between
         * the parentheses, in a fixed order, with {@code null}/default
         * fields omitted. The same option list renders {@code CREATE
         * AGGREGATE}; drift recreates ({@code DROP AGGREGATE} +
         * {@code CREATE AGGREGATE}) because aggregates have no
         * {@code CREATE OR REPLACE} form. Ordered-set and hypothetical-set
         * aggregates ride the same path — {@code ORDER BY} inside the
         * signature and the {@code HYPOTHETICAL} flag carry their
         * difference.
         */
        void loadAggregates(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT p.oid, p.proname,
                           pg_get_function_identity_arguments(p.oid) AS idargs,
                           pg_get_function_arguments(p.oid) AS args,
                           a.aggkind,
                           a.aggtransfn::regprocedure::text AS sfunc,
                           CASE WHEN a.aggtranstype = 0 THEN 'internal'
                                ELSE format_type(a.aggtranstype, NULL) END AS stype,
                           NULLIF(a.aggtransspace, 0) AS sspace,
                           NULLIF(a.aggfinalfn, 0)::regprocedure::text AS ffunc,
                           a.aggfinalextra, a.aggfinalmodify,
                           NULLIF(a.aggcombinefn, 0)::regprocedure::text AS combine,
                           NULLIF(a.aggserialfn, 0)::regprocedure::text AS serial,
                           NULLIF(a.aggdeserialfn, 0)::regprocedure::text AS deserial,
                           a.agginitval,
                           NULLIF(a.aggmtransfn, 0)::regprocedure::text AS msfunc,
                           NULLIF(a.aggminvtransfn, 0)::regprocedure::text AS minv,
                           CASE WHEN a.aggmtransfn::text = '-' THEN NULL
                                WHEN a.aggmtranstype = 0 THEN 'internal'
                                ELSE format_type(a.aggmtranstype, NULL) END AS mstype,
                           NULLIF(a.aggmtransspace, 0) AS msspace,
                           NULLIF(a.aggmfinalfn, 0)::regprocedure::text AS mffunc,
                           a.aggmfinalextra, a.aggmfinalmodify,
                           a.aggminitval,
                           NULLIF(a.aggsortop, 0)::regoper::text AS sortop
                    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    JOIN pg_aggregate a ON a.aggfnoid = p.oid
                    WHERE n.nspname = ? AND p.prokind = 'a'
                    ORDER BY p.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid) || autoProcs.containsKey(oid)) {
                            continue;
                        }
                        final var proname = rs.getString("proname");
                        final var idargs = rs.getString("idargs");
                        final var options = new ArrayList<String>();
                        final var kind = rs.getString("aggkind");
                        if ("h".equals(kind)) {
                            options.add("HYPOTHETICAL");
                        }
                        options.add("SFUNC = " + rs.getString("sfunc"));
                        options.add("STYPE = " + rs.getString("stype"));
                        aggOpt(options, "SSPACE", rs.getString("sspace"));
                        aggOpt(options, "FINALFUNC", rs.getString("ffunc"));
                        if (rs.getBoolean("aggfinalextra")) {
                            options.add("FINALFUNC_EXTRA");
                        }
                        aggModify(options, "FINALFUNC_MODIFY",
                                rs.getString("aggfinalmodify"));
                        aggOpt(options, "COMBINEFUNC", rs.getString("combine"));
                        aggOpt(options, "SERIALFUNC", rs.getString("serial"));
                        aggOpt(options, "DESERIALFUNC", rs.getString("deserial"));
                        if (rs.getString("agginitval") != null) {
                            options.add("INITCOND = '"
                                    + rs.getString("agginitval")
                                            .replace("'", "''")
                                    + "'");
                        }
                        aggOpt(options, "MSFUNC", rs.getString("msfunc"));
                        aggOpt(options, "MINVFUNC", rs.getString("minv"));
                        aggOpt(options, "MSTYPE", rs.getString("mstype"));
                        aggOpt(options, "MSSPACE", rs.getString("msspace"));
                        aggOpt(options, "MFINALFUNC", rs.getString("mffunc"));
                        if (rs.getBoolean("aggmfinalextra")) {
                            options.add("MFINALFUNC_EXTRA");
                        }
                        aggModify(options, "MFINALFUNC_MODIFY",
                                rs.getString("aggmfinalmodify"));
                        if (rs.getString("aggminitval") != null) {
                            options.add("MINITCOND = '"
                                    + rs.getString("aggminitval")
                                            .replace("'", "''")
                                    + "'");
                        }
                        if (rs.getString("sortop") != null) {
                            options.add("SORTOP = OPERATOR("
                                    + rs.getString("sortop") + ")");
                        }
                        final var opts = String.join(", ", options);
                        final var id = new ObjectId(ObjectKind.AGGREGATE,
                                PgSql.normalize(
                                        proname + "(" + idargs + ")", ns));
                        procByOid.put(oid, id);
                        final var qName = qNs + "." + PgIdentifiers.quote(proname);
                        define(id, "prokind=a;kind=" + kind + ";" + opts,
                                Map.of(
                                        CREATE_SQL, "CREATE AGGREGATE " + qName
                                                + "(" + rs.getString("args") + ")"
                                                + " (" + opts + ")",
                                        DROP_SQL, "DROP AGGREGATE " + qName
                                                + "(" + idargs + ")",
                                        SIG_ARGS, idargs,
                                        PROKIND, "a"));
                    }
                }
            }
        }

        /** Adds a {@code KEY = value} aggregate option when non-null. */
        private static void aggOpt(List<String> options, String key, String value) {
            if (value != null) {
                options.add(key + " = " + value);
            }
        }

        /** {@code FINALFUNC_MODIFY}/{@code MFINALFUNC_MODIFY} — {@code 'r'}
         *  (read-only) is the default and stays implicit. */
        private static void aggModify(List<String> options, String key, String value) {
            switch (value) {
                case "w" -> options.add(key + " = READ_WRITE");
                case "s" -> options.add(key + " = SHAREABLE");
                default -> { }
            }
        }

        void loadTriggers(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT t.oid, t.tgname, c.relname AS parent, t.tgenabled,
                           pg_get_triggerdef(t.oid, true) AS def, t.tgfoid
                    FROM pg_trigger t
                    JOIN pg_class c ON c.oid = t.tgrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND NOT t.tgisinternal AND t.tgparentid = 0
                    ORDER BY t.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        final var name = rs.getString("tgname");
                        final var parent = rs.getString("parent");
                        final var enabled = rs.getString("tgenabled");
                        final var def = rs.getString("def");
                        final var id = new ObjectId(ObjectKind.TRIGGER, name, parent);
                        tgByOid.put(rs.getLong("oid"), id);
                        final var qParent = tableRef(parent);
                        final var qName = PgIdentifiers.quote(name);
                        final var attrs = new LinkedHashMap<String, String>();
                        attrs.put(CREATE_SQL, def);
                        attrs.put(DROP_SQL, "DROP TRIGGER " + qName + " ON " + qParent);
                        if (!enabled.equals("O")) {
                            final var verb = switch (enabled) {
                                case "D" -> "DISABLE";
                                case "R" -> "ENABLE REPLICA";
                                default -> "ENABLE ALWAYS";
                            };
                            attrs.put(POST_SQL, "ALTER TABLE " + qParent + " " + verb
                                    + " TRIGGER " + qName);
                        }
                        define(id, def + ";enabled=" + enabled, attrs);
                        final var func = rs.getLong("tgfoid");
                        depOid(id, func);
                    }
                }
            }
        }

        /** Namespace-scoped catalog rows resolved to a stable display name, for
         *  locating comments and security labels. Every SELECT emits
         *  (classid, objoid, name); the parameter order matches the SELECTs. */
        private static final String NS_OBJECTS = """
                SELECT 'pg_class'::regclass AS cls, c.oid AS obj, c.relname AS name
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
                UNION ALL SELECT 'pg_proc', p.oid,
                        p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')'
                FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace AND n.nspname = ?
                UNION ALL SELECT 'pg_type', t.oid, t.typname
                FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace AND n.nspname = ?
                UNION ALL SELECT 'pg_constraint', c.oid, rel.relname || '.' || c.conname
                FROM pg_constraint c JOIN pg_class rel ON rel.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = rel.relnamespace AND n.nspname = ?
                UNION ALL SELECT 'pg_rewrite', r.oid, c.relname || '.' || r.rulename
                FROM pg_rewrite r JOIN pg_class c ON c.oid = r.ev_class
                JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
                UNION ALL SELECT 'pg_namespace', n.oid, 'namespace'
                FROM pg_namespace n WHERE n.nspname = ?
                UNION ALL SELECT 'pg_policy', p.oid, c.relname || '.' || p.polname
                FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
                UNION ALL SELECT 'pg_collation', cl.oid, cl.collname
                FROM pg_collation cl JOIN pg_namespace n ON n.oid = cl.collnamespace AND n.nspname = ?
                UNION ALL SELECT 'pg_statistic_ext', s.oid, s.stxname
                FROM pg_statistic_ext s JOIN pg_namespace n ON n.oid = s.stxnamespace AND n.nspname = ?
                UNION ALL SELECT 'pg_ts_config', g.oid, g.cfgname
                FROM pg_ts_config g JOIN pg_namespace n ON n.oid = g.cfgnamespace AND n.nspname = ?
                UNION ALL SELECT 'pg_ts_dict', g.oid, g.dictname
                FROM pg_ts_dict g JOIN pg_namespace n ON n.oid = g.dictnamespace AND n.nspname = ?
                UNION ALL SELECT 'pg_ts_parser', g.oid, g.prsname
                FROM pg_ts_parser g JOIN pg_namespace n ON n.oid = g.prsnamespace AND n.nspname = ?
                UNION ALL SELECT 'pg_ts_template', g.oid, g.tmplname
                FROM pg_ts_template g JOIN pg_namespace n ON n.oid = g.tmplnamespace AND n.nspname = ?
                UNION ALL SELECT 'pg_operator', g.oid, g.oprname
                FROM pg_operator g JOIN pg_namespace n ON n.oid = g.oprnamespace AND n.nspname = ?
                UNION ALL SELECT 'pg_opclass', g.oid, g.opcname
                FROM pg_opclass g JOIN pg_namespace n ON n.oid = g.opcnamespace AND n.nspname = ?
                UNION ALL SELECT 'pg_opfamily', g.oid, g.opfname
                FROM pg_opfamily g JOIN pg_namespace n ON n.oid = g.opfnamespace AND n.nspname = ?
                UNION ALL SELECT 'pg_trigger', t.oid, c.relname || '.' || t.tgname
                FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
                """;

        private void bindNamespace(PreparedStatement ps, int count) throws SQLException {
            for (var i = 1; i <= count; i++) {
                ps.setString(i, ns);
            }
        }

        /** Enum types: the canonical definition is the ordered label list;
         *  the {@code typeByOid} entry resolves column and table dependency
         *  edges to the managed {@link ObjectKind#TYPE} object. */
        void loadEnums(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT t.oid, t.typname,
                           (SELECT array_agg(e.enumlabel ORDER BY e.enumsortorder, e.oid)
                              FROM pg_enum e WHERE e.enumtypid = t.oid) AS labels
                    FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = ? AND t.typtype = 'e'
                    ORDER BY t.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var name = rs.getString("typname");
                        final var labels = rs.getArray("labels");
                        final var csv = labels == null
                                ? ""
                                : String.join(",", Arrays.stream(
                                        (String[]) labels.getArray())
                                        .map(Data::sqlLiteral).toList());
                        final var qName = qNs + "." + PgIdentifiers.quote(name);
                        final var id = new ObjectId(ObjectKind.TYPE, name);
                        define(id, "typtype=e;labels=" + csv,
                                Map.of(CREATE_SQL, "CREATE TYPE " + qName + " AS ENUM"
                                                + (csv.isEmpty() ? "" : " (" + csv + ")"),
                                        DROP_SQL, "DROP TYPE " + qName));
                        typeByOid.put(oid, id);
                    }
                }
            }
        }

        /**
         * Domains: the canonical definition is the base type (deparsed by
         * {@code format_type}), the nullability flag, the deparsed default
         * expression and the check constraints by name. The {@code typeByOid}
         * entry resolves column and table dependency edges to the managed
         * {@link ObjectKind#DOMAIN} object; the base type and the checks'
         * referenced routines and types join the dependency graph in
         * {@code loadDependencies}.
         */
        void loadDomains(Connection conn) throws SQLException {
            final var checks = new LinkedHashMap<Long, List<String>>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT c.contypid AS dom, c.conname, pg_get_constraintdef(c.oid) AS def
                    FROM pg_constraint c
                    JOIN pg_type t ON t.oid = c.contypid
                    JOIN pg_namespace n ON n.oid = t.typnamespace AND n.nspname = ?
                    WHERE c.contype = 'c'
                    ORDER BY c.contypid, c.conname
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        checks.computeIfAbsent(rs.getLong("dom"), k -> new ArrayList<>())
                                .add(rs.getString("conname") + "\0" + rs.getString("def"));
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT t.oid, t.typname,
                           format_type(t.typbasetype, t.typtypmod) AS basetype,
                           t.typnotnull, pg_get_expr(t.typdefaultbin, 0) AS dflt,
                           NULLIF(t.typcollation, 0)::regcollation::text AS coll,
                           t.typbasetype
                    FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = ? AND t.typtype = 'd'
                    ORDER BY t.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var name = rs.getString("typname");
                        final var basetype = rs.getString("basetype");
                        final var notnull = rs.getBoolean("typnotnull");
                        final var dflt = rs.getString("dflt");
                        final var collation = rs.getString("coll");
                        final var qName = qNs + "." + PgIdentifiers.quote(name);
                        final var id = new ObjectId(ObjectKind.DOMAIN, name);
                        final var create = new StringBuilder(
                                "CREATE DOMAIN " + qName + " AS " + basetype);
                        final var canonical = new StringBuilder(
                                "basetype=" + basetype + ";notnull=" + notnull);
                        if (collation != null) {
                            canonical.append(";collation=").append(collation);
                            create.append(" COLLATE ").append(collation);
                        }
                        if (dflt != null) {
                            canonical.append(";default=").append(dflt);
                            create.append(" DEFAULT ").append(dflt);
                        }
                        if (notnull) {
                            create.append(" NOT NULL");
                        }
                        for (final var entry : checks.getOrDefault(oid, List.of())) {
                            final var tab = entry.indexOf('\0');
                            final var cname = entry.substring(0, tab);
                            final var def = entry.substring(tab + 1);
                            canonical.append(";check.").append(cname).append('=').append(def);
                            create.append(" CONSTRAINT ").append(PgIdentifiers.quote(cname))
                                    .append(' ').append(def);
                        }
                        define(id, canonical.toString(),
                                Map.of(CREATE_SQL, create.toString(),
                                        DROP_SQL, "DROP DOMAIN " + qName));
                        typeByOid.put(oid, id);
                    }
                }
            }
        }

        /**
         * Standalone composite types ({@code CREATE TYPE n AS (a int, …)}):
         * the canonical definition is the ordered attribute list — quoted
         * name, deparsed type and non-default collation — in attnum order,
         * since field order is part of the type. The twin {@code relkind 'c'}
         * row is the composite's backing relation, not an object of its own;
         * {@code relByOid} maps it back to the type so dependency edges and
         * annotations resolve to the managed {@link ObjectKind#TYPE} object.
         * A table's rowtype also carries {@code typtype 'c'}, but its
         * {@code typrelid} is an {@code 'r'} relation — the join keeps only
         * standalone composites.
         */
        void loadComposites(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT t.oid, t.typname, t.typrelid,
                           (SELECT string_agg(quote_ident(a.attname) || ' '
                                    || format_type(a.atttypid, a.atttypmod)
                                    || CASE WHEN a.attcollation <> at.typcollation
                                            THEN ' collate '
                                                 || a.attcollation::regcollation::text
                                            ELSE '' END,
                                    ', ' ORDER BY a.attnum)
                              FROM pg_attribute a
                              JOIN pg_type at ON at.oid = a.atttypid
                              WHERE a.attrelid = t.typrelid
                                AND a.attnum > 0 AND NOT a.attisdropped) AS attrs
                    FROM pg_type t
                    JOIN pg_class c ON c.oid = t.typrelid AND c.relkind = 'c'
                    JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = ? AND t.typtype = 'c'
                    ORDER BY t.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        final var relid = rs.getLong("typrelid");
                        if (extMember(oid) || extMember(relid)) {
                            continue;
                        }
                        final var name = rs.getString("typname");
                        final var attrs = nullToEmpty(rs.getString("attrs"));
                        final var qName = qNs + "." + PgIdentifiers.quote(name);
                        final var id = new ObjectId(ObjectKind.TYPE, name);
                        define(id, "typtype=c;attrs=" + attrs,
                                Map.of(CREATE_SQL, "CREATE TYPE " + qName
                                                + " AS (" + attrs + ")",
                                        DROP_SQL, "DROP TYPE " + qName));
                        typeByOid.put(oid, id);
                        relByOid.put(relid, id);
                    }
                }
            }
        }

        /**
         * Range types ({@code CREATE TYPE n AS RANGE (SUBTYPE = t, …)}):
         * the canonical definition is the {@code pg_range} row — subtype,
         * non-default collation, subtype operator class, canonical and
         * subtype-diff functions. The generated family (multirange,
         * constructor routines, the range→multirange cast) folds into the
         * range in {@link #loadAutoTypeFamily}; a multirange has no
         * standalone create form, so it is never an object of its own.
         * {@code typeByOid} is already populated there.
         */
        void loadRanges(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT t.oid, t.typname,
                           format_type(r.rngsubtype, NULL) AS subtype,
                           NULLIF(r.rngcollation, 0)::regcollation::text AS coll,
                           -- SUBTYPE_OPCLASS takes a bare name and pg_range
                           -- always stores the resolved class, so it is
                           -- reported only when it is not the subtype's
                           -- default btree operator class
                           CASE WHEN EXISTS (
                                    SELECT 1 FROM pg_opclass d
                                    JOIN pg_opfamily f
                                      ON f.oid = d.opcfamily
                                    JOIN pg_am am
                                      ON am.oid = f.opfmethod
                                    WHERE am.amname = 'btree'
                                      AND d.opcintype = r.rngsubtype
                                      AND d.opcdefault
                                      AND d.oid = r.rngsubopc)
                                THEN NULL
                                ELSE (SELECT o.opcname FROM pg_opclass o
                                      WHERE o.oid = r.rngsubopc)
                           END AS opc,
                           NULLIF(r.rngcanonical, 0)::regproc::text AS canonical,
                           NULLIF(r.rngsubdiff, 0)::regproc::text AS diff
                    FROM pg_range r
                    JOIN pg_type t ON t.oid = r.rngtypid
                    JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = ?
                    ORDER BY t.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var name = rs.getString("typname");
                        final var subtype = rs.getString("subtype");
                        final var coll = rs.getString("coll");
                        final var opc = rs.getString("opc");
                        final var canonical = rs.getString("canonical");
                        final var diff = rs.getString("diff");
                        final var canon = new StringBuilder(
                                "typtype=r;subtype=" + subtype);
                        final var create = new StringBuilder(
                                "CREATE TYPE " + qNs + "."
                                        + PgIdentifiers.quote(name)
                                        + " AS RANGE (SUBTYPE = " + subtype);
                        if (opc != null) {
                            canon.append(";opclass=").append(opc);
                            create.append(", SUBTYPE_OPCLASS = ").append(opc);
                        }
                        if (coll != null) {
                            canon.append(";collation=").append(coll);
                            create.append(", COLLATION = ").append(coll);
                        }
                        if (canonical != null) {
                            canon.append(";canonical=").append(canonical);
                            create.append(", CANONICAL = ").append(canonical);
                        }
                        if (diff != null) {
                            canon.append(";subtype_diff=").append(diff);
                            create.append(", SUBTYPE_DIFF = ").append(diff);
                        }
                        create.append(')');
                        final var id = new ObjectId(ObjectKind.TYPE, name);
                        define(id, canon.toString(),
                                Map.of(CREATE_SQL, create.toString(),
                                        DROP_SQL, "DROP TYPE " + qNs + "."
                                                + PgIdentifiers.quote(name)));
                        typeByOid.put(oid, id);
                    }
                }
            }
        }

        /**
         * Row-level-security policies on our tables: a {@code POLICY} object
         * parented on its table. The canonical definition is the command,
         * permissive flag, sorted distinct role list ({@code {0}} renders as
         * {@code PUBLIC}) and the deparsed {@code USING}/{@code WITH CHECK}
         * expressions. The parent linkage and an explicit dependency edge tie
         * the policy to its table; {@code loadDependencies} adds the
         * expressions' referenced routines and types.
         */
        void loadPolicies(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT po.oid, po.polname, po.polrelid, c.relname, po.polcmd,
                           po.polpermissive,
                           (SELECT array_agg(x ORDER BY x)
                              FROM (SELECT DISTINCT CASE WHEN u = 0 THEN 'PUBLIC'
                                                         ELSE r.rolname END AS x
                                      FROM unnest(po.polroles) u
                                      LEFT JOIN pg_roles r ON r.oid = u) s) AS roles,
                           pg_get_expr(po.polqual, po.polrelid) AS using,
                           pg_get_expr(po.polwithcheck, po.polrelid) AS wcheck
                    FROM pg_policy po
                    JOIN pg_class c ON c.oid = po.polrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
                    ORDER BY po.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var name = rs.getString("polname");
                        final var table = rs.getString("relname");
                        final var rolesArray = rs.getArray("roles");
                        final var roles = rolesArray == null ? List.<String>of()
                                : Arrays.stream((String[]) rolesArray.getArray())
                                        .map(r -> "PUBLIC".equals(r)
                                                ? r : PgIdentifiers.quote(r))
                                        .toList();
                        final var using = rs.getString("using");
                        final var wcheck = rs.getString("wcheck");
                        final var qTable = qNs + "." + PgIdentifiers.quote(table);
                        final var id = new ObjectId(ObjectKind.POLICY, name, table);
                        final var canonical = new StringBuilder("cmd=" + rs.getString("polcmd")
                                + ";permissive=" + rs.getBoolean("polpermissive")
                                + ";roles=" + String.join(",", roles));
                        final var create = new StringBuilder("CREATE POLICY "
                                + PgIdentifiers.quote(name) + " ON " + qTable);
                        if (!rs.getBoolean("polpermissive")) {
                            create.append(" AS RESTRICTIVE");
                        }
                        create.append(" FOR ").append(switch (rs.getString("polcmd")) {
                            case "r" -> "SELECT";
                            case "a" -> "INSERT";
                            case "w" -> "UPDATE";
                            case "d" -> "DELETE";
                            default -> "ALL";
                        }).append(" TO ").append(
                                roles.isEmpty() ? "PUBLIC" : String.join(",", roles));
                        if (using != null) {
                            canonical.append(";using=").append(using);
                            create.append(" USING (").append(using).append(')');
                        }
                        if (wcheck != null) {
                            canonical.append(";check=").append(wcheck);
                            create.append(" WITH CHECK (").append(wcheck).append(')');
                        }
                        define(id, canonical.toString(),
                                Map.of(CREATE_SQL, create.toString(),
                                        DROP_SQL, "DROP POLICY " + PgIdentifiers.quote(name)
                                                + " ON " + qTable));
                        polByOid.put(oid, id);
                        dep(id, relByOid.get(rs.getLong("polrelid")));
                    }
                }
            }
        }

        /**
         * Extended statistics objects (pg_statistic_ext): a {@code STATISTICS}
         * object named by {@code stxname} — statistics names are unique per
         * schema, not per table, so the id is unparented; an explicit
         * {@code dependsOn} edge ties it to its table. The canonical
         * definition is {@code pg_get_statisticsobjdef}, which carries the
         * enabled statistic kinds, the column/expression list and the source
         * table. {@code stxstattarget} is a per-column planner property, not
         * declarable, so it stays outside the canonical (like attstattarget).
         */
        void loadStatistics(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT s.oid, s.stxname, s.stxrelid,
                           pg_get_statisticsobjdef(s.oid) AS def
                    FROM pg_statistic_ext s
                    JOIN pg_namespace n ON n.oid = s.stxnamespace AND n.nspname = ?
                    ORDER BY s.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        final var name = rs.getString("stxname");
                        final var def = rs.getString("def");
                        final var id = new ObjectId(ObjectKind.STATISTICS, name);
                        stxByOid.put(rs.getLong("oid"), id);
                        // the unsupported-dependents union resolves edges from
                        // expression statistics (function/type refs in
                        // stxexprs) through this registration too
                        otherOid("pg_statistic_ext", rs.getLong("oid"), id);
                        define(id, def, Map.of(
                                CREATE_SQL, def,
                                DROP_SQL, "DROP STATISTICS " + qNs + "."
                                        + PgIdentifiers.quote(name)));
                        dep(id, relByOid.get(rs.getLong("stxrelid")));
                    }
                }
            }
        }

        /**
         * Non-view rewrite rules (pg_rewrite rows other than {@code _RETURN},
         * which is part of its view): a {@code RULE} object parented on its
         * relation — rule names are unique per table. The canonical
         * definition is {@code pg_get_ruledef} plus the enabled state
         * ({@code ev_enabled}, like a trigger's {@code tgenabled}); the
         * {@code pg_rewrite}-classid registration feeds the
         * unsupported-dependents union, which records the rule's edges to
         * its relation and every object the action references.
         */
        void loadRules(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT r.oid, r.rulename, c.relname AS parent, r.ev_class,
                           r.ev_enabled, pg_get_ruledef(r.oid, true) AS def
                    FROM pg_rewrite r
                    JOIN pg_class c ON c.oid = r.ev_class
                    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
                    WHERE r.rulename <> '_RETURN'
                    ORDER BY r.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        final var name = rs.getString("rulename");
                        final var parent = rs.getString("parent");
                        final var enabled = rs.getString("ev_enabled");
                        final var def = rs.getString("def");
                        final var id = new ObjectId(ObjectKind.RULE, name, parent);
                        ruleByOid.put(rs.getLong("oid"), id);
                        otherOid("pg_rewrite", rs.getLong("oid"), id);
                        final var qName = PgIdentifiers.quote(name);
                        final var qParent = tableRef(parent);
                        final var attrs = new LinkedHashMap<String, String>();
                        attrs.put(CREATE_SQL, def);
                        attrs.put(DROP_SQL, "DROP RULE " + qName + " ON " + qParent);
                        if (!enabled.equals("O")) {
                            final var verb = switch (enabled) {
                                case "D" -> "DISABLE";
                                case "R" -> "ENABLE REPLICA";
                                default -> "ENABLE ALWAYS";
                            };
                            attrs.put(POST_SQL, "ALTER TABLE " + qParent + " "
                                    + verb + " RULE " + qName);
                        }
                        define(id, def + ";enabled=" + enabled, attrs);
                        dep(id, relByOid.get(rs.getLong("ev_class")));
                    }
                }
            }
        }

        /**
         * Collations a desired script can create: libc or ICU provider and an
         * encoding either universal ({@code -1}) or bound to this database's
         * own encoding — that is what {@code CREATE COLLATION} records, and a
         * collation bound to a different encoding cannot be recreated here
         * (the database-default provider can't either — both stay
         * {@code OTHER}). There is no {@code pg_get_collationdef}, so the
         * canonical definition and the {@code CREATE COLLATION} fragment are
         * composed from the provider fields; {@code collversion} is
         * environment metadata (the libc/ICU library version at creation)
         * and deliberately excluded so the same collation does not drift
         * across hosts. {@code colllocale} is named {@code colliculocale}
         * before PostgreSQL 17 — read via {@code to_json} so both spellings
         * resolve.
         */
        void loadCollations(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT cl.oid, cl.collname, cl.collprovider, cl.collisdeterministic,
                           cl.collencoding, cl.collcollate, cl.collctype,
                           COALESCE(to_json(cl)->>'colllocale',
                                    to_json(cl)->>'colliculocale') AS colllocale,
                           cl.collicurules
                    FROM pg_collation cl
                    JOIN pg_namespace n ON n.oid = cl.collnamespace AND n.nspname = ?
                    WHERE cl.collprovider IN ('c', 'i')
                      AND cl.collencoding IN (-1,
                          (SELECT encoding FROM pg_database
                           WHERE datname = current_database()))
                    ORDER BY cl.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        final var name = rs.getString("collname");
                        final var icu = rs.getString("collprovider").equals("i");
                        final var deterministic = rs.getBoolean("collisdeterministic");
                        final var collate = rs.getString("collcollate");
                        final var ctype = rs.getString("collctype");
                        final var locale = rs.getString("colllocale");
                        final var rules = rs.getString("collicurules");
                        String canonical;
                        String options;
                        if (icu) {
                            options = "provider = icu, locale = " + sqlLiteral(locale)
                                    + (rules == null ? "" : ", rules = " + sqlLiteral(rules))
                                    + (deterministic ? "" : ", deterministic = false");
                            canonical = "provider=icu;locale=" + locale
                                    + (rules == null ? "" : ";rules=" + rules)
                                    + (deterministic ? "" : ";deterministic=false")
                                    + ";encoding=" + rs.getInt("collencoding");
                        } else {
                            options = collate.equals(ctype)
                                    ? "locale = " + sqlLiteral(collate)
                                    : "lc_collate = " + sqlLiteral(collate)
                                            + ", lc_ctype = " + sqlLiteral(ctype);
                            canonical = "provider=libc;collate=" + collate
                                    + ";ctype=" + ctype
                                    + ";encoding=" + rs.getInt("collencoding");
                        }
                        final var id = new ObjectId(ObjectKind.COLLATION, name);
                        collByOid.put(rs.getLong("oid"), id);
                        final var qName = tableRef(name);
                        define(id, canonical, Map.of(
                                CREATE_SQL, "CREATE COLLATION " + qName + " (" + options + ")",
                                DROP_SQL, "DROP COLLATION " + qName));
                    }
                }
            }
        }

        /**
         * Conversions between client encodings: there is no deparsed
         * definition, so the canonical composes source/target encoding names
         * and the conversion function ({@code regproc}, which is the
         * reference form {@code CREATE CONVERSION ... FROM} accepts).
         * {@code DEFAULT CONVERSION} rows stay {@code OTHER} — {@code DEFAULT}
         * heads the script check's database-wide list, so a desired script
         * can never express the flag. A conversion depends on its function
         * when the function is managed.
         */
        void loadConversions(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT cv.oid, cv.conname,
                           pg_encoding_to_char(cv.conforencoding) AS forenc,
                           pg_encoding_to_char(cv.contoencoding) AS toenc,
                           cv.conproc::regproc::text AS procname,
                           cv.conproc::oid AS conproc
                    FROM pg_conversion cv
                    JOIN pg_namespace n ON n.oid = cv.connamespace AND n.nspname = ?
                    WHERE NOT cv.condefault
                    ORDER BY cv.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        final var name = rs.getString("conname");
                        final var forenc = rs.getString("forenc");
                        final var toenc = rs.getString("toenc");
                        final var conproc = rs.getString("procname");
                        final var id = new ObjectId(ObjectKind.CONVERSION, name);
                        convByOid.put(rs.getLong("oid"), id);
                        final var qName = tableRef(name);
                        define(id,
                                "from=" + forenc + ";to=" + toenc + ";proc=" + conproc,
                                Map.of(
                                        CREATE_SQL, "CREATE CONVERSION " + qName
                                                + " FOR " + sqlLiteral(forenc)
                                                + " TO " + sqlLiteral(toenc)
                                                + " FROM " + conproc,
                                        DROP_SQL, "DROP CONVERSION " + qName));
                        dep(id, procByOid.get(rs.getLong("conproc")));
                    }
                }
            }
        }

        /**
         * Full-text-search objects — four managed kinds:
         * {@code TEXT_SEARCH_TEMPLATE}, {@code TEXT_SEARCH_PARSER},
         * {@code TEXT_SEARCH_DICTIONARY} and {@code TEXT_SEARCH_CONFIGURATION}.
         * No {@code pg_get_*def} exists for them, so the canonical and the
         * {@code CREATE TEXT SEARCH …} fragment are composed from the catalog
         * row (the AGGREGATE pattern): function references as
         * {@code regproc} text, the dictionary's template reference and
         * {@code dictinitoption} verbatim, the configuration's parser
         * reference plus the {@code pg_ts_config_map} token→dictionary chain
         * folded into the configuration object (map rows are never objects —
         * {@code ALTER ... ADD MAPPING} fragments ride in {@code POST_SQL} in
         * {@code mapseqno} order). Token aliases resolve through
         * {@code ts_token_type(cfgparser)}; an unresolvable alias falls back
         * to {@code tok<N>}. Any canonical drift is a DROP+CREATE REBUILD —
         * PostgreSQL has no in-place create for the function references.
         */
        void loadTextSearch(Connection conn) throws SQLException {
            // templates: the two support functions are the whole body
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT t.oid, t.tmplname,
                           t.tmplinit::regproc::text AS init,
                           t.tmpllexize::regproc::text AS lexize,
                           t.tmplinit::oid AS init_oid,
                           t.tmpllexize::oid AS lexize_oid
                    FROM pg_ts_template t
                    JOIN pg_namespace n ON n.oid = t.tmplnamespace
                    WHERE n.nspname = ?
                    ORDER BY t.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var name = rs.getString("tmplname");
                        final var init = rs.getString("init");
                        final var lexize = rs.getString("lexize");
                        final var id = new ObjectId(
                                ObjectKind.TEXT_SEARCH_TEMPLATE, name);
                        tmplByOid.put(oid, id);
                        otherOid("pg_ts_template", oid, id);
                        final var qName = qNs + "." + PgIdentifiers.quote(name);
                        final var opts = "-".equals(init)
                                ? "LEXIZE = " + lexize
                                : "INIT = " + init + ", LEXIZE = " + lexize;
                        define(id,
                                "ts_template=init:" + init + ";lexize:" + lexize,
                                Map.of(
                                        CREATE_SQL, "CREATE TEXT SEARCH TEMPLATE "
                                                + qName + " (" + opts + ")",
                                        DROP_SQL, "DROP TEXT SEARCH TEMPLATE "
                                                + qName));
                        if (!"-".equals(init)) {
                            dep(id, procByOid.get(rs.getLong("init_oid")));
                        }
                        dep(id, procByOid.get(rs.getLong("lexize_oid")));
                    }
                }
            }
            // parsers: five support functions; prsheadline is optional ('-')
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT p.oid, p.prsname,
                           p.prsstart::regproc::text AS start,
                           p.prstoken::regproc::text AS token,
                           p.prsend::regproc::text AS end,
                           p.prsheadline::regproc::text AS headline,
                           p.prslextype::regproc::text AS lextype,
                           p.prsstart::oid AS start_oid,
                           p.prstoken::oid AS token_oid,
                           p.prsend::oid AS end_oid,
                           p.prsheadline::oid AS headline_oid,
                           p.prslextype::oid AS lextype_oid
                    FROM pg_ts_parser p
                    JOIN pg_namespace n ON n.oid = p.prsnamespace
                    WHERE n.nspname = ?
                    ORDER BY p.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var name = rs.getString("prsname");
                        final var headline = rs.getString("headline");
                        final var id = new ObjectId(
                                ObjectKind.TEXT_SEARCH_PARSER, name);
                        prsByOid.put(oid, id);
                        otherOid("pg_ts_parser", oid, id);
                        final var qName = qNs + "." + PgIdentifiers.quote(name);
                        var opts = "START = " + rs.getString("start")
                                + ", GETTOKEN = " + rs.getString("token")
                                + ", END = " + rs.getString("end")
                                + ", LEXTYPES = " + rs.getString("lextype");
                        if (!"-".equals(headline)) {
                            opts += ", HEADLINE = " + headline;
                        }
                        define(id, "ts_parser=start:" + rs.getString("start")
                                        + ";token:" + rs.getString("token")
                                        + ";end:" + rs.getString("end")
                                        + ";headline:" + headline
                                        + ";lextype:" + rs.getString("lextype"),
                                Map.of(
                                        CREATE_SQL, "CREATE TEXT SEARCH PARSER "
                                                + qName + " (" + opts + ")",
                                        DROP_SQL, "DROP TEXT SEARCH PARSER "
                                                + qName));
                        for (final var c : new String[]{"start_oid", "token_oid",
                                "end_oid", "headline_oid", "lextype_oid"}) {
                            final var fn = rs.getLong(c);
                            if (fn != 0) {
                                dep(id, procByOid.get(fn));
                            }
                        }
                    }
                }
            }
            // dictionaries: template reference + free-form init option text
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT d.oid, d.dictname, d.dicttemplate,
                           tn.nspname || '.' || t.tmplname AS tmplref,
                           d.dictinitoption
                    FROM pg_ts_dict d
                    JOIN pg_ts_template t ON t.oid = d.dicttemplate
                    JOIN pg_namespace tn ON tn.oid = t.tmplnamespace
                    JOIN pg_namespace n ON n.oid = d.dictnamespace
                    WHERE n.nspname = ?
                    ORDER BY d.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var name = rs.getString("dictname");
                        final var tmplref = rs.getString("tmplref");
                        final var initopt = rs.getString("dictinitoption");
                        final var id = new ObjectId(
                                ObjectKind.TEXT_SEARCH_DICTIONARY, name);
                        dictByOid.put(oid, id);
                        otherOid("pg_ts_dict", oid, id);
                        final var qName = qNs + "." + PgIdentifiers.quote(name);
                        var opts = "TEMPLATE = " + tmplref;
                        if (initopt != null && !initopt.isBlank()) {
                            opts += ", " + initopt;
                        }
                        define(id, "ts_dict=template:" + tmplref
                                        + ";init:" + (initopt == null
                                                ? "-" : initopt),
                                Map.of(
                                        CREATE_SQL, "CREATE TEXT SEARCH DICTIONARY "
                                                + qName + " (" + opts + ")",
                                        DROP_SQL, "DROP TEXT SEARCH DICTIONARY "
                                                + qName));
                        dep(id, tmplByOid.get(rs.getLong("dicttemplate")));
                    }
                }
            }
            // configurations: parser reference plus the folded token→dict
            // map chain; each map row renders an ALTER ... ADD MAPPING in
            // mapseqno order inside POST_SQL
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT c.oid, c.cfgname, c.cfgparser,
                           pn.nspname || '.' || p.prsname AS prsref
                    FROM pg_ts_config c
                    JOIN pg_ts_parser p ON p.oid = c.cfgparser
                    JOIN pg_namespace pn ON pn.oid = p.prsnamespace
                    JOIN pg_namespace n ON n.oid = c.cfgnamespace
                    WHERE n.nspname = ?
                    ORDER BY c.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var name = rs.getString("cfgname");
                        final var prsref = rs.getString("prsref");
                        final var id = new ObjectId(
                                ObjectKind.TEXT_SEARCH_CONFIGURATION, name);
                        cfgByOid.put(oid, id);
                        otherOid("pg_ts_config", oid, id);
                        final var qName = qNs + "." + PgIdentifiers.quote(name);
                        final var attrs = new LinkedHashMap<String, String>();
                        attrs.put(CREATE_SQL, "CREATE TEXT SEARCH CONFIGURATION "
                                + qName + " (PARSER = " + prsref + ")");
                        attrs.put(DROP_SQL, "DROP TEXT SEARCH CONFIGURATION "
                                + qName);
                        final var maps = configMaps(conn, oid, qName);
                        var canon = "ts_config=parser:" + prsref;
                        if (!maps.isEmpty()) {
                            final var entries = new ArrayList<String>();
                            final var alters = new ArrayList<String>();
                            for (final var m : maps) {
                                entries.add(m.alias + "@" + m.seq + "=" + m.dictref);
                                alters.add("ALTER TEXT SEARCH CONFIGURATION "
                                        + qName + " ADD MAPPING FOR " + m.alias
                                        + " WITH " + m.dictref);
                                dep(id, dictByOid.get(m.dictoid));
                            }
                            canon += ";map=" + String.join(",", entries);
                            attrs.put(POST_SQL, String.join("; ", alters));
                        }
                        define(id, canon, attrs);
                        dep(id, prsByOid.get(rs.getLong("cfgparser")));
                    }
                }
            }
        }

        /**
         * The {@code pg_ts_config_map} rows of one configuration, ordered by
         * {@code mapseqno}: token alias (resolved through
         * {@code ts_token_type(cfgparser)}, {@code tok<N>} when unresolvable),
         * sequence number and the dictionary reference as
         * {@code nspname.dictname} — normalized to {@code ${namespace}} by
         * {@code define}'s caller path like every reference.
         */
        private List<TsMap> configMaps(Connection conn, long cfgoid,
                                       String qName) throws SQLException {
            final var out = new ArrayList<TsMap>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT m.maptokentype, m.mapseqno, m.mapdict,
                           dn.nspname || '.' || d.dictname AS dictref,
                           tt.alias
                    FROM pg_ts_config_map m
                    JOIN pg_ts_config c ON c.oid = m.mapcfg
                    JOIN pg_ts_dict d ON d.oid = m.mapdict
                    JOIN pg_namespace dn ON dn.oid = d.dictnamespace
                    LEFT JOIN LATERAL (
                        SELECT alias FROM ts_token_type(c.cfgparser)
                        WHERE tokid = m.maptokentype
                    ) tt ON true
                    WHERE m.mapcfg = ?
                    ORDER BY m.mapseqno
                    """)) {
                ps.setLong(1, cfgoid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        var alias = rs.getString("alias");
                        if (alias == null) {
                            alias = "tok" + rs.getLong("maptokentype");
                        }
                        out.add(new TsMap(alias, rs.getInt("mapseqno"),
                                rs.getLong("mapdict"), rs.getString("dictref")));
                    }
                }
            }
            return out;
        }

        /** One {@code pg_ts_config_map} row folded into its configuration. */
        private record TsMap(String alias, int seq, long dictoid,
                             String dictref) { }

        /**
         * Operator objects — three managed kinds: {@code OPERATOR} (name
         * plus its {@code (leftarg,rightarg)} identity signature like a
         * routine, {@code NONE} for an absent prefix operand),
         * {@code OPERATOR_FAMILY} (name {@code opfname USING amname} — the
         * access method is part of identity) and {@code OPERATOR_CLASS}
         * (same name form; a class's {@code pg_amop}/{@code pg_amproc}
         * members fold into its canonical like a configuration's map).
         * No {@code pg_get_*def} exists, so canonical and {@code CREATE}
         * fragments are composed from the catalog rows: operator options
         * (procedure, argument/result types, commutator, negator,
         * restrict/join selectivity, HASHES/MERGES), family options (the
         * access method), class options (DEFAULT, FOR TYPE, FAMILY,
         * STORAGE, ordered {@code OPERATOR n}/{@code FUNCTION n} member
         * specs). Member operator references render as
         * {@code regoperator} text (name plus operand types), member
         * functions as {@code regprocedure}. Standalone family members
         * are not modeled — {@code ALTER OPERATOR FAMILY … ADD|DROP}
         * stays rejected in the desired-script check. Any drift is a
         * DROP+CREATE REBUILD.
         */
        void loadOperators(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT o.oid, o.oprname, o.oprleft, o.oprright,
                           o.oprleft::regtype::text AS leftt,
                           o.oprright::regtype::text AS rightt,
                           o.oprresult::regtype::text AS result,
                           o.oprcode::regproc::text AS proc,
                           o.oprcode::oid AS proc_oid,
                           o.oprrest::regproc::text AS rest,
                           o.oprrest::oid AS rest_oid,
                           o.oprjoin::regproc::text AS joinsel,
                           o.oprjoin::oid AS join_oid,
                           cn.nspname || '.' || co.oprname AS comref,
                           nn.nspname || '.' || no.oprname AS negref,
                           o.oprcom, o.oprnegate,
                           o.oprresult AS result_oid,
                           o.oprcanhash, o.oprcanmerge
                    FROM pg_operator o
                    JOIN pg_namespace n ON n.oid = o.oprnamespace
                    LEFT JOIN pg_operator co ON co.oid = o.oprcom
                    LEFT JOIN pg_namespace cn ON cn.oid = co.oprnamespace
                    LEFT JOIN pg_operator no ON no.oid = o.oprnegate
                    LEFT JOIN pg_namespace nn ON nn.oid = no.oprnamespace
                    WHERE n.nspname = ?
                    ORDER BY o.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var left = rs.getLong("oprleft");
                        final var right = rs.getLong("oprright");
                        final var name = rs.getString("oprname") + "("
                                + (left == 0 ? "NONE" : rs.getString("leftt"))
                                + ","
                                + (right == 0 ? "NONE" : rs.getString("rightt"))
                                + ")";
                        final var id = new ObjectId(ObjectKind.OPERATOR, name);
                        oprByOid.put(oid, id);
                        otherOid("pg_operator", oid, id);
                        // operator names are symbol strings, not identifiers
                        // — double-quoting them is invalid syntax
                        final var qName = qNs + "." + rs.getString("oprname");
                        var canon = "operator=" + rs.getString("result")
                                + ";proc:" + rs.getString("proc")
                                + ";rest:" + rs.getString("rest")
                                + ";join:" + rs.getString("joinsel")
                                + ";hash:" + rs.getBoolean("oprcanhash")
                                + ";merge:" + rs.getBoolean("oprcanmerge")
                                + ";com:" + nullToDash(rs.getString("comref"))
                                + ";neg:" + nullToDash(rs.getString("negref"));
                        var opts = new StringBuilder("PROCEDURE = ")
                                .append(rs.getString("proc"));
                        if (left != 0) {
                            opts.append(", LEFTARG = ").append(rs.getString("leftt"));
                        }
                        if (right != 0) {
                            opts.append(", RIGHTARG = ").append(rs.getString("rightt"));
                        }
                        opts.append(", RESULT = ").append(rs.getString("result"));
                        if (rs.getLong("oprcom") != 0) {
                            opts.append(", COMMUTATOR = OPERATOR(")
                                    .append(rs.getString("comref")).append(')');
                        }
                        if (rs.getLong("oprnegate") != 0) {
                            opts.append(", NEGATOR = OPERATOR(")
                                    .append(rs.getString("negref")).append(')');
                        }
                        if (!"-".equals(rs.getString("rest"))) {
                            opts.append(", RESTRICT = ").append(rs.getString("rest"));
                        }
                        if (!"-".equals(rs.getString("joinsel"))) {
                            opts.append(", JOIN = ").append(rs.getString("joinsel"));
                        }
                        if (rs.getBoolean("oprcanhash")) {
                            opts.append(", HASHES");
                        }
                        if (rs.getBoolean("oprcanmerge")) {
                            opts.append(", MERGES");
                        }
                        define(id, canon, Map.of(
                                CREATE_SQL, "CREATE OPERATOR " + qName
                                        + " (" + opts + ")",
                                DROP_SQL, "DROP OPERATOR " + qName + " ("
                                        + (left == 0 ? "NONE" : rs.getString("leftt"))
                                        + ", "
                                        + (right == 0 ? "NONE" : rs.getString("rightt"))
                                        + ")"));
                        dep(id, procByOid.get(rs.getLong("proc_oid")));
                        dep(id, typeByOid.get(left));
                        dep(id, typeByOid.get(right));
                        dep(id, typeByOid.get(rs.getLong("result_oid")));
                        dep(id, oprByOid.get(rs.getLong("oprcom")));
                        dep(id, oprByOid.get(rs.getLong("oprnegate")));
                        dep(id, procByOid.get(rs.getLong("rest_oid")));
                        dep(id, procByOid.get(rs.getLong("join_oid")));
                    }
                }
            }
            // families: name + access method only — standalone members are
            // not modeled (ALTER FAMILY member mutations stay rejected)
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT f.oid, f.opfname, a.amname
                    FROM pg_opfamily f
                    JOIN pg_am a ON a.oid = f.opfmethod
                    JOIN pg_namespace n ON n.oid = f.opfnamespace
                    WHERE n.nspname = ?
                    ORDER BY f.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var name = rs.getString("opfname") + " USING "
                                + rs.getString("amname");
                        final var id = new ObjectId(ObjectKind.OPERATOR_FAMILY,
                                name);
                        opfByOid.put(oid, id);
                        otherOid("pg_opfamily", oid, id);
                        final var qName = qNs + "."
                                + PgIdentifiers.quote(rs.getString("opfname"));
                        define(id, "opfamily=" + rs.getString("amname"), Map.of(
                                CREATE_SQL, "CREATE OPERATOR FAMILY " + qName
                                        + " USING " + rs.getString("amname"),
                                DROP_SQL, "DROP OPERATOR FAMILY " + qName
                                        + " USING " + rs.getString("amname")));
                    }
                }
            }
            // classes: am/type/family/default/storage plus the folded
            // amop/amproc member list (members where the row's left type is
            // the class's indexed type — cross-type members included)
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT c.oid, c.opcname, a.amname, c.opcfamily,
                           fn.nspname || '.' || f.opfname AS famref,
                           format_type(c.opcintype, NULL) AS intype,
                           format_type(c.opckeytype, NULL) AS storage,
                           c.opcintype, c.opckeytype, c.opcdefault
                    FROM pg_opclass c
                    JOIN pg_am a ON a.oid = c.opcmethod
                    JOIN pg_opfamily f ON f.oid = c.opcfamily
                    JOIN pg_namespace fn ON fn.oid = f.opfnamespace
                    JOIN pg_namespace n ON n.oid = c.opcnamespace
                    WHERE n.nspname = ?
                    ORDER BY c.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var name = rs.getString("opcname") + " USING "
                                + rs.getString("amname");
                        final var id = new ObjectId(ObjectKind.OPERATOR_CLASS,
                                name);
                        opcByOid.put(oid, id);
                        otherOid("pg_opclass", oid, id);
                        final var qName = qNs + "."
                                + PgIdentifiers.quote(rs.getString("opcname"));
                        final var members = opclassMembers(conn,
                                rs.getLong("opcfamily"), rs.getLong("opcintype"),
                                id);
                        var canon = "opclass=am:" + rs.getString("amname")
                                + ";type:" + rs.getString("intype")
                                + ";family:" + rs.getString("famref")
                                + ";default:" + rs.getBoolean("opcdefault")
                                + ";storage:" + nullToDash(rs.getString("storage"));
                        final var body = new StringBuilder(
                                rs.getBoolean("opcdefault") ? "DEFAULT " : "")
                                .append("FOR TYPE ").append(rs.getString("intype"))
                                .append(" USING ").append(rs.getString("amname"))
                                .append(" FAMILY ").append(rs.getString("famref"));
                        final var specs = new ArrayList<String>();
                        if (rs.getLong("opckeytype") != 0) {
                            specs.add("STORAGE " + rs.getString("storage"));
                        }
                        for (final var m : members) {
                            canon += ";m" + specs.size() + ":" + m;
                            specs.add(m);
                        }
                        body.append(specs.isEmpty() ? "" : " AS "
                                + String.join(", ", specs));
                        define(id, canon, Map.of(
                                CREATE_SQL, "CREATE OPERATOR CLASS " + qName
                                        + " " + body,
                                DROP_SQL, "DROP OPERATOR CLASS " + qName
                                        + " USING " + rs.getString("amname")));
                        dep(id, typeByOid.get(rs.getLong("opcintype")));
                        dep(id, opfByOid.get(rs.getLong("opcfamily")));
                    }
                }
            }
        }

        /**
         * The {@code pg_amop}/{@code pg_amproc} member specs of one
         * operator class — rows whose left type matches the class's
         * {@code opcintype}, ordered so {@code DROP} runs over a stable
         * list: {@code OPERATOR} members first (strategy, operator
         * reference, {@code FOR ORDER BY} family when set), then
         * {@code FUNCTION} members (number, type pair, function
         * reference). Dependency edges to member operators/functions and
         * sort families record on {@code id}.
         */
        private List<String> opclassMembers(Connection conn, long family,
                                            long intype, ObjectId id)
                throws SQLException {
            final var out = new ArrayList<String>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT m.amopstrategy, m.amoppurpose,
                           m.amopopr::regoperator::text AS opref,
                           m.amopopr::oid AS amopopr_oid,
                           sn.nspname || '.' || sf.opfname AS sortref,
                           m.amopsortfamily
                    FROM pg_amop m
                    LEFT JOIN pg_opfamily sf ON sf.oid = m.amopsortfamily
                    LEFT JOIN pg_namespace sn ON sn.oid = sf.opfnamespace
                    WHERE m.amopfamily = ? AND m.amoplefttype = ?
                    ORDER BY m.amopstrategy, m.amoprighttype, m.oid
                    """)) {
                ps.setLong(1, family);
                ps.setLong(2, intype);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        var spec = "OPERATOR " + rs.getInt("amopstrategy") + " "
                                + rs.getString("opref");
                        if ("o".equals(rs.getString("amoppurpose"))) {
                            spec += " FOR ORDER BY " + rs.getString("sortref");
                        }
                        out.add(spec);
                        dep(id, oprByOid.get(rs.getLong("amopopr_oid")));
                        dep(id, opfByOid.get(rs.getLong("amopsortfamily")));
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT m.amprocnum,
                           format_type(m.amproclefttype, NULL) AS lt,
                           format_type(m.amprocrighttype, NULL) AS rt,
                           m.amproc::regprocedure::text AS fn,
                           m.amproc::oid AS amproc_oid
                    FROM pg_amproc m
                    WHERE m.amprocfamily = ? AND m.amproclefttype = ?
                    ORDER BY m.amprocnum, m.oid
                    """)) {
                ps.setLong(1, family);
                ps.setLong(2, intype);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add("FUNCTION " + rs.getInt("amprocnum") + " ("
                                + rs.getString("lt") + "," + rs.getString("rt")
                                + ") " + rs.getString("fn"));
                        dep(id, procByOid.get(rs.getLong("amproc_oid")));
                    }
                }
            }
            return out;
        }

        private static String nullToDash(String v) {
            return v == null || v.isBlank() || "0".equals(v) || "-".equals(v)
                    ? "-" : v;
        }

        /**
         * Security labels ({@code pg_seclabel}) on managed targets: the id
         * mirrors the target like a {@code COMMENT}, prefixed with the
         * provider — {@code SECURITY_LABEL selinux:t}. The canonical is the
         * label text; re-issuing {@code SECURITY LABEL FOR ... ON ... IS}
         * is both the create and the in-place drift fix, and
         * {@code IS NULL} the drop. Labels on targets the dialect does not
         * manage stay {@code OTHER} (provider-qualified so two providers
         * cannot collapse into one entry).
         */
        void loadSecLabels(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT o.name AS target, d.objoid, o.cls::text AS cls, d.objsubid,
                           a.attname, d.provider, d.label
                    FROM pg_seclabel d
                    JOIN (""" + NS_OBJECTS + """
                    ) o ON o.cls = d.classoid AND o.obj = d.objoid
                    LEFT JOIN pg_attribute a ON d.classoid = 'pg_class'::regclass
                            AND a.attrelid = d.objoid AND a.attnum = d.objsubid
                    ORDER BY 1, 2, 3
                    """)) {
                bindNamespace(ps, 17);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("objoid"))) {
                            continue;
                        }
                        final var target = annotatedTarget(rs.getString("cls"),
                                rs.getLong("objoid"), rs.getInt("objsubid"),
                                rs.getString("attname"));
                        final var provider = rs.getString("provider");
                        if (target != null && target.kind() != ObjectKind.OTHER
                                && objects.containsKey(target)) {
                            var lid = seclabelId(target, provider, "");
                            if (objects.containsKey(lid)) {
                                // mirrors the COMMENT collision escape (A.5)
                                lid = seclabelId(target, provider, " ("
                                        + target.kind().name().toLowerCase(Locale.ROOT)
                                        + ")");
                            }
                            final var label = rs.getString("label");
                            define(lid, label, Map.of(
                                    CREATE_SQL, "SECURITY LABEL FOR "
                                            + PgIdentifiers.quote(provider) + " ON "
                                            + annotationTarget(target)
                                            + " IS " + sqlLiteral(label),
                                    DROP_SQL, "SECURITY LABEL FOR "
                                            + PgIdentifiers.quote(provider) + " ON "
                                            + annotationTarget(target) + " IS NULL"));
                            dep(lid, target);
                        } else {
                            define(new ObjectId(ObjectKind.OTHER, "seclabel:" + provider
                                    + "." + rs.getString("target")
                                    + (rs.getString("attname") == null
                                            ? "" : "." + rs.getString("attname"))),
                                    "security-label", Map.of());
                        }
                    }
                }
            }
        }

        /** Loads every catalog object a desired script can create in the
         *  namespace that has no managed {@link ObjectKind}, as OTHER, so the
         *  diff reports it UNSUPPORTED instead of ignoring it (5.4). */
        void loadOther(Connection conn) throws SQLException {
            // user-defined types that stay unsupported: base types (array
            // types carry typelem != 0 and are not standalone; enums,
            // composites and ranges are managed TYPE objects, domains
            // managed DOMAIN, and a range's multirange folds into it)
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT t.oid, t.typname, t.typtype
                    FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = ? AND t.typtype = 'b' AND t.typelem = 0
                    ORDER BY t.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        final var name = rs.getString("typname");
                        final var id = new ObjectId(ObjectKind.OTHER, "type:" + name);
                        typeByOid.put(rs.getLong("oid"), id);
                        otherOid("pg_type", rs.getLong("oid"), id);
                        define(id, "typtype=" + rs.getString("typtype"), Map.of());
                    }
                }
            }
            // extensions anchored to this namespace are managed EXTENSION
            // objects — presence-only canonical, so a version drift can
            // never surface as DRIFTED (A.5: ALTER EXTENSION UPDATE is out
            // of scope). The name keeps the extension: prefix so the id
            // stays stable across the promotion from OTHER.
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT e.oid, e.extname
                    FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                    WHERE n.nspname = ?
                    ORDER BY e.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var extname = rs.getString("extname");
                        define(new ObjectId(ObjectKind.EXTENSION, "extension:" + extname),
                                "extension",
                                Map.of(CREATE_SQL, "CREATE EXTENSION IF NOT EXISTS "
                                        + PgIdentifiers.quote(extname)
                                        + " WITH SCHEMA ${namespace}"));
                    }
                }
            }
            // policies on our tables load as managed POLICY objects in
            // loadPolicies — nothing to detect here
            // encoding conversions a desired script cannot express
            // (DEFAULT CONVERSION is a database-wide object word)
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT cv.oid, cv.conname
                    FROM pg_conversion cv JOIN pg_namespace n ON n.oid = cv.connamespace
                    WHERE n.nspname = ? AND cv.condefault
                    ORDER BY cv.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        final var id = new ObjectId(ObjectKind.OTHER,
                                "conversion:" + rs.getString("conname"));
                        otherOid("pg_conversion", rs.getLong("oid"), id);
                        define(id, "conversion", Map.of());
                    }
                }
            }
            // constraint rows the managed loader did not claim and which are
            // not followers of another object surface as OTHER — a contype
            // this build does not model cannot silently disappear. Trigger
            // constraints ('t') back a managed pg_trigger row and column
            // NOT NULL constraints ('n') fold into their COLUMN; non-local
            // rows are partition/inheritance copies of the parent.
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT c.oid, rel.relname AS parent, c.conname
                    FROM pg_constraint c
                    JOIN pg_class rel ON rel.oid = c.conrelid
                    JOIN pg_namespace n ON n.oid = rel.relnamespace
                    WHERE n.nspname = ? AND c.conislocal
                      AND c.contype NOT IN ('p','u','f','c','x','n')
                      AND NOT EXISTS (SELECT 1 FROM pg_trigger t
                                      WHERE t.tgconstraint = c.oid)
                    ORDER BY c.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var id = new ObjectId(ObjectKind.OTHER,
                                "constraint:" + rs.getString("parent")
                                        + "." + rs.getString("conname"));
                        conByOid.put(oid, id);
                        otherOid("pg_constraint", oid, id);
                        define(id, "constraint", Map.of());
                    }
                }
            }
            // collations the managed loader cannot express (default provider,
            // bound encoding) stay OTHER
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT cl.oid, cl.collname
                    FROM pg_collation cl JOIN pg_namespace n ON n.oid = cl.collnamespace
                    WHERE n.nspname = ?
                      AND (cl.collprovider NOT IN ('c', 'i')
                           OR cl.collencoding NOT IN (-1,
                               (SELECT encoding FROM pg_database
                                WHERE datname = current_database())))
                    ORDER BY cl.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        final var id = new ObjectId(ObjectKind.OTHER,
                                "collation:" + rs.getString("collname"));
                        collByOid.put(rs.getLong("oid"), id);
                        define(id, "collation", Map.of());
                    }
                }
            }
            // granted privileges: object acl columns are NULL until a
            // GRANT. On a managed target the acl becomes a managed
            // PRIVILEGES object mirroring the target like OWNERSHIP —
            // grantee roles are cluster-object references (4.1), whose
            // existence is a preflight concern. On an unmanaged target the
            // acl stays privileges: OTHER — still content-compared, so a
            // target holding the same grants reports nothing (5.4). The
            // definition is the sorted grantee:PRIVILEGE[*] token list with
            // the owner written <owner> and the grantor dropped.
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT 'pg_class' AS cls, 'privileges:' || c.relname AS name,
                           c.oid, '' AS attname,
                           """ + aclTokens("c.relacl", "c.relowner") + """
                           AS acl
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relacl IS NOT NULL
                    UNION ALL
                    SELECT 'pg_proc', 'privileges:' || p.proname || '('
                            || pg_get_function_identity_arguments(p.oid) || ')',
                            p.oid, '',
                           """ + aclTokens("p.proacl", "p.proowner") + """
                           AS acl
                    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = ? AND p.proacl IS NOT NULL
                    UNION ALL
                    SELECT 'pg_type', 'privileges:' || t.typname, t.oid, '',
                           """ + aclTokens("t.typacl", "t.typowner") + """
                           AS acl
                    FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = ? AND t.typacl IS NOT NULL
                    UNION ALL
                    SELECT 'pg_attribute', 'privileges:' || c.relname
                            || '.' || a.attname, a.attrelid, a.attname,
                           """ + aclTokens("a.attacl", "c.relowner") + """
                           AS acl
                    FROM pg_attribute a
                    JOIN pg_class c ON c.oid = a.attrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND a.attacl IS NOT NULL
                      AND a.attnum > 0 AND NOT a.attisdropped
                    ORDER BY 2, 1
                    """)) {
                bindNamespace(ps, 4);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var acl = rs.getString("acl");
                        if (acl == null) {
                            // an acl of '{}' unnests to no rows — no grants
                            // held, so nothing to compare
                            continue;
                        }
                        final var attname = rs.getString("attname");
                        if (rs.getString("cls").equals("pg_attribute")) {
                            columnPrivileges(oid, rs.getString("name"),
                                    attname, acl);
                            continue;
                        }
                        final var target = switch (rs.getString("cls")) {
                            case "pg_class" -> relByOid.get(oid);
                            case "pg_proc" -> procByOid.get(oid);
                            default -> typeByOid.get(oid);
                        };
                        if (target == null || target.kind() == ObjectKind.OTHER
                                || foldedMembers.contains(oid)) {
                            define(new ObjectId(ObjectKind.OTHER,
                                    rs.getString("name")), "privileges=" + acl,
                                    Map.of(Dialect.CONTENT_COMPARED_ATTRIBUTE,
                                            "true"));
                            continue;
                        }
                        var pid = privilegesId(target, "");
                        if (objects.containsKey(pid)) {
                            pid = privilegesId(target, " (" + target.kind().name()
                                    .toLowerCase(Locale.ROOT) + ")");
                        }
                        define(pid, "privileges=" + acl,
                                Map.of(GRANT_TARGET, grantTarget(target)));
                        dep(pid, target);
                    }
                }
            }
            // schema-scoped default privileges (pg_default_acl) — future
            // grants the namespace applies to every new object of a class.
            // Global rows (defaclnamespace = 0) belong to no namespace and
            // stay invisible like every other cluster object (A.5).
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT d.oid, r.rolname AS role, d.defaclobjtype AS objtype,
                           """ + aclTokens("d.defaclacl", "d.defaclrole") + """
                           AS acl
                    FROM pg_default_acl d
                    JOIN pg_roles r ON r.oid = d.defaclrole
                    JOIN pg_namespace n ON n.oid = d.defaclnamespace
                    WHERE n.nspname = ?
                    ORDER BY 2, 3
                    """)) {
                bindNamespace(ps, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var acl = rs.getString("acl");
                        final var classWord = defaultAclClass(rs.getString("objtype"));
                        if (acl == null || classWord == null) {
                            continue;
                        }
                        final var role = rs.getString("role");
                        final var id = new ObjectId(ObjectKind.DEFAULT_PRIVILEGES,
                                classWord + " for " + role);
                        define(id, "privileges=" + acl, Map.of(
                                GRANT_TARGET, classWord,
                                GRANT_PREFIX, "ALTER DEFAULT PRIVILEGES FOR ROLE "
                                        + PgIdentifiers.quote(role)
                                        + " IN SCHEMA ${namespace}"));
                    }
                }
            }
            // ownership: the role that owns each object, filtered by the
            // session role — a script that assigns no owner leaves the
            // object session-owned, so an absent OWNER TO means "don't
            // care", not "claim". On a managed target the role is a
            // cluster-object reference (4.1): the ownership binding becomes
            // a managed OWNERSHIP object named and parented like the target
            // (mirroring COMMENT); on an unmanaged target it stays OTHER.
            // An index's relowner always follows its table's — it cannot
            // diverge, so index rows are skipped outright.
            try (PreparedStatement ps = conn.prepareStatement("""
                    -- an index's relowner always follows its table's — it
                    -- cannot diverge — and a composite's 'c' or a toast 't'
                    -- row follows its type/table, so 'i'/'I'/'c'/'t' are out
                    SELECT 'pg_class' AS cls, 'ownership:' || c.relname AS name,
                           c.oid, c.relowner::regrole::text AS owner
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relkind NOT IN ('i', 'I', 'c', 't')
                      AND c.relowner::regrole::text <> current_user
                    UNION ALL
                    SELECT 'pg_proc', 'ownership:' || p.proname || '('
                            || pg_get_function_identity_arguments(p.oid) || ')',
                           p.oid, p.proowner::regrole::text
                    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = ? AND p.proowner::regrole::text <> current_user
                    UNION ALL
                    SELECT 'pg_type', 'ownership:' || t.typname,
                           t.oid, t.typowner::regrole::text
                    FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = ? AND t.typowner::regrole::text <> current_user
                      -- a table's implicit rowtype and its array type flip
                      -- owner with the table — neither is a type object of
                      -- its own; only a standalone composite's 'c' relation
                      -- counts. A multirange follows its range (ALTER TYPE on
                      -- it is rejected outright), so 'm' is out too
                      AND t.typtype <> 'm'
                      AND t.typelem = 0
                      AND NOT EXISTS (SELECT 1 FROM pg_class rt
                                      WHERE rt.oid = t.typrelid
                                        AND rt.relkind <> 'c')
                    ORDER BY 2
                    """)) {
                bindNamespace(ps, 3);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var target = switch (rs.getString("cls")) {
                            case "pg_class" -> relByOid.get(oid);
                            case "pg_proc" -> procByOid.get(oid);
                            default -> typeByOid.get(oid);
                        };
                        if (target != null && target.kind() == ObjectKind.INDEX) {
                            continue;
                        }
                        final var owner = rs.getString("owner");
                        if (target == null || target.kind() == ObjectKind.OTHER
                                || foldedMembers.contains(oid)) {
                            define(new ObjectId(ObjectKind.OTHER,
                                    rs.getString("name")), "ownership", Map.of());
                            continue;
                        }
                        var wid = ownershipId(target, "");
                        if (objects.containsKey(wid)) {
                            wid = ownershipId(target, " (" + target.kind().name()
                                    .toLowerCase(Locale.ROOT) + ")");
                        }
                        define(wid, "owner=" + owner,
                                Map.of(CREATE_SQL, "ALTER " + annotationTarget(target)
                                        + " OWNER TO " + PgIdentifiers.quote(owner)));
                        dep(wid, target);
                    }
                }
            }
            // publication bindings on namespace tables (the publication itself
            // is a cluster-level reference, 4.1; the FOR TABLE edge is the
            // namespace-visible part). The binding's parent is the bound
            // table, so it orders after it; filter/column lists ride in the
            // canonical (pg_publication_rel.prqual/.prattrs, PG15+)
            final var relBindingCols = serverMajor >= 15;
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT p.pubname, c.relname, c.oid,
                           """ + (relBindingCols ? """
                           pg_get_expr(pr.prqual, pr.prrelid) AS qual,
                           (SELECT string_agg(a.attname, ',' ORDER BY u.ord)
                              FROM unnest(string_to_array(pr.prattrs::text, ' ')::smallint[])
                                   WITH ORDINALITY u(attnum, ord)
                              JOIN pg_attribute a
                                ON a.attrelid = pr.prrelid AND a.attnum = u.attnum) AS cols
                           """ : "NULL AS qual, NULL AS cols\n") + """
                    FROM pg_publication_rel pr
                    JOIN pg_class c ON c.oid = pr.prrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
                    JOIN pg_publication p ON p.oid = pr.prpubid
                    ORDER BY 1, 2
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        publicationBinding(rs, "TABLE", "table");
                    }
                }
            }
            // FOR TABLES IN SCHEMA bindings — pg_publication_namespace anchors
            // the publication to the schema itself (PostgreSQL 15+)
            if (serverMajor >= 15) {
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT p.pubname, NULL AS relname,
                               NULL AS qual, NULL AS cols
                        FROM pg_publication_namespace pn
                        JOIN pg_namespace n ON n.oid = pn.pnnspid AND n.nspname = ?
                        JOIN pg_publication p ON p.oid = pn.pnpubid
                        ORDER BY 1
                        """)) {
                    ps.setString(1, ns);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            publicationBinding(rs, "TABLES IN SCHEMA", "schema");
                        }
                    }
                }
            }
            // casts have no namespace; they are visible through a namespace
            // source/target type or cast function (a cast between two foreign
            // types has no namespace anchor and stays outside the model).
            // A cast anchored to a managed object becomes a managed CAST —
            // its identity is the (source AS target) pair and foreign types
            // or the cast function stay cluster/namespace references in the
            // canonical; a cast anchored only to unmanaged objects stays
            // cast: OTHER
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT c.oid, c.castsource, c.casttarget, c.castfunc,
                           'cast:' || format_type(c.castsource, NULL)
                            || '->' || format_type(c.casttarget, NULL) AS name,
                           format_type(c.castsource, NULL)
                            || '->' || format_type(c.casttarget, NULL) AS bare,
                           c.castcontext::text || ' ' || c.castmethod::text || ' '
                               || CASE WHEN c.castfunc = 0 THEN 'none'
                                       ELSE c.castfunc::regprocedure::text
                                       END AS canon,
                           'CREATE CAST (' || format_type(c.castsource, NULL)
                               || ' AS ' || format_type(c.casttarget, NULL) || ') '
                               || CASE c.castmethod
                                      WHEN 'f' THEN 'WITH FUNCTION '
                                              || c.castfunc::regprocedure::text
                                      WHEN 'i' THEN 'WITH INOUT' END
                               || CASE c.castcontext
                                      WHEN 'a' THEN ' AS ASSIGNMENT'
                                      WHEN 'i' THEN ' AS IMPLICIT'
                                      ELSE '' END AS create_sql,
                           'DROP CAST (' || format_type(c.castsource, NULL)
                               || ' AS ' || format_type(c.casttarget, NULL)
                               || ')' AS drop_sql
                    FROM pg_cast c
                    WHERE EXISTS (SELECT 1 FROM pg_type t
                                  JOIN pg_namespace n ON n.oid = t.typnamespace
                                  WHERE n.nspname = ?
                                    AND t.oid IN (c.castsource, c.casttarget))
                       OR EXISTS (SELECT 1 FROM pg_proc f
                                  JOIN pg_namespace n ON n.oid = f.pronamespace
                                  WHERE n.nspname = ? AND f.oid = c.castfunc)
                    ORDER BY c.oid
                    """)) {
                bindNamespace(ps, 2);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        if (autoCasts.containsKey(oid)) {
                            // the range→multirange cast folds into the range
                            // type — its oid remaps so dependents resolve
                            otherOid("pg_cast", oid,
                                    typeByOid.get(autoCasts.get(oid)));
                            continue;
                        }
                        final var source = typeByOid.get(rs.getLong("castsource"));
                        final var target = typeByOid.get(rs.getLong("casttarget"));
                        final var func = procByOid.get(rs.getLong("castfunc"));
                        // managed only when every anchor visible in the
                        // namespace resolves managed — a cast on an
                        // unsupported rowtype cannot be created, so it
                        // stays OTHER instead of failing at apply
                        final var managed = (managedAnchor(source)
                                || managedAnchor(target) || managedAnchor(func))
                                && !(otherAnchor(source) || otherAnchor(target)
                                        || otherAnchor(func));
                        if (!managed || rs.getString("create_sql") == null) {
                            // unanchored, or a castmethod the profile cannot
                            // render (binary casts were removed in PG10)
                            final var id = new ObjectId(ObjectKind.OTHER,
                                    rs.getString("name"));
                            otherOid("pg_cast", oid, id);
                            define(id, "cast", Map.of());
                            continue;
                        }
                        final var id = new ObjectId(ObjectKind.CAST,
                                rs.getString("bare"));
                        otherOid("pg_cast", oid, id);
                        dep(id, managedAnchor(source) ? source : null);
                        dep(id, managedAnchor(target) ? target : null);
                        dep(id, managedAnchor(func) ? func : null);
                        define(id, rs.getString("canon"),
                                Map.of(CREATE_SQL, rs.getString("create_sql"),
                                        DROP_SQL, rs.getString("drop_sql")));
                    }
                }
            }
            // transforms have no namespace; anchor on the type or the
            // from/to-SQL functions. A transform's only expressible anchor
            // is the FOR type (3.2) — a transform on a managed type becomes
            // a managed TRANSFORM, its identity the type/language pair and
            // the language itself a cluster reference; anything else stays
            // transform: OTHER
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT t.oid, t.trftype, t.trffromsql, t.trftosql,
                           'transform:' || format_type(t.trftype, NULL)
                            || '/' || l.lanname AS name,
                           format_type(t.trftype, NULL)
                            || '/' || l.lanname AS bare,
                           CASE WHEN t.trffromsql = 0 THEN 'none'
                                ELSE t.trffromsql::regprocedure::text END || ' '
                            || CASE WHEN t.trftosql = 0 THEN 'none'
                                    ELSE t.trftosql::regprocedure::text
                                    END AS canon,
                           'CREATE TRANSFORM FOR '
                            || format_type(t.trftype, NULL) || ' LANGUAGE '
                            || quote_ident(l.lanname)
                            || ' ('
                            || CASE WHEN t.trffromsql = 0 THEN ''
                                    ELSE 'FROM SQL WITH FUNCTION '
                                         || t.trffromsql::regprocedure::text
                                         || CASE WHEN t.trftosql <> 0
                                                 THEN ', ' ELSE '' END
                                    END
                            || CASE WHEN t.trftosql = 0 THEN ''
                                    ELSE 'TO SQL WITH FUNCTION '
                                         || t.trftosql::regprocedure::text
                                    END
                            || ')' AS create_sql,
                           'DROP TRANSFORM FOR '
                            || format_type(t.trftype, NULL) || ' LANGUAGE '
                            || quote_ident(l.lanname) AS drop_sql
                    FROM pg_transform t JOIN pg_language l ON l.oid = t.trflang
                    WHERE EXISTS (SELECT 1 FROM pg_type ty
                                  JOIN pg_namespace n ON n.oid = ty.typnamespace
                                  WHERE n.nspname = ? AND ty.oid = t.trftype)
                       OR EXISTS (SELECT 1 FROM pg_proc f
                                  JOIN pg_namespace n ON n.oid = f.pronamespace
                                  WHERE n.nspname = ?
                                    AND f.oid IN (t.trffromsql, t.trftosql))
                    ORDER BY t.oid
                    """)) {
                bindNamespace(ps, 2);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var oid = rs.getLong("oid");
                        if (extMember(oid)) {
                            continue;
                        }
                        final var type = typeByOid.get(rs.getLong("trftype"));
                        if (!managedAnchor(type)) {
                            final var id = new ObjectId(ObjectKind.OTHER,
                                    rs.getString("name"));
                            otherOid("pg_transform", oid, id);
                            define(id, "transform", Map.of());
                            continue;
                        }
                        final var id = new ObjectId(ObjectKind.TRANSFORM,
                                rs.getString("bare"));
                        otherOid("pg_transform", oid, id);
                        dep(id, type);
                        dep(id, procByOid.get(rs.getLong("trffromsql")));
                        dep(id, procByOid.get(rs.getLong("trftosql")));
                        define(id, rs.getString("canon"),
                                Map.of(CREATE_SQL, rs.getString("create_sql"),
                                        DROP_SQL, rs.getString("drop_sql")));
                    }
                }
            }
            // languages are database-level; a language whose handlers live in
            // the namespace is part of the desired state
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT l.oid, 'language:' || l.lanname AS name
                    FROM pg_language l
                    WHERE EXISTS (SELECT 1 FROM pg_proc h
                                  JOIN pg_namespace n ON n.oid = h.pronamespace
                                  WHERE n.nspname = ?
                                    AND h.oid IN (l.lanplcallfoid, l.laninline,
                                                  l.lanvalidator))
                    ORDER BY l.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        final var id = new ObjectId(ObjectKind.OTHER,
                                rs.getString("name"));
                        langByOid.put(rs.getLong("oid"), id);
                        otherOid("pg_language", rs.getLong("oid"), id);
                        define(id, "language", Map.of());
                    }
                }
            }
            // sequence publication bindings — the pg_publication_sequences
            // view exists from PostgreSQL 19 (pg_publication_rel covers
            // tables) and also expands FOR ALL SEQUENCES flags, which have no
            // per-sequence binding to reconcile — those pubs are filtered out
            if (serverMajor >= 19) {
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT v.pubname, v.sequencename AS relname,
                               NULL AS qual, NULL AS cols
                        FROM pg_publication_sequences v
                        JOIN pg_publication p ON p.pubname = v.pubname
                         WHERE v.schemaname = ? AND NOT p.puballsequences
                        ORDER BY 1
                        """)) {
                    ps.setString(1, ns);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            publicationBinding(rs, "SEQUENCE", "sequence");
                        }
                    }
                }
            }
        }

        /**
         * One managed {@link ObjectKind#PUBLICATION} binding: name the
         * publication (a cluster-object reference, 4.1), parent the bound
         * relation, and render the {@code ALTER PUBLICATION} add/drop pair.
         * {@code verb} is the statement's object word — {@code TABLE},
         * {@code TABLES IN SCHEMA} or {@code SEQUENCE}; {@code shape} is the
         * canonical's binding discriminator.
         */
        private void publicationBinding(ResultSet rs, String verb, String shape)
                throws SQLException {
            final var pub = rs.getString("pubname");
            final var rel = rs.getString("relname");
            final var qual = rs.getString("qual");
            final var cols = rs.getString("cols");
            final var id = rel == null
                    ? new ObjectId(ObjectKind.PUBLICATION, pub)
                    : new ObjectId(ObjectKind.PUBLICATION, pub, rel);
            final var qpub = PgIdentifiers.quote(pub);
            final var create = new StringBuilder("ALTER PUBLICATION ").append(qpub)
                    .append(" ADD ").append(verb).append(' ');
            if (rel == null) {
                create.append("${namespace}");
            } else {
                create.append(tableRef(rel));
            }
            if (cols != null) {
                create.append(" (").append(Arrays.stream(cols.split(","))
                        .map(PgIdentifiers::quote)
                        .collect(Collectors.joining(","))).append(')');
            }
            if (qual != null) {
                create.append(" WHERE (").append(qual).append(')');
            }
            final var drop = new StringBuilder("ALTER PUBLICATION ").append(qpub)
                    .append(" DROP ").append(verb).append(' ')
                    .append(rel == null ? "${namespace}" : tableRef(rel));
            define(id, "binding=" + shape
                    + (qual != null ? ";filter=" + qual : "")
                    + (cols != null ? ";columns=" + cols : ""),
                    Map.of(
                            CREATE_SQL, create.toString(),
                            DROP_SQL, drop.toString()));
        }

        /**
         * Descriptions on namespace objects (pg_description). A description on
         * a managed object becomes a managed {@link ObjectKind#COMMENT} object
         * whose {@link ObjectId} mirrors the target (its name, and its parent
         * for child objects); a description on anything else stays the OTHER
         * {@code comment:<name>} object, undeclarable and reported on every
         * run. pg_extension is not in NS_OBJECTS — an extension's shipped
         * description is database-wide metadata (COMMENT ON EXTENSION is
         * rejected), and an anchored object here would poison desired state
         * for the managed EXTENSION object; the extension row itself reports.
         * Comments sharing the mirrored id (a column and a policy with the
         * same name on one table, say) merge into one COMMENT object, as on
         * both sides of the diff, so they still converge — documented in A.5.
         */
        void loadComments(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT o.cls::text AS cls, d.objoid, d.objsubid,
                           o.name AS target, a.attname, d.description
                    FROM pg_description d
                    JOIN (""" + NS_OBJECTS + """
                    ) o ON o.cls = d.classoid AND o.obj = d.objoid
                    LEFT JOIN pg_attribute a ON d.classoid = 'pg_class'::regclass
                            AND a.attrelid = d.objoid AND a.attnum = d.objsubid
                    ORDER BY 1, 2, 3
                    """)) {
                bindNamespace(ps, 17);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("objoid"))) {
                            continue;
                        }
                        final var attname = rs.getString("attname");
                        final var target = annotatedTarget(rs.getString("cls"),
                                rs.getLong("objoid"), rs.getInt("objsubid"), attname);
                        if (target != null && target.kind() != ObjectKind.OTHER
                                && objects.containsKey(target)) {
                            var cid = commentId(target, "");
                            if (objects.containsKey(cid)) {
                                // two targets share the mirrored id (a column
                                // and a same-named policy, say): the later one
                                // gains a (kind) suffix so both surface (A.5)
                                cid = commentId(target, " (" + target.kind().name()
                                        .toLowerCase(Locale.ROOT) + ")");
                            }
                            final var text = rs.getString("description");
                            define(cid, text,
                                    Map.of(CREATE_SQL, commentSql(target, text)));
                            dep(cid, target);
                        } else {
                            define(new ObjectId(ObjectKind.OTHER, "comment:"
                                    + rs.getString("target")
                                    + (attname == null ? "" : "." + attname)),
                                    "comment", Map.of());
                        }
                    }
                }
            }
        }

        /** The mirrored COMMENT id for a managed target, with the optional
         *  name suffix used to break collisions. */
        private static ObjectId commentId(ObjectId target, String suffix) {
            final var name = target.name() + suffix;
            return target.parent().isPresent()
                    ? new ObjectId(ObjectKind.COMMENT, name, target.parent().get())
                    : new ObjectId(ObjectKind.COMMENT, name);
        }

        /** The mirrored SECURITY_LABEL id for a managed target and provider,
         *  with the optional collision suffix. */
        private static ObjectId seclabelId(ObjectId target, String provider, String suffix) {
            final var name = provider + ":" + target.name() + suffix;
            return target.parent().isPresent()
                    ? new ObjectId(ObjectKind.SECURITY_LABEL, name, target.parent().get())
                    : new ObjectId(ObjectKind.SECURITY_LABEL, name);
        }

        /** The mirrored OWNERSHIP id for a managed target, with the optional
         *  collision suffix. */
        private static ObjectId ownershipId(ObjectId target, String suffix) {
            final var name = target.name() + suffix;
            return target.parent().isPresent()
                    ? new ObjectId(ObjectKind.OWNERSHIP, name, target.parent().get())
                    : new ObjectId(ObjectKind.OWNERSHIP, name);
        }

        /** A managed namespace object an anchored catalog row can belong
         *  to — a null or {@code OTHER} anchor leaves the row unsupported. */
        private static boolean managedAnchor(ObjectId id) {
            return id != null && id.kind() != ObjectKind.OTHER;
        }

        /** An in-namespace {@code OTHER} anchor — the row references an
         *  unsupported object, so it can never be created. */
        private static boolean otherAnchor(ObjectId id) {
            return id != null && id.kind() == ObjectKind.OTHER;
        }

        /**
         * A column-level grant — {@code pg_attribute.attacl} on a relation in
         * the namespace. On a managed relation it mirrors the column like a
         * comment does ({@code PRIVILEGES <col>} parented on the relation)
         * and renders through the same token diff, the column name
         * parenthesized after the privilege word; on an unmanaged relation it
         * stays {@code privileges:<rel>.<col>} OTHER, content-compared.
         */
        private void columnPrivileges(long relOid, String name, String attname,
                                      String acl) {
            final var rel = relByOid.get(relOid);
            if (rel == null || rel.kind() == ObjectKind.OTHER
                    || foldedMembers.contains(relOid)) {
                define(new ObjectId(ObjectKind.OTHER, name), "privileges=" + acl,
                        Map.of(Dialect.CONTENT_COMPARED_ATTRIBUTE, "true"));
                return;
            }
            final var pid = new ObjectId(ObjectKind.PRIVILEGES, attname,
                    rel.name());
            final var attrs = new HashMap<String, String>();
            attrs.put(GRANT_TARGET, grantTarget(rel));
            attrs.put(GRANT_COLUMN, PgIdentifiers.quote(attname));
            define(pid, "privileges=" + acl, attrs);
            // order after the column when it is a modeled object (tables and
            // partitions carry COLUMN objects; views and materialized views
            // do not) — otherwise the relation itself suffices
            final var column = new ObjectId(ObjectKind.COLUMN, attname,
                    rel.name());
            dep(pid, objects.containsKey(column) ? column : rel);
        }

        /**
         * The GRANT object-class word of a {@code pg_default_acl} objtype —
         * {@code r} tables, {@code S} sequences, {@code f} functions and
         * procedures, {@code T} types. An unknown future objtype renders no
         * statement and is skipped.
         */
        private static String defaultAclClass(String objtype) {
            return switch (objtype) {
                case "r" -> "TABLES";
                case "S" -> "SEQUENCES";
                case "f" -> "FUNCTIONS";
                case "T" -> "TYPES";
                default -> null;
            };
        }

        /** The mirrored PRIVILEGES id for a managed target, with the optional
         *  collision suffix. */
        private static ObjectId privilegesId(ObjectId target, String suffix) {
            final var name = target.name() + suffix;
            return target.parent().isPresent()
                    ? new ObjectId(ObjectKind.PRIVILEGES, name, target.parent().get())
                    : new ObjectId(ObjectKind.PRIVILEGES, name);
        }

        /**
         * The {@code <KEYWORD> ${namespace}.<name>} target phrase of a
         * {@code GRANT|REVOKE ... ON} statement for a managed target — the
         * relation kinds grant through {@code TABLE} except foreign tables,
         * which use their own keyword; routines reuse the annotation phrase
         * ({@code FUNCTION|PROCEDURE name(sigargs)}).
         */
        private String grantTarget(ObjectId target) {
            final var n = "${namespace}";
            final var name = PgIdentifiers.quote(target.name());
            return switch (target.kind()) {
                case TABLE, PARTITION, VIEW, MATERIALIZED_VIEW ->
                        "TABLE " + n + "." + name;
                case FOREIGN_TABLE -> "FOREIGN TABLE " + n + "." + name;
                case SEQUENCE -> "SEQUENCE " + n + "." + name;
                case TYPE -> "TYPE " + n + "." + name;
                case DOMAIN -> "DOMAIN " + n + "." + name;
                case ROUTINE -> routineAnnotationTarget(target);
                case AGGREGATE -> routineAnnotationTarget(target, "FUNCTION");
                default -> throw new IllegalStateException(
                        "no grant form for " + target.kind());
            };
        }

        /** The managed object an annotation row (comment or security label)
         *  targets, or null when the annotated catalog entry maps to no
         *  managed object. */
        private ObjectId annotatedTarget(String cls, long objoid, int objsubid,
                                       String attname) {
            switch (cls) {
                case "pg_class":
                    if (objsubid == 0) {
                        return relByOid.get(objoid);
                    }
                    // column annotations resolve where columns are managed
                    // objects — tables, partitions and foreign tables all
                    // carry them parented on the relation
                    final var rel = relByOid.get(objoid);
                    return attname != null && rel != null
                            && (rel.kind() == ObjectKind.TABLE
                                    || rel.kind() == ObjectKind.PARTITION
                                    || rel.kind() == ObjectKind.FOREIGN_TABLE)
                            ? new ObjectId(ObjectKind.COLUMN, attname, rel.name())
                            : null;
                case "pg_proc":
                    // a folded constructor has no annotation target of its
                    // own — a comment or label on it stays OTHER
                    return foldedMembers.contains(objoid) ? null
                            : procByOid.get(objoid);
                case "pg_constraint":
                    return conByOid.get(objoid);
                case "pg_policy":
                    return polByOid.get(objoid);
                case "pg_statistic_ext":
                    return stxByOid.get(objoid);
                case "pg_rewrite":
                    return ruleByOid.get(objoid);
                case "pg_collation":
                    return collByOid.get(objoid);
                case "pg_conversion":
                    return convByOid.get(objoid);
                case "pg_type":
                    // a folded multirange likewise stays OTHER
                    return foldedMembers.contains(objoid) ? null
                            : typeByOid.get(objoid);
                case "pg_trigger":
                    return tgByOid.get(objoid);
                case "pg_ts_template":
                    return tmplByOid.get(objoid);
                case "pg_ts_parser":
                    return prsByOid.get(objoid);
                case "pg_ts_dict":
                    return dictByOid.get(objoid);
                case "pg_ts_config":
                    return cfgByOid.get(objoid);
                case "pg_operator":
                    return oprByOid.get(objoid);
                case "pg_opclass":
                    return opcByOid.get(objoid);
                case "pg_opfamily":
                    return opfByOid.get(objoid);
                default:
                    return null;
            }
        }

        /** The {@code COMMENT ON <target> IS 'text'} statement for a managed target. */
        private String commentSql(ObjectId target, String text) {
            return "COMMENT ON " + annotationTarget(target) + " IS " + sqlLiteral(text);
        }

        /**
         * The {@code <KEYWORD> <name>} target phrase shared by
         * {@code COMMENT ON} and {@code SECURITY LABEL ... ON}.
         */
        private String annotationTarget(ObjectId target) {
            final var n = "${namespace}";
            final var name = PgIdentifiers.quote(target.name());
            final var parent = target.parent().orElse(null);
            final var qParent = parent == null
                    ? null : n + "." + PgIdentifiers.quote(parent);
            return switch (target.kind()) {
                case TABLE, PARTITION -> "TABLE " + n + "." + name;
                case FOREIGN_TABLE -> "FOREIGN TABLE " + n + "." + name;
                case VIEW -> "VIEW " + n + "." + name;
                case MATERIALIZED_VIEW -> "MATERIALIZED VIEW " + n + "." + name;
                case SEQUENCE -> "SEQUENCE " + n + "." + name;
                case INDEX -> "INDEX " + n + "." + name;
                case COLUMN -> "COLUMN " + qParent + "." + name;
                case PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK, EXCLUSION ->
                        "CONSTRAINT " + name + " ON " + qParent;
                case TRIGGER -> "TRIGGER " + name + " ON " + qParent;
                case POLICY -> "POLICY " + name + " ON " + qParent;
                case STATISTICS -> "STATISTICS " + n + "." + name;
                case RULE -> "RULE " + name + " ON " + qParent;
                case COLLATION -> "COLLATION " + n + "." + name;
                case CONVERSION -> "CONVERSION " + n + "." + name;
                case TYPE -> "TYPE " + n + "." + name;
                case DOMAIN -> "DOMAIN " + n + "." + name;
                case ROUTINE -> routineAnnotationTarget(target);
                case AGGREGATE -> routineAnnotationTarget(target, "AGGREGATE");
                case TEXT_SEARCH_TEMPLATE ->
                        "TEXT SEARCH TEMPLATE " + n + "." + name;
                case TEXT_SEARCH_PARSER ->
                        "TEXT SEARCH PARSER " + n + "." + name;
                case TEXT_SEARCH_DICTIONARY ->
                        "TEXT SEARCH DICTIONARY " + n + "." + name;
                case TEXT_SEARCH_CONFIGURATION ->
                        "TEXT SEARCH CONFIGURATION " + n + "." + name;
                // an operator name is a symbol plus signature — never an
                // identifier to quote: OPERATOR ns.=(integer,integer)
                case OPERATOR -> "OPERATOR " + n + "." + target.name();
                // the id name is "<name> USING <am>" — only the name
                // part is schema-qualified and quoted
                case OPERATOR_CLASS, OPERATOR_FAMILY -> {
                    final var i = target.name().indexOf(" USING ");
                    yield (target.kind() == ObjectKind.OPERATOR_CLASS
                            ? "OPERATOR CLASS " : "OPERATOR FAMILY ")
                            + n + "." + PgIdentifiers.quote(target.name().substring(0, i))
                            + target.name().substring(i);
                }
                default -> throw new IllegalStateException(
                        "no annotation form for " + target.kind());
            };
        }

        /** {@code FUNCTION|PROCEDURE <name>(<sigargs>)}. */
        private String routineAnnotationTarget(ObjectId target) {
            final var o = objects.get(target);
            final var kind = o.attrs.getOrDefault(PROKIND, "f").equals("p")
                    ? "PROCEDURE" : "FUNCTION";
            return routineAnnotationTarget(target, kind);
        }

        /** {@code <word> <name>(<sigargs>)} — {@code FUNCTION} for a grant
         *  on an aggregate, {@code AGGREGATE} for its annotation/owner
         *  forms. */
        private String routineAnnotationTarget(ObjectId target, String kind) {
            final var o = objects.get(target);
            // the id name was built as proname + "(" + sigargs + ")" — strip
            // that exact suffix so a quoted proname containing '(' survives
            final var sigargs = o.attrs.getOrDefault(SIG_ARGS, "");
            final var suffix = "(" + sigargs + ")";
            final var fname = target.name().endsWith(suffix)
                    ? target.name().substring(0, target.name().length() - suffix.length())
                    : target.name();
            return kind + " ${namespace}."
                    + PgIdentifiers.quote(fname) + "(" + sigargs + ")";
        }

        void loadDependencies(Connection conn) throws SQLException {
            // view dependencies via the _RETURN rewrite rule
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT DISTINCT v.oid AS viewer, d.refobjid AS dep
                    FROM pg_rewrite r
                    JOIN pg_depend d ON d.classid = 'pg_rewrite'::regclass AND d.objid = r.oid
                        AND d.refclassid = 'pg_class'::regclass
                    JOIN pg_class v ON v.oid = r.ev_class AND v.relkind IN ('v','m')
                    JOIN pg_namespace vn ON vn.oid = v.relnamespace AND vn.nspname = ?
                    JOIN pg_class rc ON rc.oid = d.refobjid
                    JOIN pg_namespace rn ON rn.oid = rc.relnamespace AND rn.nspname = ?
                    WHERE r.rulename = '_RETURN' AND rc.oid <> v.oid
                    """)) {
                ps.setString(1, ns);
                ps.setString(2, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var viewer = relByOid.get(rs.getLong("viewer"));
                        depOid(viewer, rs.getLong("dep"));
                    }
                }
            }
            // view-on-routine and view-on-type dependencies
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT DISTINCT v.oid AS viewer, d.refobjid AS dep
                    FROM pg_rewrite r
                    JOIN pg_depend d ON d.classid = 'pg_rewrite'::regclass AND d.objid = r.oid
                        AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                           'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                           'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)
                    JOIN pg_class v ON v.oid = r.ev_class AND v.relkind IN ('v','m')
                    JOIN pg_namespace vn ON vn.oid = v.relnamespace AND vn.nspname = ?
                    WHERE r.rulename = '_RETURN'
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var viewer = relByOid.get(rs.getLong("viewer"));
                        depOid(viewer, rs.getLong("dep"));
                    }
                }
            }
            // routine dependencies on relations, routines and types
            // (SQL-language bodies and argument/return types)
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT DISTINCT d.objid AS caller, d.refobjid AS dep
                    FROM pg_depend d
                    JOIN pg_proc p ON p.oid = d.objid
                    JOIN pg_namespace pn ON pn.oid = p.pronamespace AND pn.nspname = ?
                    WHERE d.classid = 'pg_proc'::regclass
                      AND d.refclassid IN ('pg_class'::regclass, 'pg_proc'::regclass,
                                           'pg_type'::regclass, 'pg_language'::regclass,
                                           'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                           'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                           'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var caller = procByOid.get(rs.getLong("caller"));
                        if (caller != null) {
                            depOid(caller, rs.getLong("dep"));
                        }
                    }
                }
            }
            // index-on-routine dependencies (expression indexes)
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT DISTINCT i.indexrelid AS idx, d.refobjid AS dep
                    FROM pg_depend d
                    JOIN pg_class ic ON ic.oid = d.objid AND ic.relkind = 'i'
                    JOIN pg_index i ON i.indexrelid = ic.oid
                    JOIN pg_namespace n ON n.oid = ic.relnamespace AND n.nspname = ?
                    JOIN pg_proc p ON p.oid = d.refobjid
                    JOIN pg_namespace pn ON pn.oid = p.pronamespace AND pn.nspname = ?
                    WHERE d.classid = 'pg_class'::regclass AND d.refclassid = 'pg_proc'::regclass
                    """)) {
                ps.setString(1, ns);
                ps.setString(2, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var idx = relByOid.get(rs.getLong("idx"));
                        final var proc = procByOid.get(rs.getLong("dep"));
                        dep(idx, proc);
                    }
                }
            }
            // column defaults on our tables -> sequences, routines and types
            // (the edge lands on the column and on its parent table, so that a
            // covered column inside a CREATE TABLE still orders after the ref)
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT ad.adrelid AS tbl, a.attname AS col, d.refobjid AS dep
                    FROM pg_depend d
                    JOIN pg_attrdef ad ON ad.oid = d.objid
                    JOIN pg_class tc ON tc.oid = ad.adrelid
                    JOIN pg_namespace tn ON tn.oid = tc.relnamespace AND tn.nspname = ?
                    JOIN pg_attribute a ON a.attrelid = ad.adrelid AND a.attnum = ad.adnum
                        AND NOT a.attisdropped
                    WHERE d.classid = 'pg_attrdef'::regclass AND d.deptype = 'n'
                      AND d.refclassid IN ('pg_class'::regclass, 'pg_proc'::regclass,
                                           'pg_type'::regclass,
                                           'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                           'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                           'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var table = relByOid.get(rs.getLong("tbl"));
                        if (table == null) {
                            continue;
                        }
                        final var column = new ObjectId(ObjectKind.COLUMN,
                                rs.getString("col"), table.name());
                        final var ref = byOid(rs.getLong("dep"));
                        dep(column, ref);
                        depTableForCoveredChild(table, ref);
                    }
                }
            }
            // constraints on our tables -> routines, types, the unique
            // constraint backing an FK (a pg_class ref to its index), and
            // other constraints
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT d.objid AS con, d.refclassid::regclass::text AS refcls,
                           d.refobjid AS dep
                    FROM pg_depend d
                    JOIN pg_constraint co ON co.oid = d.objid
                    JOIN pg_class cr ON cr.oid = co.conrelid
                    JOIN pg_namespace cn ON cn.oid = cr.relnamespace AND cn.nspname = ?
                    WHERE d.classid = 'pg_constraint'::regclass
                      AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,
                                           'pg_class'::regclass, 'pg_constraint'::regclass,
                                           'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                           'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                           'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var from = conByOid.get(rs.getLong("con"));
                        final var ref = rs.getLong("dep");
                        final var to = switch (rs.getString("refcls")) {
                            case "pg_constraint" -> conByOid.get(ref);
                            case "pg_class" -> {
                                final var viaIndex = conByIndexOid.get(ref);
                                yield viaIndex != null ? viaIndex : relByOid.get(ref);
                            }
                            default -> byOid(ref);
                        };
                        dep(from, to);
                    }
                }
            }
            // domains and ranges -> their base/subtype, canonical and
            // subtype-diff routines; domain check constraints -> the
            // routines, types and relations their expressions use
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT d.objid AS dom, d.refobjid AS dep
                    FROM pg_depend d
                    JOIN pg_type t ON t.oid = d.objid AND t.typtype IN ('d','r')
                    JOIN pg_namespace n ON n.oid = t.typnamespace AND n.nspname = ?
                    WHERE d.classid = 'pg_type'::regclass
                      AND d.refclassid IN ('pg_type'::regclass, 'pg_proc'::regclass,'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                           'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                           'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)
                    UNION
                    SELECT c.contypid, d.refobjid
                    FROM pg_depend d
                    JOIN pg_constraint c ON c.oid = d.objid AND c.contypid <> 0
                    JOIN pg_type t ON t.oid = c.contypid
                    JOIN pg_namespace n ON n.oid = t.typnamespace AND n.nspname = ?
                    WHERE d.classid = 'pg_constraint'::regclass
                      AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,
                                           'pg_class'::regclass,
                                           'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                           'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                           'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)
                    """)) {
                ps.setString(1, ns);
                ps.setString(2, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        depOid(typeByOid.get(rs.getLong("dom")), rs.getLong("dep"));
                    }
                }
            }
            // policies -> the routines, types and relations their
            // USING / WITH CHECK expressions reference
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT d.objid AS pol, d.refobjid AS dep
                    FROM pg_depend d
                    JOIN pg_policy po ON po.oid = d.objid
                    JOIN pg_class c ON c.oid = po.polrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
                    WHERE d.classid = 'pg_policy'::regclass
                      AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,
                                           'pg_class'::regclass,
                                           'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                           'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                           'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        depOid(polByOid.get(rs.getLong("pol")), rs.getLong("dep"));
                    }
                }
            }
            // column types and collations on our tables -> our domains, enums
            // and collations (objsubid is the column's attnum; a zero objsubid
            // is an object-level edge)
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT d.objid AS tbl, d.objsubid AS attnum, a.attname AS col,
                           d.refobjid AS dep
                    FROM pg_depend d
                    JOIN pg_class tc ON tc.oid = d.objid
                    JOIN pg_namespace tn ON tn.oid = tc.relnamespace AND tn.nspname = ?
                    LEFT JOIN pg_attribute a ON a.attrelid = d.objid AND a.attnum = d.objsubid
                        AND NOT a.attisdropped
                    WHERE d.classid = 'pg_class'::regclass AND d.deptype = 'n'
                      AND d.refclassid IN ('pg_type'::regclass, 'pg_collation'::regclass,'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                           'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                           'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var table = relByOid.get(rs.getLong("tbl"));
                        if (table == null) {
                            continue;
                        }
                        final var depOid = rs.getLong("dep");
                        var ref = byOid(depOid); // resolved twice: collByOid is the fallback
                        if (ref == null) {
                            ref = collByOid.get(depOid);
                        }
                        final var col = rs.getString("col");
                        if (col != null) {
                            dep(new ObjectId(ObjectKind.COLUMN, col, table.name()), ref);
                        }
                        depTableForCoveredChild(table, ref);
                    }
                }
            }
            // partition children (tables and indexes) -> their parents
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT d.objid AS child, d.refobjid AS dep
                    FROM pg_depend d
                    JOIN pg_class cc ON cc.oid = d.objid
                    JOIN pg_namespace cn ON cn.oid = cc.relnamespace AND cn.nspname = ?
                    WHERE d.classid = 'pg_class'::regclass AND d.refclassid = 'pg_class'::regclass
                      AND d.deptype IN ('a', 'P')
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        depOid(relByOid.get(rs.getLong("child")), rs.getLong("dep"));
                    }
                }
            }
            // unsupported objects -> the managed objects they depend on.
            // An OTHER object can never be recreated, so a dependent one must
            // block a drop or recreate of what it uses (5.8.3): a rule or an
            // exclusion constraint dies with its table, a cast or a range on
            // our type blocks dropping it, a language blocks its handlers.
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT d.classid::regclass::text AS cls, d.objid,
                           d.refclassid::regclass::text AS refcls, d.refobjid AS dep
                    FROM pg_depend d
                    WHERE d.classid IN ('pg_type'::regclass,'pg_language'::regclass,
                                        'pg_rewrite'::regclass,'pg_statistic_ext'::regclass,
                                        'pg_cast'::regclass,'pg_transform'::regclass,
                                        'pg_conversion'::regclass,'pg_operator'::regclass,
                                        'pg_opclass'::regclass,'pg_opfamily'::regclass,
                                        'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                        'pg_ts_parser'::regclass,'pg_ts_template'::regclass)
                      AND d.refclassid IN ('pg_class'::regclass,'pg_proc'::regclass,
                                           'pg_type'::regclass,'pg_collation'::regclass,
                                           'pg_language'::regclass,'pg_constraint'::regclass,
                                           'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                           'pg_ts_parser'::regclass,'pg_ts_template'::regclass)
                    """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var map = otherByOid.get(rs.getString("cls"));
                        final var from = map == null ? null : map.get(rs.getLong("objid"));
                        if (from == null) {
                            continue;
                        }
                        final var ref = rs.getLong("dep");
                        final var to = switch (rs.getString("refcls")) {
                            case "pg_constraint" -> conByOid.get(ref);
                            case "pg_collation" -> collByOid.get(ref);
                            case "pg_ts_config" -> cfgByOid.get(ref);
                            case "pg_ts_dict" -> dictByOid.get(ref);
                            case "pg_ts_parser" -> prsByOid.get(ref);
                            case "pg_ts_template" -> tmplByOid.get(ref);
                            default -> byOid(ref);
                        };
                        dep(from, to);
                    }
                }
            }
        }

        void loadExternalDependents(Connection conn) throws SQLException {
            final var sql = """
                    SELECT DISTINCT d.refobjid AS tgt, rn.nspname AS depns, rc.relname AS depname
                    FROM pg_depend d
                    JOIN pg_rewrite r ON r.oid = d.objid
                    JOIN pg_class rc ON rc.oid = r.ev_class
                    JOIN pg_namespace rn ON rn.oid = rc.relnamespace
                    JOIN pg_class tgt ON tgt.oid = d.refobjid
                    JOIN pg_namespace tn ON tn.oid = tgt.relnamespace AND tn.nspname = ?
                    WHERE d.classid = 'pg_rewrite'::regclass AND d.refclassid = 'pg_class'::regclass
                      AND rn.nspname <> ?
                    UNION
                    SELECT DISTINCT d.refobjid, rn.nspname, rc.relname
                    FROM pg_depend d
                    JOIN pg_constraint co ON co.oid = d.objid
                    JOIN pg_class rc ON rc.oid = co.conrelid
                    JOIN pg_namespace rn ON rn.oid = rc.relnamespace
                    JOIN pg_class tgt ON tgt.oid = d.refobjid
                    JOIN pg_namespace tn ON tn.oid = tgt.relnamespace AND tn.nspname = ?
                    WHERE d.classid = 'pg_constraint'::regclass AND d.refclassid = 'pg_class'::regclass
                      AND rn.nspname <> ?
                    UNION
                    SELECT DISTINCT d.refobjid, sn.nspname, sq.relname
                    FROM pg_depend d
                    JOIN pg_class sq ON sq.oid = d.objid AND sq.relkind = 'S'
                    JOIN pg_namespace sn ON sn.oid = sq.relnamespace
                    JOIN pg_class tgt ON tgt.oid = d.refobjid
                    JOIN pg_namespace tn ON tn.oid = tgt.relnamespace AND tn.nspname = ?
                    WHERE d.classid = 'pg_class'::regclass AND d.refclassid = 'pg_class'::regclass
                      AND d.deptype = 'a' AND sn.nspname <> ?
                    UNION
                    SELECT DISTINCT d.refobjid, pn.nspname, p.proname
                    FROM pg_depend d
                    JOIN pg_proc p ON p.oid = d.objid
                    JOIN pg_namespace pn ON pn.oid = p.pronamespace
                    WHERE d.classid = 'pg_proc'::regclass
                      AND d.refclassid IN ('pg_class'::regclass, 'pg_proc'::regclass,
                                           'pg_type'::regclass, 'pg_language'::regclass)
                      AND pn.nspname <> ?
                    UNION
                    SELECT DISTINCT d.refobjid, rn.nspname, rc.relname
                    FROM pg_depend d
                    JOIN pg_rewrite r ON r.oid = d.objid
                    JOIN pg_class rc ON rc.oid = r.ev_class
                    JOIN pg_namespace rn ON rn.oid = rc.relnamespace
                    WHERE d.classid = 'pg_rewrite'::regclass
                      AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass)
                      AND rn.nspname <> ?
                    UNION
                    SELECT DISTINCT d.refobjid, rn.nspname, rc.relname || '.' || t.tgname
                    FROM pg_depend d
                    JOIN pg_trigger t ON t.oid = d.objid
                    JOIN pg_class rc ON rc.oid = t.tgrelid
                    JOIN pg_namespace rn ON rn.oid = rc.relnamespace
                    WHERE d.classid = 'pg_trigger'::regclass
                      AND d.refclassid = 'pg_proc'::regclass AND rn.nspname <> ?
                    UNION
                    SELECT DISTINCT d.refobjid, rn.nspname, rc.relname || '.' || a.attname
                    FROM pg_depend d
                    JOIN pg_attrdef ad ON ad.oid = d.objid
                    JOIN pg_attribute a ON a.attrelid = ad.adrelid AND a.attnum = ad.adnum
                        AND NOT a.attisdropped
                    JOIN pg_class rc ON rc.oid = ad.adrelid
                    JOIN pg_namespace rn ON rn.oid = rc.relnamespace
                    WHERE d.classid = 'pg_attrdef'::regclass AND d.deptype = 'n'
                      AND d.refclassid IN ('pg_class'::regclass, 'pg_proc'::regclass,
                                           'pg_type'::regclass)
                      AND rn.nspname <> ?
                    UNION
                    SELECT DISTINCT d.refobjid, rn.nspname, co.conname
                    FROM pg_depend d
                    JOIN pg_constraint co ON co.oid = d.objid
                    JOIN pg_class rc ON rc.oid = co.conrelid
                    JOIN pg_namespace rn ON rn.oid = rc.relnamespace
                    WHERE d.classid = 'pg_constraint'::regclass
                      AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass)
                      AND rn.nspname <> ?
                    UNION
                    SELECT DISTINCT d.refobjid, rn.nspname, ic.relname
                    FROM pg_depend d
                    JOIN pg_class ic ON ic.oid = d.objid AND ic.relkind = 'i'
                    JOIN pg_namespace rn ON rn.oid = ic.relnamespace
                    WHERE d.classid = 'pg_class'::regclass
                      AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass)
                      AND rn.nspname <> ?
                    UNION
                    -- columns (objsubid > 0) and typed tables (objsubid = 0) in
                    -- other namespaces typed by or collated with our objects
                    SELECT DISTINCT d.refobjid, rn.nspname,
                           rc.relname || CASE WHEN a.attname IS NULL THEN ''
                                              ELSE '.' || a.attname END
                    FROM pg_depend d
                    JOIN pg_class rc ON rc.oid = d.objid
                    JOIN pg_namespace rn ON rn.oid = rc.relnamespace AND rn.nspname <> ?
                    LEFT JOIN pg_attribute a ON a.attrelid = d.objid
                        AND a.attnum = d.objsubid AND a.attnum > 0
                        AND NOT a.attisdropped
                    WHERE d.classid = 'pg_class'::regclass AND d.deptype = 'n'
                      AND d.refclassid IN ('pg_type'::regclass, 'pg_collation'::regclass)
                    UNION
                    -- types (domains, ranges, base types) in other namespaces
                    -- built on ours or calling our routines in defaults
                    SELECT DISTINCT d.refobjid, tn.nspname, t.typname
                    FROM pg_depend d
                    JOIN pg_type t ON t.oid = d.objid
                    JOIN pg_namespace tn ON tn.oid = t.typnamespace AND tn.nspname <> ?
                    WHERE d.classid = 'pg_type'::regclass
                      AND d.refclassid IN ('pg_type'::regclass, 'pg_proc'::regclass,
                                           'pg_collation'::regclass)
                    UNION
                    -- check constraints on other namespaces' domains whose
                    -- expressions use our objects
                    SELECT DISTINCT d.refobjid, dn.nspname,
                           dt.typname || '.' || c.conname
                    FROM pg_depend d
                    JOIN pg_constraint c ON c.oid = d.objid AND c.contypid <> 0
                    JOIN pg_type dt ON dt.oid = c.contypid
                    JOIN pg_namespace dn ON dn.oid = dt.typnamespace AND dn.nspname <> ?
                    WHERE d.classid = 'pg_constraint'::regclass
                      AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,
                                           'pg_class'::regclass)
                    UNION
                    -- policies on other namespaces' tables whose expressions
                    -- use our objects
                    SELECT DISTINCT d.refobjid, rn.nspname,
                           rc.relname || '.' || po.polname
                    FROM pg_depend d
                    JOIN pg_policy po ON po.oid = d.objid
                    JOIN pg_class rc ON rc.oid = po.polrelid
                    JOIN pg_namespace rn ON rn.oid = rc.relnamespace AND rn.nspname <> ?
                    WHERE d.classid = 'pg_policy'::regclass
                      AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,
                                           'pg_class'::regclass)
                    UNION
                    -- dependents in other namespaces on our text-search and
                    -- operator objects — views, column defaults, and the
                    -- families' own member rows (an external dictionary on our
                    -- template, an external family member on our operator);
                    -- no tgt join: byOid resolves in-namespace refs only
                    SELECT DISTINCT d.refobjid, rn.nspname, rc.relname
                    FROM pg_depend d
                    JOIN pg_rewrite r ON r.oid = d.objid
                    JOIN pg_class rc ON rc.oid = r.ev_class
                    JOIN pg_namespace rn ON rn.oid = rc.relnamespace AND rn.nspname <> ?
                    WHERE d.classid = 'pg_rewrite'::regclass
                      AND d.refclassid IN ('pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                           'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                           'pg_operator'::regclass,'pg_opclass'::regclass,
                                           'pg_opfamily'::regclass)
                    UNION
                    SELECT DISTINCT d.refobjid, rn.nspname,
                           rc.relname || '.' || a.attname
                    FROM pg_depend d
                    JOIN pg_attrdef ad ON ad.oid = d.objid
                    JOIN pg_attribute a ON a.attrelid = ad.adrelid AND a.attnum = ad.adnum
                        AND NOT a.attisdropped
                    JOIN pg_class rc ON rc.oid = ad.adrelid
                    JOIN pg_namespace rn ON rn.oid = rc.relnamespace AND rn.nspname <> ?
                    WHERE d.classid = 'pg_attrdef'::regclass AND d.deptype = 'n'
                      AND d.refclassid IN ('pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                           'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                           'pg_operator'::regclass,'pg_opclass'::regclass,
                                           'pg_opfamily'::regclass)
                    UNION
                    SELECT DISTINCT d.refobjid, tdn.nspname, td.dictname
                    FROM pg_depend d
                    JOIN pg_ts_dict td ON td.oid = d.objid
                    JOIN pg_namespace tdn ON tdn.oid = td.dictnamespace AND tdn.nspname <> ?
                    WHERE d.classid = 'pg_ts_dict'::regclass
                      AND d.refclassid IN ('pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                           'pg_ts_parser'::regclass,'pg_ts_template'::regclass)
                    UNION
                    -- config mappings record as pg_ts_config -> pg_ts_dict
                    SELECT DISTINCT d.refobjid, cn.nspname, c.cfgname
                    FROM pg_depend d
                    JOIN pg_ts_config c ON c.oid = d.objid
                    JOIN pg_namespace cn ON cn.oid = c.cfgnamespace AND cn.nspname <> ?
                    WHERE d.classid = 'pg_ts_config'::regclass
                      AND d.refclassid IN ('pg_ts_dict'::regclass,'pg_ts_parser'::regclass)
                    UNION
                    SELECT DISTINCT d.refobjid, ocn.nspname, oc.opcname
                    FROM pg_depend d
                    JOIN pg_opclass oc ON oc.oid = d.objid
                    JOIN pg_namespace ocn ON ocn.oid = oc.opcnamespace AND ocn.nspname <> ?
                    WHERE d.classid = 'pg_opclass'::regclass
                      AND d.refclassid IN ('pg_opfamily'::regclass,'pg_operator'::regclass,
                                           'pg_type'::regclass)
                    UNION
                    SELECT DISTINCT d.refobjid, fn.nspname, f.opfname
                    FROM pg_depend d
                    JOIN pg_amop m ON m.oid = d.objid
                    JOIN pg_opfamily f ON f.oid = m.amopfamily
                    JOIN pg_namespace fn ON fn.oid = f.opfnamespace AND fn.nspname <> ?
                    WHERE d.classid = 'pg_amop'::regclass
                      AND d.refclassid IN ('pg_opfamily'::regclass,'pg_operator'::regclass)
                    UNION
                    SELECT DISTINCT d.refobjid, fn.nspname, f.opfname
                    FROM pg_depend d
                    JOIN pg_amproc m ON m.oid = d.objid
                    JOIN pg_opfamily f ON f.oid = m.amprocfamily
                    JOIN pg_namespace fn ON fn.oid = f.opfnamespace AND fn.nspname <> ?
                    WHERE d.classid = 'pg_amproc'::regclass
                      AND d.refclassid IN ('pg_opfamily'::regclass,'pg_proc'::regclass)
                    UNION
                    SELECT DISTINCT d.refobjid, ons.nspname, o.oprname
                    FROM pg_depend d
                    JOIN pg_operator o ON o.oid = d.objid
                    JOIN pg_namespace ons ON ons.oid = o.oprnamespace AND ons.nspname <> ?
                    WHERE d.classid = 'pg_operator'::regclass
                      AND d.refclassid IN ('pg_operator'::regclass,'pg_proc'::regclass,
                                           'pg_type'::regclass)
                    UNION
                    SELECT DISTINCT d.refobjid, ofn.nspname, of.opfname
                    FROM pg_depend d
                    JOIN pg_opfamily of ON of.oid = d.objid
                    JOIN pg_namespace ofn ON ofn.oid = of.opfnamespace AND ofn.nspname <> ?
                    WHERE d.classid = 'pg_opfamily'::regclass
                      AND d.refclassid = 'pg_opfamily'::regclass
                    UNION
                    -- casts not anchored to this namespace (by function or by
                    -- either type — the loadOther anchor rule) that still use
                    -- our types or routines
                    SELECT DISTINCT d.refobjid,
                           coalesce(pn.nspname, sn.nspname, dtn.nspname, '-'),
                           'cast(' || ca.castsource::regtype::text || ' AS '
                                  || ca.casttarget::regtype::text || ')'
                    FROM pg_depend d
                    JOIN pg_cast ca ON ca.oid = d.objid
                    LEFT JOIN pg_proc p ON p.oid = ca.castfunc
                    LEFT JOIN pg_namespace pn ON pn.oid = p.pronamespace
                    JOIN pg_type st ON st.oid = ca.castsource
                    JOIN pg_namespace sn ON sn.oid = st.typnamespace
                    JOIN pg_type tt ON tt.oid = ca.casttarget
                    JOIN pg_namespace dtn ON dtn.oid = tt.typnamespace
                    WHERE d.classid = 'pg_cast'::regclass
                      AND pn.nspname IS DISTINCT FROM ?
                      AND sn.nspname IS DISTINCT FROM ?
                      AND dtn.nspname IS DISTINCT FROM ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (var i = 1; i <= 28; i++) {
                    ps.setString(i, ns);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var target = byOid(rs.getLong("tgt"));
                        if (target == null) {
                            continue;
                        }
                        final var phantom = new ObjectId(ObjectKind.OTHER,
                                rs.getString("depns") + "." + rs.getString("depname"));
                        final var o = obj(phantom);
                        if (o.def == null) {
                            o.def = new Definition("");
                            o.attrs.put(Dialect.EXTERNAL_DEPENDENT_ATTRIBUTE, "true");
                        }
                        dep(phantom, target);
                    }
                }
            }
        }

        /**
         * SQL for a deterministic rendering of an ACL column: one
         * {@code grantee:PRIVILEGE[*]} token per distinct entry (PUBLIC for
         * grantee 0, {@code <owner>} for the object's owner, {@code *} for
         * grantable), sorted, so grant order cannot cause drift. The
         * grantor is dropped — who made a grant is provenance, not desired
         * state: a grant recorded under a different grantor is the same
         * held privilege, and comparing it would drift forever (a
         * REVOKE+GRANT pair cannot rename the grantor anyway).
         */
        private static String aclTokens(String acl, String owner) {
            return "(SELECT string_agg(tok, ',' ORDER BY tok) FROM (SELECT DISTINCT"
                    + " CASE WHEN a.grantee = 0 THEN 'PUBLIC'"
                    + " WHEN a.grantee = " + owner + " THEN '<owner>'"
                    + " ELSE a.grantee::regrole::text END || ':' || a.privilege_type ||"
                    + " CASE WHEN a.is_grantable THEN '*' ELSE '' END AS tok"
                    + " FROM aclexplode(" + acl + ") a) toks)";
        }

        // ---- helpers ----------------------------------------------------

        private String tableRef(String name) {
            return "${namespace}." + PgIdentifiers.quote(name);
        }

        private String seqRef(ObjectId id) {
            return "${namespace}." + PgIdentifiers.quote(id.name());
        }

        private static List<String> reloptions(String text) {
            if (text == null || text.length() < 3) {
                return List.of();
            }
            final var inner = text.substring(1, text.length() - 1);
            return Arrays.stream(inner.split(","))
                    .map(String::strip)
                    .sorted()
                    .distinct()
                    .toList();
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s;
        }

        /** A single-quoted SQL literal for a canonical or DDL fragment. */
        private static String sqlLiteral(String value) {
            return "'" + value.replace("'", "''") + "'";
        }

        /**
         * 3.1 dump(): fills {@link #DUMP_SQL} — each object's complete
         * {@code ${namespace}}-token create fragment list, joined by U+0000
         * (impossible in PostgreSQL text). A table's fragment folds its
         * serial-owned sequences and columns the way
         * {@code PgRender.createTableStatements} does; a sequence owned by a
         * table column of this snapshot and every object without a create
         * fragment get none — they surface as comment lines in the dump.
         */
        void computeDumpSql() {
            for (final var entry : objects.entrySet()) {
                final var id = entry.getKey();
                final var o = entry.getValue();
                final var statements = switch (id.kind()) {
                    case TABLE -> tableDumpStatements(id, o);
                    case FOREIGN_TABLE -> foreignTableDumpStatements(id, o);
                    case MATERIALIZED_VIEW -> {
                        final var create = o.attrs.get(CREATE_SQL);
                        yield create == null ? List.<String>of()
                                : List.of(Boolean.parseBoolean(
                                        o.attrs.getOrDefault(POPULATED, "true"))
                                        ? create
                                        // CREATE_SQL embeds pg_get_viewdef's
                                        // trailing ';' — WITH NO DATA must
                                        // follow the query, not the ';'
                                        : PgSql.dropTerminator(create)
                                                + " WITH NO DATA");
                    }
                    case SEQUENCE -> foldedSequence(id)
                            ? List.<String>of()
                            : fragments(o, CREATE_SQL, OWNED_SQL);
                    case PRIVILEGES -> privilegeDumpStatements(o);
                    default -> fragments(o, CREATE_SQL, POST_SQL, OWNED_SQL);
                };
                if (!statements.isEmpty()) {
                    o.attrs.put(DUMP_SQL, String.join("\0", statements));
                }
            }
        }

        /**
         * The CREATE FOREIGN TABLE fragment of
         * {@code PgRender.createForeignTableStatements} in unbound token
         * form: columns folded, then {@code SERVER}/{@code OPTIONS}.
         */
        private List<String> foreignTableDumpStatements(ObjectId id, Obj table) {
            final var columns = objects.entrySet().stream()
                    .filter(e -> e.getKey().kind() == ObjectKind.COLUMN
                            && e.getKey().parent().orElse("").equals(id.name()))
                    .sorted(Comparator.comparingInt(e -> Integer.parseInt(
                            e.getValue().attrs.getOrDefault(ATTNUM, "0"))))
                    .toList();
            final var create = new StringBuilder("CREATE FOREIGN TABLE ")
                    .append(tableRef(id.name())).append(" (\n")
                    .append(columns.stream()
                            .map(c -> "    " + c.getValue().attrs.get(COLUMN_SQL))
                            .collect(Collectors.joining(",\n")))
                    .append("\n) SERVER ")
                    .append(PgIdentifiers.quote(table.attrs.get(FT_SERVER)));
            final var options = table.attrs.getOrDefault(FT_OPTIONS, "");
            if (!options.isEmpty()) {
                create.append(" OPTIONS (").append(options).append(')');
            }
            return List.of(create.toString());
        }

        /**
         * The GRANT list of a PRIVILEGES object: one statement per
         * {@code grantee:PRIVILEGE[*]} token of the canonical — a bootstrap
         * dump replays the grants the way the reconcile diff renders them.
         * Implicit {@code <owner>} rights are not grants and are skipped.
         */
        private static List<String> privilegeDumpStatements(Obj o) {
            final var target = o.attrs.get(GRANT_TARGET);
            if (target == null) {
                return List.of();
            }
            final var column = o.attrs.get(GRANT_COLUMN);
            final var colClause = column == null ? "" : " (" + column + ")";
            final var prefix = o.attrs.get(GRANT_PREFIX);
            final var pfx = prefix == null ? "" : prefix + " ";
            var c = o.def.canonical();
            c = c.startsWith("privileges=")
                    ? c.substring("privileges=".length()) : c;
            final var grants = new ArrayList<String>();
            if (!c.isEmpty()) {
                for (final var tok : c.split(",")) {
                    final var g = tok.substring(0, tok.lastIndexOf(':'));
                    if (g.equals("<owner>")) {
                        continue;
                    }
                    var p = tok.substring(tok.lastIndexOf(':') + 1);
                    final var option = p.endsWith("*");
                    if (option) {
                        p = p.substring(0, p.length() - 1);
                    }
                    grants.add(pfx + "GRANT " + p + colClause + " ON " + target + " TO "
                            + (g.equals("PUBLIC") ? g : PgIdentifiers.quote(g))
                            + (option ? " WITH GRANT OPTION" : ""));
                }
            }
            return grants;
        }

        /** A sequence folded into its owner table's create fragment (A.5). */
        private boolean foldedSequence(ObjectId id) {
            return deps.getOrDefault(id, Set.of()).stream().anyMatch(
                    dep -> dep.kind() == ObjectKind.TABLE && objects.containsKey(dep));
        }

        private static List<String> fragments(Obj o, String... keys) {
            final var out = new ArrayList<String>(keys.length);
            for (final var key : keys) {
                final var sql = o.attrs.get(key);
                if (sql != null) {
                    out.add(sql);
                }
            }
            return out;
        }

        /**
         * The folded CREATE TABLE fragment list of {@code createTableStatements}
         * in unbound token form: owned sequences first, the CREATE TABLE with
         * every column, then replica-identity/row-security and OWNED BY alters.
         */
        private List<String> tableDumpStatements(ObjectId id, Obj table) {
            final var foldedSeqs = objects.entrySet().stream()
                    .filter(e -> e.getKey().kind() == ObjectKind.SEQUENCE
                            && deps.getOrDefault(e.getKey(), Set.of()).contains(id))
                    .sorted(Map.Entry.comparingByKey(ObjectIds.ORDER))
                    .map(Map.Entry::getValue)
                    .toList();
            final var columns = objects.entrySet().stream()
                    .filter(e -> e.getKey().kind() == ObjectKind.COLUMN
                            && e.getKey().parent().orElse("").equals(id.name()))
                    .sorted(Comparator.comparingInt(e -> Integer.parseInt(
                            e.getValue().attrs.getOrDefault(ATTNUM, "0"))))
                    .toList();
            final var statements = new ArrayList<String>();
            foldedSeqs.forEach(seq -> statements.add(seq.attrs.get(CREATE_SQL)));
            final var canonical = table.def == null ? "" : table.def.canonical();
            final var create = new StringBuilder("CREATE ")
                    .append("u".equals(canonicalField(canonical, "relpersistence"))
                            ? "UNLOGGED " : "")
                    .append("TABLE ").append(tableRef(id.name())).append(" (\n")
                    .append(columns.stream()
                            .map(c -> "    " + c.getValue().attrs.get(COLUMN_SQL))
                            .collect(Collectors.joining(",\n")))
                    .append("\n)");
            final var options = table.attrs.getOrDefault(TABLE_OPTIONS, "");
            if (!options.isEmpty()) {
                create.append(" WITH (").append(options).append(')');
            }
            statements.add(create.toString());
            final var replident = switch (table.attrs.getOrDefault(REPLIDENT, "d")) {
                case "f" -> "FULL";
                case "n" -> "NOTHING";
                default -> null;
            };
            if (replident != null) {
                statements.add("ALTER TABLE " + tableRef(id.name())
                        + " REPLICA IDENTITY " + replident);
            }
            if (Boolean.parseBoolean(canonicalField(canonical, "rls"))) {
                statements.add("ALTER TABLE " + tableRef(id.name())
                        + " ENABLE ROW LEVEL SECURITY");
            }
            if (Boolean.parseBoolean(canonicalField(canonical, "forcerls"))) {
                statements.add("ALTER TABLE " + tableRef(id.name())
                        + " FORCE ROW LEVEL SECURITY");
            }
            foldedSeqs.stream()
                    .map(seq -> seq.attrs.get(OWNED_SQL))
                    .filter(Objects::nonNull)
                    .forEach(statements::add);
            return statements;
        }

        /** A scalar {@code name=value} field of a canonical definition. */
        private static String canonicalField(String canonical, String name) {
            for (final var part : PgSql.splitOutsideLiterals(canonical, ';')) {
                if (part.startsWith(name + "=")) {
                    return part.substring(name.length() + 1);
                }
            }
            return "";
        }

        SchemaSnapshot snapshot() {
            final var out = new LinkedHashMap<ObjectId, SchemaObject>();
            objects.forEach((id, o) -> out.put(id, new SchemaObject(id,
                    o.def == null ? new Definition("") : o.def,
                    deps.getOrDefault(id, Set.of()), o.attrs)));
            return SchemaSnapshot.of(out);
        }
    }

    /** Mutable per-object accumulation. */
    private static final class Obj {
        Definition def;
        final Map<String, String> attrs = new LinkedHashMap<>();
    }
}

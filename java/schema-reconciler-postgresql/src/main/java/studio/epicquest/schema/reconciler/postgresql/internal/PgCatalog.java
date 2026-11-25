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
    /** True when the index itself is a relkind 'I' partitioned index. */
    public static final String PARTITIONED_INDEX = "partitionedIndex";
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
        data.depInvalidPartitionedIndexesOnPartitions();
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("extension_owned"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("range_types"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("auto_type_procs"))) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        autoProcs.put(rs.getLong("objid"), rs.getLong("rng"));
                        foldedMembers.add(rs.getLong("objid"));
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("auto_type_casts"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("relations"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("columns"))) {
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
                        final var collation = rs.getString("collation") == null
                                ? null
                                : PgSql.normalize(rs.getString("collation"), ns);
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
                            final var method = switch (compression) {
                                case "p" -> "pglz";
                                case "l" -> "lz4";
                                case "z" -> "zstd";
                                default -> null;
                            };
                            if (method != null) {
                                sql.append(" COMPRESSION ").append(method);
                            }
                        }
                        if (customCollation && collation != null) {
                            canonical.append(";collation=").append(collation);
                            sql.append(" COLLATE ").append(collation);
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("constraints"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("indexes"))) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        final var name = rs.getString("relname");
                        final var relkind = rs.getString("relkind");
                        final var parent = rs.getString("parent");
                        final var def = rs.getString("def");
                        final var valid = rs.getBoolean("valid");
                        final var id = new ObjectId(ObjectKind.INDEX, name, parent);
                        relByOid.put(rs.getLong("oid"), id);
                        final var attrs = new LinkedHashMap<String, String>();
                        attrs.put(CREATE_SQL, "I".equals(relkind) && valid
                                ? PgIdentifiers.stripIndexOnOnly(def) : def);
                        if ("I".equals(relkind)) {
                            attrs.put(PARTITIONED_INDEX, "true");
                        }
                        attrs.put(DROP_SQL, "DROP INDEX " + qNs + "." + PgIdentifiers.quote(name));
                        if (rs.getBoolean("replident")) {
                            attrs.put(POST_SQL, "ALTER TABLE " + tableRef(parent)
                                    + " REPLICA IDENTITY USING INDEX " + PgIdentifiers.quote(name));
                        }
                        define(id, def + (valid ? "" : ";valid=false"), attrs);
                    }
                }
            }
        }

        /**
         * `CREATE INDEX ... ON ONLY` is order-sensitive: when partitions
         * already exist it leaves the partitioned parent invalid, while
         * creating it before those partitions can converge as valid once
         * they attach. Tie an intentionally invalid partitioned index to the
         * desired/actual partitions of its parent so creation order
         * preserves that distinction.
         */
        void depInvalidPartitionedIndexesOnPartitions() {
            for (final var entry : objects.entrySet()) {
                final var id = entry.getKey();
                final var object = entry.getValue();
                if (id.kind() != ObjectKind.INDEX
                        || !"true".equals(object.attrs.get(PARTITIONED_INDEX))
                        || object.def == null
                        || !object.def.canonical().endsWith(";valid=false")
                        || id.parent().isEmpty()) {
                    continue;
                }
                for (final var partition : objects.keySet()) {
                    if (partition.kind() == ObjectKind.PARTITION
                            && partition.parent().equals(id.parent())) {
                        dep(id, partition);
                    }
                }
            }
        }

        void loadViews(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("views"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("sequences"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("sequence_owners"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("routines"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("aggregates"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("triggers"))) {
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
         *  locating comments and security labels, live in annotations.sql (the
         *  `seclabels` and `comments` queries); see PgQueries. */

        private void bindNamespace(PreparedStatement ps, int count) throws SQLException {
            for (var i = 1; i <= count; i++) {
                ps.setString(i, ns);
            }
        }

        /** Enum types: the canonical definition is the ordered label list;
         *  the {@code typeByOid} entry resolves column and table dependency
         *  edges to the managed {@link ObjectKind#TYPE} object. */
        void loadEnums(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("enums"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("domain_checks"))) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        checks.computeIfAbsent(rs.getLong("dom"), k -> new ArrayList<>())
                                .add(rs.getString("conname") + "\0" + rs.getString("def"));
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("domains"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("composites"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("ranges"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("policies"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("statistics"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("rules"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("collations"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("conversions"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("ts_templates"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("ts_parsers"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("ts_dictionaries"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("ts_configurations"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("ts_config_maps"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("operators"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("opfamilies"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("opclasses"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("opclass_amop_members"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("opclass_amproc_members"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("seclabels"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("other_base_types"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("other_extensions"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("other_default_conversions"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("other_constraints"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("other_collations"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("privileges"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("default_acl"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("ownership"))) {
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
            // the reconcile unit itself: the schema's own owner and acl.
            // nspowner follows the same session-role "don't care" rule as
            // every other owner leg; nspacl is NULL until a GRANT (the
            // default acl then), and an empty acl means nothing held. Both
            // mirror onto the neutral "<namespace>" name — a managed name
            // no table, view or type can collide with (4.1) — so the real
            // schema name never enters a canonical
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("namespace_owner"))) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var owner = rs.getString("owner");
                        define(new ObjectId(ObjectKind.OWNERSHIP, "<namespace>"),
                                "owner=" + owner,
                                Map.of(CREATE_SQL, "ALTER SCHEMA ${namespace}"
                                        + " OWNER TO " + PgIdentifiers.quote(owner)));
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("namespace_acl"))) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final var acl = rs.getString("acl");
                        if (acl == null) {
                            // an acl of '{}' unnests to no rows — nothing held
                            continue;
                        }
                        define(new ObjectId(ObjectKind.PRIVILEGES, "<namespace>"),
                                "privileges=" + acl,
                                Map.of(GRANT_TARGET, "SCHEMA ${namespace}"));
                    }
                }
            }
            // publication bindings on namespace tables (the publication itself
            // is a cluster-level reference, 4.1; the FOR TABLE edge is the
            // namespace-visible part). The binding's parent is the bound
            // table, so it orders after it; filter/column lists ride in the
            // canonical (pg_publication_rel.prqual/.prattrs, PG15+)
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get(
                    serverMajor >= 15 ? "publication_rel" : "publication_rel_pre15"))) {
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
                try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("publication_namespace"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("casts"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("transforms"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("languages"))) {
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
                try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("publication_sequences"))) {
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
         * run. pg_extension is not in the comments query's object union —
         * an extension's shipped description is database-wide metadata
         * (COMMENT ON EXTENSION is
         * rejected), and an anchored object here would poison desired state
         * for the managed EXTENSION object; the extension row itself reports.
         * Comments sharing the mirrored id (a column and a policy with the
         * same name on one table, say) merge into one COMMENT object, as on
         * both sides of the diff, so they still converge — documented in A.5.
         */
        void loadComments(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("comments"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("dep_view_class"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("dep_view_other"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("dep_routine"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("dep_index_proc"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("dep_attrdef"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("dep_constraint"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("dep_type"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("dep_policy"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("dep_column_type"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("dep_partition"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("dep_other"))) {
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
            try (PreparedStatement ps = conn.prepareStatement(PgQueries.get("external_dependents"))) {
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
                for (final var tok : PgSql.splitOutsideLiterals(c, ',')) {
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
                            + (g.equals("PUBLIC") || g.startsWith("\"") ? g : PgIdentifiers.quote(g))
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

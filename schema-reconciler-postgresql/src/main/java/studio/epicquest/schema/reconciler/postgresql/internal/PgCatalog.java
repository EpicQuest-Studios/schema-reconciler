package studio.epicquest.schema.reconciler.postgresql.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import studio.epicquest.schema.reconciler.model.Definition;
import studio.epicquest.schema.reconciler.model.ObjectId;
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
    public static final String RETURN_TYPE = "returnType";
    /** Table storage options ({@code fillfactor=70, ...}) for {@code CREATE TABLE ... WITH}. */
    public static final String TABLE_OPTIONS = "tableOptions";
    /** The {@code relreplident} letter of a table. */
    public static final String REPLIDENT = "replident";
    /** Whether a materialized view currently holds its result rows. */
    public static final String POPULATED = "populated";

    public static SchemaSnapshot read(Connection conn, String namespace) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("SET LOCAL search_path = " + PgIdentifiers.quote(namespace));
        }

        final var data = new Data(namespace);
        data.serverMajor = conn.getMetaData().getDatabaseMajorVersion();
        data.loadExtensionOwned(conn);
        data.loadRelations(conn);
        data.loadColumns(conn);
        data.loadConstraints(conn);
        data.loadIndexes(conn);
        data.loadViews(conn);
        data.loadSequences(conn);
        data.loadRoutines(conn);
        data.loadTriggers(conn);
        data.loadEnums(conn);
        data.loadOther(conn);
        data.loadDependencies(conn);
        data.loadExternalDependents(conn);
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
        private final Map<Long, ObjectId> collByOid = new HashMap<>();
        private final Map<Long, ObjectId> langByOid = new HashMap<>();
        private final Map<ObjectId, Set<ObjectId>> deps = new HashMap<>();
        /** Catalog oids owned by an extension (pg_depend deptype 'e'). Member
         *  objects cannot be created or dropped standalone, so they never
         *  enter the model; the extension itself is reported as OTHER. */
        private final Set<Long> extOwned = new HashSet<>();

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

        void loadRelations(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT c.oid, c.relname, c.relkind, c.relpersistence,
                           c.reloptions::text AS options, c.relreplident,
                           c.relrowsecurity, c.relforcerowsecurity,
                           EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhrelid = c.oid) AS inherited,
                           (SELECT ic.relname FROM pg_index ix JOIN pg_class ic ON ic.oid = ix.indexrelid
                             WHERE ix.indrelid = c.oid AND ix.indisreplident) AS replindex
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
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
                        final var inherited = rs.getBoolean("inherited");
                        // a plain table whose parents are also plain tables is a
                        // classic-inheritance child; a partition child has a
                        // partitioned ('p') parent. Neither is faithfully
                        // creatable as a standalone TABLE, so both are OTHER.
                        final var id = switch (kind) {
                            case "r" -> inherited
                                    ? new ObjectId(ObjectKind.OTHER, "inherited:" + name)
                                    : new ObjectId(ObjectKind.TABLE, name);
                            case "v" -> new ObjectId(ObjectKind.VIEW, name);
                            case "S" -> new ObjectId(ObjectKind.SEQUENCE, name);
                            // managed: canonical definition lands in loadViews
                            case "m" -> new ObjectId(ObjectKind.MATERIALIZED_VIEW, name);
                            // a documented category prefix per relkind (3.3):
                            // tolerating one non-managed relation kind must
                            // not exempt the others
                            case "f" -> new ObjectId(ObjectKind.OTHER, "foreign:" + name);
                            case "p" -> new ObjectId(ObjectKind.OTHER, "partition:" + name);
                            case "I" -> new ObjectId(ObjectKind.OTHER, "partindex:" + name);
                            case "c" -> new ObjectId(ObjectKind.OTHER, "composite:" + name);
                            case "t" -> new ObjectId(ObjectKind.OTHER, "toast:" + name);
                            default -> new ObjectId(ObjectKind.OTHER, "relation:" + name);
                        };
                        relByOid.put(oid, id);
                        if (id.kind() == ObjectKind.TABLE) {
                            final var replident = rs.getString("relreplident");
                            final var replindex = rs.getString("replindex");
                            define(id, "relkind=r;relpersistence=" + persistence
                                    + ";options=" + options
                                    + ";replident=" + replident
                                    + (replindex != null ? ";replindex=" + replindex : "")
                                    + ";rls=" + rs.getBoolean("relrowsecurity")
                                    + ";forcerls=" + rs.getBoolean("relforcerowsecurity"),
                                    Map.of(TABLE_OPTIONS, String.join(", ", options),
                                            REPLIDENT, replident));
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
                    JOIN pg_class c ON c.oid = a.attrelid AND c.relkind = 'r'
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

                        final var canonical = new StringBuilder("type=").append(type)
                                .append(";notnull=").append(notnull);
                        final var sql = new StringBuilder()
                                .append(PgIdentifiers.quote(name)).append(' ').append(type);
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
                            default -> ObjectKind.OTHER;
                        };
                        final var name = rs.getString("conname");
                        final var parent = rs.getString("parent");
                        final var def = rs.getString("def");
                        // exclusion constraints are OTHER: unsupported but detected
                        final var id = kind == ObjectKind.OTHER
                                ? new ObjectId(kind, "constraint:" + parent + "." + name)
                                : new ObjectId(kind, name, parent);
                        conByOid.put(rs.getLong("oid"), id);
                        final var conindid = rs.getLong("conindid");
                        // conindid is the backing index only for pk/unique; on
                        // an FK it is the referenced index on the other table.
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
                    SELECT c.oid, c.relname,
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
                        final var qSeq = qNs + "." + PgIdentifiers.quote(name);
                        final var attrs = new LinkedHashMap<String, String>();
                        attrs.put(OPTIONS_SQL, options);
                        attrs.put(CREATE_SQL, "CREATE SEQUENCE " + qSeq + " " + options);
                        attrs.put(DROP_SQL, "DROP SEQUENCE " + qSeq);
                        seqOptions.put(id, "type=" + stype + ";increment=" + inc + ";min=" + min
                                + ";max=" + max + ";start=" + start + ";cache=" + cache
                                + ";cycle=" + cycle);
                        // the canonical needs ownership, resolved by the
                        // second query — attrs accumulate here, define() runs
                        // (and normalizes them) once below
                        obj(id).attrs.putAll(attrs);
                    }
                }
            }
            // serial-style ownership (deptype 'a')
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT dep.objid AS seq_oid, rc.relname AS tbl, ra.attname AS col,
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
                            dep(seq, new ObjectId(ObjectKind.TABLE, tbl));
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
                    WHERE n.nspname = ? AND p.prokind IN ('f','p')
                    ORDER BY p.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
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
                                RETURN_TYPE, rettype));
                    }
                }
            }
        }

        void loadTriggers(Connection conn) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT t.oid, t.tgname, c.relname AS parent, t.tgenabled,
                           pg_get_triggerdef(t.oid, true) AS def, t.tgfoid
                    FROM pg_trigger t
                    JOIN pg_class c ON c.oid = t.tgrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND NOT t.tgisinternal
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
                UNION ALL SELECT 'pg_extension', e.oid, e.extname
                FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace AND n.nspname = ?
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

        /** Loads every catalog object a desired script can create in the
         *  namespace that has no managed {@link ObjectKind}, as OTHER, so the
         *  diff reports it UNSUPPORTED instead of ignoring it (5.4). */
        void loadOther(Connection conn) throws SQLException {
            // user-defined types that stay unsupported: domains, ranges,
            // multiranges and base types (array types carry typelem != 0 and
            // are not standalone; enums load as TYPE objects in loadEnums)
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT t.oid, t.typname, t.typtype
                    FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = ?
                      AND (t.typtype IN ('d','r','m')
                           OR (t.typtype = 'b' AND t.typelem = 0))
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
                        define(id, "typtype=" + rs.getString("typtype"), Map.of());
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT e.oid, e.extname
                    FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                    WHERE n.nspname = ?
                    ORDER BY e.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        define(new ObjectId(ObjectKind.OTHER, "extension:" + rs.getString("extname")),
                                "extension", Map.of());
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT po.oid, po.polname, c.relname
                    FROM pg_policy po
                    JOIN pg_class c ON c.oid = po.polrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
                    ORDER BY po.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        define(new ObjectId(ObjectKind.OTHER,
                                        "policy:" + rs.getString("relname") + "."
                                                + rs.getString("polname")),
                                "policy", Map.of());
                    }
                }
            }
            // aggregates and window functions (prokind 'a','w') — detected as
            // OTHER; functions and procedures ('f','p') are managed ROUTINEs
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT p.oid, p.proname || '(' || coalesce(
                            (SELECT string_agg(format_type(a, NULL), ', ' ORDER BY x.i)
                               FROM unnest(string_to_array(p.proargtypes::text, ' ')::oid[])
                                  WITH ORDINALITY AS x(a, i)), '') || ')' AS name,
                           p.prokind
                    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = ? AND p.prokind IN ('a','w')
                    ORDER BY p.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        final var id = new ObjectId(ObjectKind.OTHER,
                                "routine:" + rs.getString("name"));
                        procByOid.put(rs.getLong("oid"), id);
                        define(id, "prokind=" + rs.getString("prokind"), Map.of());
                    }
                }
            }
            // encoding conversions
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT cv.oid, cv.conname
                    FROM pg_conversion cv JOIN pg_namespace n ON n.oid = cv.connamespace
                    WHERE n.nspname = ?
                    ORDER BY cv.oid
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        define(new ObjectId(ObjectKind.OTHER,
                                        "conversion:" + rs.getString("conname")),
                                "conversion", Map.of());
                    }
                }
            }
            // non-view rewrite rules (_RETURN is part of the view itself)
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT r.oid, c.relname || '.' || r.rulename AS name
                    FROM pg_rewrite r
                    JOIN pg_class c ON c.oid = r.ev_class
                    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
                    WHERE r.rulename <> '_RETURN'
                    ORDER BY 2
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        define(new ObjectId(ObjectKind.OTHER, "rule:" + rs.getString("name")),
                                "rule", Map.of());
                    }
                }
            }
            // extended statistics objects
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT s.oid, c.relname || '.' || s.stxname AS name
                    FROM pg_statistic_ext s
                    JOIN pg_class c ON c.oid = s.stxrelid
                    JOIN pg_namespace n ON n.oid = s.stxnamespace AND n.nspname = ?
                    ORDER BY 2
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        define(new ObjectId(ObjectKind.OTHER,
                                        "statistics:" + rs.getString("name")),
                                "statistics", Map.of());
                    }
                }
            }
            // collations (referenced by column dependencies too)
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT cl.oid, cl.collname
                    FROM pg_collation cl JOIN pg_namespace n ON n.oid = cl.collnamespace
                    WHERE n.nspname = ?
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
            // full-text-search objects
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT 'fts-config:' || g.cfgname AS name, g.oid
                    FROM pg_ts_config g JOIN pg_namespace n ON n.oid = g.cfgnamespace AND n.nspname = ?
                    UNION ALL
                    SELECT 'fts-dict:' || g.dictname, g.oid
                    FROM pg_ts_dict g JOIN pg_namespace n ON n.oid = g.dictnamespace AND n.nspname = ?
                    UNION ALL
                    SELECT 'fts-parser:' || g.prsname, g.oid
                    FROM pg_ts_parser g JOIN pg_namespace n ON n.oid = g.prsnamespace AND n.nspname = ?
                    UNION ALL
                    SELECT 'fts-template:' || g.tmplname, g.oid
                    FROM pg_ts_template g JOIN pg_namespace n ON n.oid = g.tmplnamespace AND n.nspname = ?
                    ORDER BY 1
                    """)) {
                bindNamespace(ps, 4);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        define(new ObjectId(ObjectKind.OTHER, rs.getString("name")),
                                "text-search", Map.of());
                    }
                }
            }
            // operators, operator classes and families
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT 'operator:' || g.oprname || '('
                            || COALESCE(g.oprleft::regtype::text, '-') || ','
                            || COALESCE(g.oprright::regtype::text, '-') || ')' AS name, g.oid
                    FROM pg_operator g JOIN pg_namespace n ON n.oid = g.oprnamespace AND n.nspname = ?
                    UNION ALL
                    SELECT 'opclass:' || g.opcname, g.oid
                    FROM pg_opclass g JOIN pg_namespace n ON n.oid = g.opcnamespace AND n.nspname = ?
                    UNION ALL
                    SELECT 'opfamily:' || g.opfname, g.oid
                    FROM pg_opfamily g JOIN pg_namespace n ON n.oid = g.opfnamespace AND n.nspname = ?
                    ORDER BY 1
                    """)) {
                bindNamespace(ps, 3);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        define(new ObjectId(ObjectKind.OTHER, rs.getString("name")),
                                "operator", Map.of());
                    }
                }
            }
            // comments on namespace objects (pg_description)
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT 'comment:' || o.name
                            || CASE WHEN a.attname IS NULL THEN '' ELSE '.' || a.attname END AS name,
                            d.objoid
                    FROM pg_description d
                    JOIN (""" + NS_OBJECTS + """
                    ) o ON o.cls = d.classoid AND o.obj = d.objoid
                    LEFT JOIN pg_attribute a ON d.classoid = 'pg_class'::regclass
                            AND a.attrelid = d.objoid AND a.attnum = d.objsubid
                    ORDER BY 1
                    """)) {
                bindNamespace(ps, 18);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("objoid"))) {
                            continue;
                        }
                        define(new ObjectId(ObjectKind.OTHER, rs.getString("name")),
                                "comment", Map.of());
                    }
                }
            }
            // security labels on namespace objects (pg_seclabel)
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT 'seclabel:' || o.name
                            || CASE WHEN a.attname IS NULL THEN '' ELSE '.' || a.attname END AS name,
                            d.objoid
                    FROM pg_seclabel d
                    JOIN (""" + NS_OBJECTS + """
                    ) o ON o.cls = d.classoid AND o.obj = d.objoid
                    LEFT JOIN pg_attribute a ON d.classoid = 'pg_class'::regclass
                            AND a.attrelid = d.objoid AND a.attnum = d.objsubid
                    ORDER BY 1
                    """)) {
                bindNamespace(ps, 18);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("objoid"))) {
                            continue;
                        }
                        define(new ObjectId(ObjectKind.OTHER, rs.getString("name")),
                                "security-label", Map.of());
                    }
                }
            }
            // granted privileges: object acl columns are NULL until a GRANT. The
            // definition is the sorted ACL, with the owner written as <owner>, so a
            // target that already holds the same grants compares equal (5.4).
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT 'privileges:' || c.relname AS name, c.oid,
                           """ + aclTokens("c.relacl", "c.relowner") + """
                           AS acl
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relacl IS NOT NULL
                    UNION ALL
                    SELECT 'privileges:' || p.proname || '('
                            || pg_get_function_identity_arguments(p.oid) || ')', p.oid,
                           """ + aclTokens("p.proacl", "p.proowner") + """
                           AS acl
                    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = ? AND p.proacl IS NOT NULL
                    UNION ALL
                    SELECT 'privileges:' || t.typname, t.oid,
                           """ + aclTokens("t.typacl", "t.typowner") + """
                           AS acl
                    FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = ? AND t.typacl IS NOT NULL
                    ORDER BY 1
                    """)) {
                bindNamespace(ps, 3);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        define(new ObjectId(ObjectKind.OTHER, rs.getString("name")),
                                "privileges=" + rs.getString("acl"),
                                Map.of(Dialect.CONTENT_COMPARED_ATTRIBUTE, "true"));
                    }
                }
            }
            // ownership changed away from the executing role
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT 'ownership:' || c.relname AS name, c.oid
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relowner::regrole::text <> current_user
                    UNION ALL
                    SELECT 'ownership:' || p.proname || '('
                            || pg_get_function_identity_arguments(p.oid) || ')', p.oid
                    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = ? AND p.proowner::regrole::text <> current_user
                    UNION ALL
                    SELECT 'ownership:' || t.typname, t.oid
                    FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                    WHERE n.nspname = ? AND t.typowner::regrole::text <> current_user
                    ORDER BY 1
                    """)) {
                bindNamespace(ps, 3);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        define(new ObjectId(ObjectKind.OTHER, rs.getString("name")),
                                "ownership", Map.of());
                    }
                }
            }
            // publication bindings on namespace tables (the publication itself
            // is database-level; the FOR TABLE edge is the namespace-visible part)
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT 'publication:' || p.pubname || ':' || c.relname AS name, c.oid
                    FROM pg_publication_rel pr
                    JOIN pg_class c ON c.oid = pr.prrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
                    JOIN pg_publication p ON p.oid = pr.prpubid
                    ORDER BY 1
                    """)) {
                ps.setString(1, ns);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        define(new ObjectId(ObjectKind.OTHER, rs.getString("name")),
                                "publication", Map.of());
                    }
                }
            }
            // casts have no namespace; they are visible through a namespace
            // source/target type or cast function (a cast between two foreign
            // types has no namespace anchor and stays outside the model)
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT c.oid, 'cast:' || format_type(c.castsource, NULL)
                            || '->' || format_type(c.casttarget, NULL) AS name
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
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        define(new ObjectId(ObjectKind.OTHER, rs.getString("name")),
                                "cast", Map.of());
                    }
                }
            }
            // transforms have no namespace; anchor on the type or the
            // from/to-SQL functions
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT t.oid, 'transform:' || format_type(t.trftype, NULL)
                            || '/' || l.lanname AS name
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
                        if (extMember(rs.getLong("oid"))) {
                            continue;
                        }
                        define(new ObjectId(ObjectKind.OTHER, rs.getString("name")),
                                "transform", Map.of());
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
                        define(id, "language", Map.of());
                    }
                }
            }
            // sequence publication bindings — the pg_publication_sequences
            // view exists from PostgreSQL 19 (pg_publication_rel covers tables)
            if (serverMajor >= 19) {
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT 'publication:' || v.pubname || ':' || v.sequencename AS name
                        FROM pg_publication_sequences v
                        WHERE v.schemaname = ?
                        ORDER BY 1
                        """)) {
                    ps.setString(1, ns);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            define(new ObjectId(ObjectKind.OTHER, rs.getString("name")),
                                    "publication", Map.of());
                        }
                    }
                }
            }
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
                        AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass)
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
                                           'pg_type'::regclass, 'pg_language'::regclass)
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
                                           'pg_type'::regclass)
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
                                           'pg_class'::regclass, 'pg_constraint'::regclass)
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
                      AND d.refclassid IN ('pg_type'::regclass, 'pg_collation'::regclass)
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
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (var i = 1; i <= 12; i++) {
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
         * {@code grantor>grantee:PRIVILEGE[*]} token per entry (PUBLIC for
         * grantee 0, {@code <owner>} for the object's owner, {@code *} for
         * grantable), sorted, so grant order cannot cause drift.
         */
        private static String aclTokens(String acl, String owner) {
            return "(SELECT string_agg(tok, ',' ORDER BY tok) FROM (SELECT"
                    + " CASE WHEN a.grantor = " + owner + " THEN '<owner>'"
                    + " ELSE a.grantor::regrole::text END || '>' ||"
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

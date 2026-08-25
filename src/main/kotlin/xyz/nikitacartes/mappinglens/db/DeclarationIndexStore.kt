package xyz.nikitacartes.mappinglens.db

import xyz.nikitacartes.mappinglens.config.AppConfig
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipFile

/** One class as the declaration index holds it, in the names of the namespace it was scanned in. */
data class ClassDecl(
    /** The ASM access flags of the class header. */
    val access: Int,
    /** Direct supertypes, superclass first, exactly as the class file names them. */
    val supertypes: List<String>,
    /** The classes of the same jar that name this one as a direct supertype. */
    val subtypes: List<String>,
    /** Every declared method and field, as `name:descriptor`. */
    val members: List<String>,
)

/**
 * The prebuilt declaration index: one row for each class of each (version, namespace), in a file of
 * its own beside the main index.
 *
 * Without it the server scans a version's whole named jar the first time it is asked anything about
 * that version: 227 to 318ms measured over 1.21.4 and 1.21.8, and the resulting graph held in memory
 * for as long as it is cached. Reading a class out of this file instead costs 10us and holds nothing.
 *
 * The main index cannot answer these questions in its place. `exists` is keyed on
 * `owner:name:descriptor` in the target namespace, and the member tables carry no yarn or mojmap
 * descriptor, only the obfuscated and the intermediary one. The jar is also the only place the
 * subtype edges and the access flags exist.
 *
 * One row for each class, because the class is the unit both readers ask for: ExistsService tests a
 * key against its owner and then walks up the supertypes, and HierarchyService walks the graph an
 * edge at a time. A whole (version, namespace) costs 4.2 MB on disk.
 *
 * The file is optional and holds whichever versions were built into it. Anything it does not cover
 * falls back to the scan, so a partial file is a valid file.
 */
object DeclarationIndexStore {
    private val log = LoggerFactory.getLogger(DeclarationIndexStore::class.java)

    /** The declaration index beside [databasePath]: `mappinglens.db` gives `mappinglens-decl.db`. */
    fun path(databasePath: String): Path {
        val main = Paths.get(databasePath).toAbsolutePath()
        return main.resolveSibling(main.fileName.toString().removeSuffix(".db") + "-decl.db")
    }

    fun exists(databasePath: String): Boolean = Files.exists(path(databasePath))

    /** Opens the file for reading, or returns null when there is none. The caller closes it. */
    fun openReadOnly(databasePath: String): Connection? {
        val file = path(databasePath)
        if (!Files.exists(file)) return null
        return DriverManager.getConnection(jdbcUrl(file)).apply {
            createStatement().use { st ->
                st.execute("PRAGMA query_only=ON;")
                st.execute("PRAGMA mmap_size=2147483648;")   // 2 GiB
                st.execute("PRAGMA cache_size=-16384;")       // 16 MiB
                st.execute("PRAGMA busy_timeout=3000;")
            }
        }
    }

    /** Opens the file for the indexer, creating it and its tables when they are not there yet. */
    fun openWritable(databasePath: String): Connection {
        val file = path(databasePath)
        file.parent?.let { Files.createDirectories(it) }
        log.info("Opening declaration index at {}", file)
        return DriverManager.getConnection(jdbcUrl(file)).apply {
            createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL;")
                st.execute("PRAGMA synchronous=NORMAL;")
                st.execute("PRAGMA page_size=8192;")
                st.execute("PRAGMA temp_store=MEMORY;")
                st.execute(
                    "CREATE TABLE IF NOT EXISTS decls(" +
                        "version TEXT, ns TEXT, owner TEXT, blob BLOB, " +
                        "PRIMARY KEY(version, ns, owner)) WITHOUT ROWID;"
                )
                // Which (version, namespace) pairs are complete. Asking `decls` itself would mean a
                // scan, and an absent class is indistinguishable from an unbuilt version there.
                st.execute(
                    "CREATE TABLE IF NOT EXISTS decls_built(" +
                        "version TEXT, ns TEXT, PRIMARY KEY(version, ns)) WITHOUT ROWID;"
                )
            }
            autoCommit = false
        }
    }

    /** The (version, namespace) pairs the file holds. Read once per connection; it is a small table. */
    fun built(conn: Connection): Set<Pair<String, String>> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT version, ns FROM decls_built").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }
        }

    /** Replaces everything stored for one (version, namespace). The caller commits. */
    fun write(conn: Connection, version: String, namespace: String, classes: Map<String, ClassDecl>) {
        conn.prepareStatement("DELETE FROM decls WHERE version = ? AND ns = ?").use { ps ->
            ps.setString(1, version); ps.setString(2, namespace); ps.executeUpdate()
        }
        conn.prepareStatement("INSERT INTO decls(version, ns, owner, blob) VALUES (?, ?, ?, ?)").use { ps ->
            for ((owner, decl) in classes) {
                ps.setString(1, version)
                ps.setString(2, namespace)
                ps.setString(3, owner)
                ps.setBytes(4, deflate(encode(decl)))
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement("INSERT OR REPLACE INTO decls_built(version, ns) VALUES (?, ?)").use { ps ->
            ps.setString(1, version); ps.setString(2, namespace); ps.executeUpdate()
        }
    }

    /** One class, or null when the file holds no such class. [ps] comes from [readStatement]. */
    fun read(ps: PreparedStatement, version: String, namespace: String, owner: String): ClassDecl? {
        ps.setString(1, version); ps.setString(2, namespace); ps.setString(3, owner)
        return ps.executeQuery().use { rs -> if (rs.next()) decode(inflate(rs.getBytes(1))) else null }
    }

    /**
     * The statement [read] takes. One walk asks for hundreds of classes, and preparing a statement
     * for each of them costs more than the reads do, so a reader prepares one and keeps it.
     */
    fun readStatement(conn: Connection): PreparedStatement =
        conn.prepareStatement("SELECT blob FROM decls WHERE version = ? AND ns = ? AND owner = ?")

    /**
     * Every class of [jar], with its header, its members and its direct subtypes.
     *
     * Only the class header and the member declarations are parsed, so the scan is a fraction of a
     * full read: `SKIP_CODE` leaves the method bodies, which are the bulk of a class file, unread.
     */
    fun scan(jar: File): Map<String, ClassDecl> {
        val access = LinkedHashMap<String, Int>()
        val supertypes = HashMap<String, List<String>>()
        val members = HashMap<String, MutableList<String>>()
        ZipFile(jar).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                ClassReader(bytes).accept(
                    collector(access, supertypes, members),
                    ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
                )
            }
        }
        val subtypes = HashMap<String, MutableList<String>>()
        for ((name, parents) in supertypes) {
            for (parent in parents) {
                if (parent in access) subtypes.getOrPut(parent) { ArrayList() }.add(name)
            }
        }
        return access.mapValues { (name, flags) ->
            ClassDecl(
                access = flags,
                supertypes = supertypes[name].orEmpty(),
                subtypes = subtypes[name].orEmpty(),
                members = members[name].orEmpty(),
            )
        }
    }

    private fun collector(
        access: MutableMap<String, Int>,
        supertypes: MutableMap<String, List<String>>,
        members: MutableMap<String, MutableList<String>>,
    ): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9) {
            private lateinit var owner: String

            override fun visit(
                version: Int,
                flags: Int,
                name: String,
                sig: String?,
                superName: String?,
                interfaces: Array<String>?,
            ) {
                owner = name
                access[name] = flags
                val parents = listOfNotNull(superName) + interfaces.orEmpty()
                if (parents.isNotEmpty()) supertypes[name] = parents
            }

            override fun visitMethod(f: Int, name: String, desc: String, sig: String?, ex: Array<String>?): MethodVisitor? {
                members.getOrPut(owner) { ArrayList() }.add("$name:$desc")
                return null
            }

            override fun visitField(f: Int, name: String, desc: String, sig: String?, value: Any?): FieldVisitor? {
                members.getOrPut(owner) { ArrayList() }.add("$name:$desc")
                return null
            }
        }

    /**
     * One class as text: the access flags on the first line, then a line for each supertype prefixed
     * `>`, each subtype prefixed `+` and each member as `name:descriptor`. No Java name starts with
     * `>` or `+`, so the prefixes need no escaping, and `<init>` and `<clinit>` stay unambiguous
     * where a `<` prefix would not. Text of this shape deflates better than any packing worth
     * writing by hand.
     */
    private fun encode(decl: ClassDecl): ByteArray {
        val out = StringBuilder()
        out.append(decl.access).append('\n')
        for (parent in decl.supertypes) out.append('>').append(parent).append('\n')
        for (child in decl.subtypes) out.append('+').append(child).append('\n')
        for (member in decl.members) out.append(member).append('\n')
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decode(bytes: ByteArray): ClassDecl {
        val lines = String(bytes, Charsets.UTF_8).removeSuffix("\n").split('\n')
        val supertypes = ArrayList<String>()
        val subtypes = ArrayList<String>()
        val members = ArrayList<String>()
        for (line in lines.drop(1)) {
            when (line.firstOrNull()) {
                '>' -> supertypes.add(line.substring(1))
                '+' -> subtypes.add(line.substring(1))
                else -> members.add(line)
            }
        }
        return ClassDecl(lines[0].toInt(), supertypes, subtypes, members)
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4)
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            DeflaterOutputStream(out, deflater).use { it.write(bytes) }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): ByteArray =
        InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun jdbcUrl(file: Path) = "jdbc:sqlite:${file.toString().replace('\\', '/')}"
}

/**
 * One request's view of a version's declarations: a class at a time out of the prebuilt file, or out
 * of a whole-jar scan when the file does not cover the version.
 *
 * The connection lives for the request rather than for the read, because a walk asks for hundreds of
 * classes. It closes with the request, which keeps the server as stateless as it was. Classes read
 * once are kept for the life of the lookup: `exists` answers up to 2000 keys in one call and they
 * share owners, and a walk revisits the classes it has already passed.
 */
class DeclarationLookup(
    private val config: AppConfig,
    private val version: String,
    private val namespace: String,
    /** Whole-jar scans held between requests, for versions the prebuilt file does not cover. */
    private val fallback: MutableMap<Pair<String, String>, Map<String, ClassDecl>>,
) : AutoCloseable {

    private val conn = DeclarationIndexStore.openReadOnly(config.databasePath)
    private val statement = conn?.let { DeclarationIndexStore.readStatement(it) }
    private val prebuilt = conn?.let { DeclarationIndexStore.built(it) }.orEmpty()
    private val fromFile = (version to namespace) in prebuilt
    private val seen = HashMap<String, ClassDecl?>()

    /** Whether this version can be answered at all, prebuilt or by scanning its jar. */
    val available: Boolean
        get() = fromFile || config.sources.remappedJar(version, namespace) != null

    operator fun get(owner: String): ClassDecl? = seen.getOrPut(owner) {
        if (fromFile) DeclarationIndexStore.read(statement!!, version, namespace, owner) else scanned()[owner]
    }

    /** Every supertype of [owner], nearest first. Breadth-first, so a direct parent beats a distant one. */
    fun ancestorsOf(owner: String): List<String> {
        val seenNames = LinkedHashSet<String>()
        val queue = ArrayDeque(this[owner]?.supertypes.orEmpty())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seenNames.add(next)) continue
            queue.addAll(this[next]?.supertypes.orEmpty())
        }
        return seenNames.toList()
    }

    private fun scanned(): Map<String, ClassDecl> {
        val key = version to namespace
        fallback[key]?.let { return it }
        val jar = config.sources.remappedJar(version, namespace)?.toFile() ?: return emptyMap()
        val built = DeclarationIndexStore.scan(jar)
        fallback[key] = built
        return built
    }

    override fun close() {
        statement?.close()
        conn?.close()
    }
}

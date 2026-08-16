package xyz.nikitacartes.mappinglens.ingestion

import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.tree.MemoryMappingTree
import java.nio.file.Path

/**
 * Parsed normalized mapping representation. All names are kept as-is from the file.
 * "namespaces" is the ordered list: [src, dst1, dst2, ...].
 */
data class ParsedMappings(
    val namespaces: List<String>,
    val classes: List<ParsedClass>,
)

data class ParsedClass(
    val names: List<String?>, // size == namespaces.size
    val methods: List<ParsedMethod>,
    val fields: List<ParsedField>,
)

data class ParsedMethod(
    override val names: List<String?>,
    override val descs: List<String?>,
) : HasNamesDescs

data class ParsedField(
    override val names: List<String?>,
    override val descs: List<String?>,
) : HasNamesDescs

object TinyV2Parser {
    fun parse(file: Path): ParsedMappings {
        val tree = MemoryMappingTree()
        MappingReader.read(file, tree)
        val nsCount = 1 + tree.dstNamespaces.size
        val namespaces = buildList {
            add(tree.srcNamespace ?: "official")
            addAll(tree.dstNamespaces)
        }
        val classes = tree.classes.map { cls ->
            val classNames = (0 until nsCount).map { idx ->
                if (idx == 0) cls.srcName else cls.getDstName(idx - 1)
            }
            val methods = cls.methods.map { m ->
                val names = (0 until nsCount).map { idx ->
                    if (idx == 0) m.srcName else m.getDstName(idx - 1)
                }
                val descs = (0 until nsCount).map { idx ->
                    if (idx == 0) m.srcDesc else m.getDstDesc(idx - 1)
                }
                ParsedMethod(names, descs)
            }
            val fields = cls.fields.map { f ->
                val names = (0 until nsCount).map { idx ->
                    if (idx == 0) f.srcName else f.getDstName(idx - 1)
                }
                val descs = (0 until nsCount).map { idx ->
                    if (idx == 0) f.srcDesc else f.getDstDesc(idx - 1)
                }
                ParsedField(names, descs)
            }
            ParsedClass(classNames, methods, fields)
        }
        return ParsedMappings(namespaces, classes)
    }
}

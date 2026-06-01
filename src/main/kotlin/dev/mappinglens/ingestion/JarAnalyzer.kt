package dev.mappinglens.ingestion

import org.objectweb.asm.ClassReader
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceClassVisitor
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * ASM-backed helper for reading classes from Minecraft jars and producing javap-like text.
 */
object JarAnalyzer {
    fun disassembleText(jar: Path, internalClassName: String, remapper: Remapper? = null): String? {
        ZipFile(jar.toFile()).use { zip ->
            val entry = zip.getEntry("$internalClassName.class") ?: return null
            zip.getInputStream(entry).use { input ->
                val reader = ClassReader(input.readBytes())
                val writer = StringWriter()
                val trace = TraceClassVisitor(null, Textifier(), PrintWriter(writer))
                val visitor = if (remapper == null) trace else ClassRemapper(trace, remapper)
                reader.accept(visitor, ClassReader.SKIP_FRAMES)
                return writer.toString()
            }
        }
    }
}

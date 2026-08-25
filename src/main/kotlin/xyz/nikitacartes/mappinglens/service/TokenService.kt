package xyz.nikitacartes.mappinglens.service

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.javaparser.resolution.types.ResolvedPrimitiveType
import com.github.javaparser.resolution.types.ResolvedType
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.lruCache
import xyz.nikitacartes.mappinglens.model.SourceToken
import xyz.nikitacartes.mappinglens.model.TokensResponse
import java.util.Optional

/**
 * Resolves every class/method/field identifier in a decompiled `.java` to its owner/name/descriptor
 * by parsing the source with JavaParser's symbol solver (classpath = the version's named jar). Returns
 * `{source, tokens}` so the frontend can map a cursor position to a symbol exactly like mcsrc.
 *
 * Both the (expensive) per-(version,namespace) symbol solver and the per-class token result are cached;
 * the index is immutable for the server's lifetime. Any identifier the solver can't resolve is skipped,
 * so a partial failure degrades gracefully rather than dropping the whole file.
 */
class TokenService(private val config: AppConfig, private val bytecodeService: BytecodeService) {

    // Resolving one class costs 3.6 to 4.1s measured over 1.21.4, almost all of it JavaParser symbol
    // resolution, so a resolved class is worth holding. An entry is the whole class source plus a
    // token for each identifier: 40 KB for an average class, 970 KB for one of the largest.
    // `cache.tokens` bounds this map. `cache.symbol-solvers` bounds the count of the other one and
    // not its heap, see CacheConfig.symbolSolvers.
    private val solverCache = lruCache<Pair<String, String>, Optional<JavaSymbolSolver>>(config.cache.symbolSolvers)
    private val tokenCache = lruCache<Triple<String, String, String>, TokensResponse>(config.cache.tokens)

    fun tokens(versionId: String, className: String, namespace: String): TokensResponse? {
        if (namespace != "yarn" && namespace != "mojmap") return null
        tokenCache[Triple(versionId, className, namespace)]?.let { return it }
        val src = bytecodeService.source(versionId, className, namespace) ?: return null
        val solver = solverFor(versionId, namespace) ?: return null
        val resp = TokensResponse(versionId, className, namespace, src.source, tokensFor(src.source, solver))
        tokenCache[Triple(versionId, className, namespace)] = resp
        return resp
    }

    private fun solverFor(versionId: String, namespace: String): JavaSymbolSolver? {
        val key = versionId to namespace
        solverCache[key]?.let { return it.orElse(null) }
        val built = buildSolver(versionId, namespace)
        solverCache[key] = built
        return built.orElse(null)
    }

    private fun buildSolver(versionId: String, namespace: String): Optional<JavaSymbolSolver> {
        val jar = config.sources.remappedJar(versionId, namespace) ?: return Optional.empty()
        val combined = CombinedTypeSolver()
        combined.add(ReflectionTypeSolver()) // JDK types
        combined.add(JarTypeSolver(jar))      // Minecraft named classes
        // Minecraft's own dependencies (Guava, Brigadier, DataFixerUpper, fastutil, …) so that
        // library-typed identifiers resolve instead of being silently dropped by emit()'s catch.
        // Keeps ~one JarFile handle open per lib per (version, ns). Construction costs 8 to 23ms
        // with no library and 570 to 1096ms with all of them, so rebuilding one is cheap. Freeing
        // one is not possible: a solver stays reachable from a JavaParser static after eviction, so
        // `cache.symbol-solvers` bounds how many are built, not how much they hold. The measurements
        // and the one lever JavaParser offers are in CacheConfig.symbolSolvers.
        for (lib in config.sources.libraryJars(versionId)) {
            try {
                combined.add(JarTypeSolver(lib))
            } catch (_: Exception) {
                // Unreadable/native jar: skip; a missing lib only costs a few unresolved tokens.
            }
        }
        return Optional.of(JavaSymbolSolver(combined))
    }

    companion object {
        /** Resolve all tokens in [source] using [symbolSolver]. Pure — testable with any TypeSolver. */
        fun tokensFor(source: String, symbolSolver: JavaSymbolSolver): List<SourceToken> {
            val config = ParserConfiguration()
                .setSymbolResolver(symbolSolver)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
            val cu = JavaParser(config).parse(source).result.orElse(null) ?: return emptyList()

            val tokens = ArrayList<SourceToken>()
            cu.walk { node ->
                try {
                    emit(node, tokens)
                } catch (_: Throwable) {
                    // Unresolved symbol (missing classpath entry, decompiler quirk): skip this identifier.
                }
            }
            return tokens
        }

        private fun emit(node: Node, out: MutableList<SourceToken>) {
            when (node) {
                is MethodCallExpr -> methodToken(node.name, node.resolve(), declaration = false, out)
                is MethodDeclaration -> methodToken(node.name, node.resolve(), declaration = true, out)
                is ClassOrInterfaceType -> {
                    val rt = node.resolve()
                    if (rt.isReferenceType) {
                        val decl = rt.asReferenceType().typeDeclaration.orElse(null) ?: return
                        classToken(node.name, internalOf(decl), declaration = false, out)
                    }
                }
                is ClassOrInterfaceDeclaration ->
                    (node.resolve() as? ResolvedReferenceTypeDeclaration)?.let {
                        classToken(node.name, internalOf(it), declaration = true, out)
                    }
                is FieldAccessExpr -> fieldToken(node.name, node.resolve(), declaration = false, out)
                is NameExpr -> fieldToken(node.name, node.resolve(), declaration = false, out)
                is VariableDeclarator -> {
                    if (node.parentNode.orElse(null) is FieldDeclaration) {
                        fieldToken(node.name, node.resolve(), declaration = true, out)
                    }
                }
            }
        }

        private fun methodToken(name: SimpleName, m: ResolvedMethodDeclaration, declaration: Boolean, out: MutableList<SourceToken>) {
            val range = rangeOf(name) ?: return
            out += SourceToken(
                range[0], range[1], range[2], range[3],
                type = "method",
                className = internalOf(m.declaringType()),
                name = m.name,
                descriptor = methodDescriptor(m),
                declaration = declaration,
            )
        }

        private fun fieldToken(name: SimpleName, resolved: Any, declaration: Boolean, out: MutableList<SourceToken>) {
            val value = resolved as? com.github.javaparser.resolution.declarations.ResolvedValueDeclaration ?: return
            if (!value.isField) return
            val field = value.asField()
            val range = rangeOf(name) ?: return
            out += SourceToken(
                range[0], range[1], range[2], range[3],
                type = "field",
                className = internalOf(field.declaringType()),
                name = field.name,
                descriptor = descriptorOf(field.type),
                declaration = declaration,
            )
        }

        private fun classToken(name: SimpleName, internal: String, declaration: Boolean, out: MutableList<SourceToken>) {
            val range = rangeOf(name) ?: return
            out += SourceToken(range[0], range[1], range[2], range[3], type = "class", className = internal, declaration = declaration)
        }

        /** Monaco-style range [startLine, startCol, endLine, endColExclusive] for a name node. */
        private fun rangeOf(name: SimpleName): IntArray? {
            val r = name.range.orElse(null) ?: return null
            return intArrayOf(r.begin.line, r.begin.column, r.end.line, r.end.column + 1)
        }

        /** Qualified name (dots) + package name -> JVM internal name (slashes for packages, `$` for nesting). */
        private fun internalOf(decl: ResolvedTypeDeclaration): String {
            val qn = decl.qualifiedName
            val pkg = runCatching { decl.packageName }.getOrDefault("")
            if (pkg.isEmpty()) return qn.replace('.', '$')
            val rest = qn.removePrefix("$pkg.")
            return pkg.replace('.', '/') + "/" + rest.replace('.', '$')
        }

        private fun methodDescriptor(m: ResolvedMethodDeclaration): String {
            val params = (0 until m.numberOfParams).joinToString("") { descriptorOf(m.getParam(it).type) }
            return "($params)${descriptorOf(m.returnType)}"
        }

        private fun descriptorOf(t: ResolvedType): String = when {
            t.isVoid -> "V"
            t.isPrimitive -> when (t.asPrimitive()) {
                ResolvedPrimitiveType.BOOLEAN -> "Z"
                ResolvedPrimitiveType.BYTE -> "B"
                ResolvedPrimitiveType.SHORT -> "S"
                ResolvedPrimitiveType.CHAR -> "C"
                ResolvedPrimitiveType.INT -> "I"
                ResolvedPrimitiveType.LONG -> "J"
                ResolvedPrimitiveType.FLOAT -> "F"
                ResolvedPrimitiveType.DOUBLE -> "D"
            }
            t.isArray -> "[" + descriptorOf(t.asArrayType().componentType)
            t.isReferenceType -> {
                val rt = t.asReferenceType()
                val decl = rt.typeDeclaration.orElse(null)
                "L" + (if (decl != null) internalOf(decl) else rt.qualifiedName.replace('.', '/')) + ";"
            }
            // Generic type variable: erase to its first bound (JVM erasure), or Object when unbounded.
            t.isTypeVariable -> {
                val bound = t.asTypeVariable().asTypeParameter().bounds.firstOrNull()?.type
                if (bound != null && !bound.isTypeVariable) descriptorOf(bound) else "Ljava/lang/Object;"
            }
            else -> "Ljava/lang/Object;"
        }
    }
}

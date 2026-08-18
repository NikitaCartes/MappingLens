package xyz.nikitacartes.mappinglens.service

/**
 * JVM descriptor rewriting. A descriptor is primitives, array markers and `L<internal name>;`
 * types, so translating one between namespaces is translating the class names inside it. The index
 * stores only official and intermediary descriptors, and this is what gives the other namespaces
 * theirs.
 */
object Descriptors {

    /**
     * Rewrites every `L<class>;` type of [descriptor] through [rename]. A type [rename] does not
     * know is left as it is, so a descriptor that mentions a JDK class still comes back usable.
     *
     * A class name cannot contain `;`, so reading from `L` to the next `;` is unambiguous — an `L`
     * inside a name (`Level`) is already consumed as part of that name.
     */
    fun mapTypes(descriptor: String, rename: (String) -> String?): String {
        if ('L' !in descriptor) return descriptor
        val out = StringBuilder(descriptor.length)
        var i = 0
        while (i < descriptor.length) {
            val c = descriptor[i]
            if (c != 'L') {
                out.append(c)
                i++
                continue
            }
            val end = descriptor.indexOf(';', i)
            if (end < 0) { // Not a descriptor after all; hand back what came in.
                out.append(descriptor, i, descriptor.length)
                break
            }
            val type = descriptor.substring(i + 1, end)
            out.append('L').append(rename(type) ?: type).append(';')
            i = end + 1
        }
        return out.toString()
    }
}

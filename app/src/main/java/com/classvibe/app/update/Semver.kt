package com.classvibe.app.update

/** Minimal semantic-version parser/comparator: "v1.2.0", "1.3.0-beta.2". */
data class Semver(val major: Int, val minor: Int, val patch: Int, val pre: String) : Comparable<Semver> {

    val isPrerelease: Boolean get() = pre.isNotEmpty()

    override fun compareTo(other: Semver): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // A final release is newer than any pre-release of the same version.
        if (pre.isEmpty() && other.pre.isNotEmpty()) return 1
        if (pre.isNotEmpty() && other.pre.isEmpty()) return -1
        val a = Regex("""\d+""").find(pre)?.value?.toIntOrNull() ?: 0
        val b = Regex("""\d+""").find(other.pre)?.value?.toIntOrNull() ?: 0
        return compareValues(a, b).takeIf { it != 0 } ?: pre.compareTo(other.pre)
    }

    override fun toString() = "$major.$minor.$patch" + if (pre.isNotEmpty()) "-$pre" else ""

    companion object {
        fun parse(text: String): Semver? {
            val m = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.\-]+))?""").find(text.trim()) ?: return null
            return Semver(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].ifEmpty { "0" }.toInt(),
                m.groupValues[4]
            )
        }
    }
}

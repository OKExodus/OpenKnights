package io.github.okexodus.openknights.patcher

/**
 * An OpenKnights version: four numbers, `major.milestone.fix.build`, for example `0.1.0.0`.
 *
 * Android identifies updates by one ever-growing integer, the `versionCode`. It is
 * `major * 1,000,000 + milestone * 10,000 + fix * 100 + build`, so milestone, fix and build each stay below 100 and
 * a higher version always has a higher code.
 */
data class AppVersion(val major: Int, val milestone: Int, val fix: Int, val build: Int) : Comparable<AppVersion> {
    init {
        require(major in 0..MAX_MAJOR) { "major must be 0..$MAX_MAJOR, was $major" }
        require(milestone in 0..MAX_PART) { "milestone must be 0..$MAX_PART, was $milestone" }
        require(fix in 0..MAX_PART) { "fix must be 0..$MAX_PART, was $fix" }
        require(build in 0..MAX_PART) { "build must be 0..$MAX_PART, was $build" }
    }

    val versionCode: Int
        get() = major * 1_000_000 + milestone * 10_000 + fix * 100 + build

    override fun compareTo(other: AppVersion): Int = versionCode.compareTo(other.versionCode)

    override fun toString(): String = "$major.$milestone.$fix.$build"

    companion object {
        /** Keeps every versionCode at or below Android's limit of 2,100,000,000. */
        const val MAX_MAJOR = 2099
        const val MAX_PART = 99

        /** Parses `major.milestone.fix.build`; each part is a plain number without leading zeros. */
        fun parse(text: String): AppVersion {
            val parts = text.split('.')
            require(parts.size == 4) { "expected four numbers like 0.1.0.0, got \"$text\"" }
            val numbers = parts.map { part ->
                require(part.length in 1..4 && part.all { it in '0'..'9' } && (part == "0" || part[0] != '0')) {
                    "\"$part\" in \"$text\" is not a plain number"
                }
                part.toInt()
            }
            return AppVersion(numbers[0], numbers[1], numbers[2], numbers[3])
        }
    }
}

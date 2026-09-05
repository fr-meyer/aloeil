package org.aloeil.app

/**
 * JVM-only verification with an explicitly synthetic fixture.
 *
 * The fixture contains build labels only: no health data, units, or sample readings.
 */
object SyntheticFixtureJvmTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val fixture = SyntheticBuildFixture(
            builder = "github-actions",
            records = emptyList(),
        )

        check(fixture.builder == "github-actions")
        check(fixture.records.isEmpty())
    }
}

private data class SyntheticBuildFixture(
    val builder: String,
    val records: List<String>,
)

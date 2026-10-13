package com.lexken.lexkenai.core.network

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HEAD
import retrofit2.http.HTTP
import retrofit2.http.OPTIONS
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import java.io.File
import java.util.jar.JarFile

/**
 * The drift guard (replaces the older repo's `tools/web-drift.sh`): every Retrofit path the
 * app declares — found by scanning the compiled interfaces — must match a line of the backend
 * route table that `tools/route-catalog.py` flattens from the two Django URLconfs (read-only
 * checkouts, `prod_staging`). A path that stops matching means the backend moved, a trailing
 * slash was dropped, or a route was invented; regenerate the catalog before touching the test.
 */
class RouteCatalogTest {
    data class Endpoint(val api: String, val method: String, val path: String)

    @TestFactory
    fun `every Retrofit path exists in the backend route table`(): List<DynamicTest> {
        val endpoints = scanEndpoints()
        assertThat(endpoints.size).isGreaterThan(0)
        return endpoints.map { endpoint ->
            val catalog = if (endpoint.api in IDENTITY_APIS) identity else lawris
            dynamicTest("${endpoint.api}.${endpoint.method} ${endpoint.path}") {
                assertThat(
                    matches(catalog, endpoint.path),
                    "${endpoint.api}: ${endpoint.method} ${endpoint.path} is not in routes/${if (endpoint.api in IDENTITY_APIS) "identity" else "lawris"}.txt",
                ).isTrue()
            }
        }
    }

    @TestFactory
    fun `every socket URL the app derives is a Channels route`(): List<DynamicTest> {
        val base = "https://stage.lexken.ai/api/"
        val urls = mapOf(
            "chat" to SocketUrls.chat(base),
            "dmsNotifications" to SocketUrls.dmsNotifications(base, "5f2b1c9e-4a3d-4e8f-9b7a-1c2d3e4f5a6b"),
            "contractAnalysis" to SocketUrls.contractAnalysis(base, "5f2b1c9e-4a3d-4e8f-9b7a-1c2d3e4f5a6b"),
            "gitContract" to SocketUrls.gitContract(base, "5f2b1c9e-4a3d-4e8f-9b7a-1c2d3e4f5a6b"),
            "firmKnowledge" to SocketUrls.firmKnowledge(base),
        )
        return urls.map { (name, url) ->
            dynamicTest("$name → $url") {
                val path = url.removePrefix("wss://stage.lexken.ai/")
                assertThat(socketRoutes.any { it.matches(path) }, "$path is not a ws: line of routes/lawris.txt").isTrue()
            }
        }
    }

    @Test
    fun `the doubled api prefix family is exactly the eight the plan names`() {
        val families = lawrisLines.filter { it.startsWith("api/") }.map { it.removePrefix("api/").substringBefore('/') }.toSet()
        assertThat(families).isEqualTo(
            setOf("crypt", "database", "drafting", "firm-knowledge", "pii-redaction", "playbooks", "sharing", "telemetry"),
        )
    }

    companion object {
        /** Interfaces that talk to the identity host (`VF_API_BASE_URL`); everything else is Lawris. */
        private val IDENTITY_APIS = setOf("VfOAuthApi", "VfAccountApi")

        private val lawrisLines: List<String> by lazy { catalogLines("lawris") }
        private val lawris: List<Regex> by lazy { lawrisLines.filterNot { it.startsWith("ws:") }.map(::toRegex) }
        private val identity: List<Regex> by lazy { catalogLines("identity").filterNot { it.startsWith("ws:") }.map(::toRegex) }
        private val socketRoutes: List<Regex> by lazy { lawrisLines.filter { it.startsWith("ws:") }.map(::toRegex) }

        /** Values a `{param}` is tried with: a UUID, an int, a slug and a two-segment path — one per Django converter. */
        private val PARAM_SAMPLES = listOf("5f2b1c9e-4a3d-4e8f-9b7a-1c2d3e4f5a6b", "42", "annual-leave-policy", "shared/lease.docx")

        private fun catalogLines(name: String): List<String> {
            val stream = RouteCatalogTest::class.java.classLoader.getResourceAsStream("routes/$name.txt")
                ?: error("routes/$name.txt is missing — run tools/route-catalog.py")
            return stream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }

        /** Python named groups (`(?P<folder_path>…)`) become plain groups: Java forbids `_` in group names. */
        private val NAMED_GROUP = Regex("\\(\\?P<[^>]+>")

        fun toRegex(line: String): Regex = when {
            line.startsWith("re:") || line.startsWith("ws:^") -> Regex(line.substringAfter(":").replace(NAMED_GROUP, "(?:"))
            line.startsWith("ws:") -> pathToRegex(line.removePrefix("ws:"))
            else -> pathToRegex(line)
        }

        /** `ai/quality-gate/<uuid:session_id>/` → `^ai/quality-gate/[^/]+/$`; `<path:x>` spans segments. */
        private fun pathToRegex(pattern: String): Regex {
            val converter = Regex("<([^>]+)>")
            val sb = StringBuilder("^")
            var last = 0
            converter.findAll(pattern).forEach { match ->
                sb.append(Regex.escape(pattern.substring(last, match.range.first)))
                sb.append(if (match.groupValues[1].startsWith("path:")) ".+" else "[^/]+")
                last = match.range.last + 1
            }
            sb.append(Regex.escape(pattern.substring(last))).append("$")
            return Regex(sb.toString())
        }

        fun matches(catalog: List<Regex>, retrofitPath: String): Boolean {
            val path = retrofitPath.removePrefix("/").substringBefore('?')
            val candidates = if (path.contains('{')) PARAM_SAMPLES.map { sample -> path.replace(Regex("\\{[^}]+}"), sample) } else listOf(path)
            return catalog.any { regex -> candidates.any { regex.matches(it) } }
        }

        private const val APP_PACKAGE_DIR = "com/lexken/lexkenai/"

        /**
         * Every interface under `com.lexken.lexkenai` on the test classpath, with its Retrofit
         * methods. The classpath is walked entry by entry — directories and jars alike — because
         * AGP hands the tested classes to the unit-test worker in both forms.
         */
        fun scanEndpoints(): List<Endpoint> {
            val loader = Thread.currentThread().contextClassLoader
            val classNames = System.getProperty("java.class.path").split(File.pathSeparator).flatMap { entry ->
                val file = File(entry)
                when {
                    file.isDirectory -> file.walkTopDown()
                        .filter { it.isFile && it.extension == "class" }
                        .map { it.relativeTo(file).path.replace(File.separatorChar, '/') }
                        .toList()

                    file.isFile && file.extension == "jar" -> runCatching {
                        JarFile(file).use { jar -> jar.entries().toList().map { it.name }.filter { it.endsWith(".class") } }
                    }.getOrDefault(emptyList())

                    else -> emptyList()
                }
            }
                .filter { it.startsWith(APP_PACKAGE_DIR) }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .distinct()
            return classNames.mapNotNull { name -> runCatching { Class.forName(name, false, loader) }.getOrNull() }
                .filter { it.isInterface }
                .flatMap { api ->
                    api.declaredMethods.mapNotNull { method ->
                        val (verb, path) = method.httpRoute() ?: return@mapNotNull null
                        // A blank path is a dynamic `@Url` call (a DRF `next` link); the page it follows is checked where it is produced.
                        if (path.isBlank()) return@mapNotNull null
                        Endpoint(api.simpleName, verb, path)
                    }
                }
                .sortedWith(compareBy({ it.api }, { it.path }))
        }

        private fun java.lang.reflect.Method.httpRoute(): Pair<String, String>? {
            getAnnotation(GET::class.java)?.let { return "GET" to it.value }
            getAnnotation(POST::class.java)?.let { return "POST" to it.value }
            getAnnotation(PUT::class.java)?.let { return "PUT" to it.value }
            getAnnotation(PATCH::class.java)?.let { return "PATCH" to it.value }
            getAnnotation(DELETE::class.java)?.let { return "DELETE" to it.value }
            getAnnotation(HEAD::class.java)?.let { return "HEAD" to it.value }
            getAnnotation(OPTIONS::class.java)?.let { return "OPTIONS" to it.value }
            getAnnotation(HTTP::class.java)?.let { return it.method to it.path }
            return null
        }
    }
}

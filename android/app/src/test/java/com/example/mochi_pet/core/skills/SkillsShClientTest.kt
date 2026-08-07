package com.example.mochi_pet.core.skills

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SkillsShClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search maps skills sh response`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "skills":[{
                        "id":"owner/repo/calendar",
                        "skillId":"calendar",
                        "name":"calendar",
                        "installs":42,
                        "source":"owner/repo"
                      }]
                    }
                    """.trimIndent(),
                ),
        )
        val client = SkillsShClient(
            searchUrl = server.url("/api/search").toString(),
        )

        val results = client.search("calendar")

        assertEquals("owner/repo/calendar", results.single().id)
        assertEquals(42, results.single().installs)
        assertEquals(
            "/api/search?q=calendar&limit=30",
            server.takeRequest().path,
        )
    }

    @Test
    fun `download resolves GitHub skill folder and frontmatter`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"default_branch":"main"}"""),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "tree":[{
                    "path":"skills/calendar/SKILL.md",
                    "type":"blob"
                  }]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                ---
                name: calendar
                version: 1.2.0
                description: Calendar help
                ---
                # Calendar
                """.trimIndent(),
            ),
        )
        val client = SkillsShClient(
            gitHubApiBaseUrl = server.url("/github").toString(),
            rawGitHubBaseUrl = server.url("/raw").toString(),
        )

        val skill = client.download(
            MarketSkillSummary(
                id = "owner/repo/calendar",
                skillId = "calendar",
                name = "calendar",
                installs = 1,
                source = "owner/repo",
            ),
        )

        assertEquals("1.2.0", skill.version)
        assertEquals("Calendar help", skill.description)
        assertEquals(
            server.url("/raw/owner/repo/main/skills/calendar/SKILL.md")
                .toString(),
            skill.sourceUrl,
        )
    }

    @Test
    fun `popular parses trending 24 hour leaderboard`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                <a class="group grid anything" href="/owner/repo/hot-skill">
                  <h3 class="name">hot-skill</h3>
                  <p class="source">owner/repo</p>
                  <span class="font-mono text-sm text-foreground">21.7K</span>
                </a>
                """.trimIndent(),
            ),
        )
        val client = SkillsShClient(
            trendingUrl = server.url("/trending").toString(),
        )

        val skill = client.popular().single()

        assertEquals("owner/repo/hot-skill", skill.id)
        assertEquals(21_700, skill.installs)
        assertEquals(InstallWindow.LAST_24_HOURS, skill.installWindow)
    }
}

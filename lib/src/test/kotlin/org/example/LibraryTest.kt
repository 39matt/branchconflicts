    package org.example

    import io.mockk.*
    import okhttp3.*
    import okio.FileNotFoundException
    import okio.IOException
    import org.json.JSONObject
    import org.junit.jupiter.api.*
    import java.io.ByteArrayInputStream
    import java.io.File
    import kotlin.test.assertEquals
    import kotlin.test.assertFailsWith
    import kotlin.test.assertTrue

    class LibraryTest {
        private lateinit var library: Library
        private lateinit var mockClient: OkHttpClient

        @BeforeEach
        fun setUp() {
            mockClient = mockk()
            library = spyk(Library(), recordPrivateCalls = true)
            library.client = mockClient
        }

        @Test
        fun `findBranchByNameRemote should return correct branch`() {
            val api = "https://api.github.com/repos/owner/repo"
            val branchName = "main"
            val jsonResponse = """
                [
                    {"name": "main", "commit": {"sha": "abc123"}},
                    {"name": "dev", "commit": {"sha": "def456"}}
                ]
            """.trimIndent()

            val response = mockk<Response>()
            val responseBody = mockk<ResponseBody>()

            every { mockClient.newCall(any()).execute() } returns response
            every { response.isSuccessful } returns true
            every { response.body } returns responseBody
            every { responseBody.string() } returns jsonResponse

            val branch = library.findBranchByNameRemote(api, branchName, "token")

            assertEquals(branchName, branch.name)
            assertEquals("abc123", branch.commitSha)
        }

        @Test
        fun `tryRequest should throw error if user not found`() {
            val api = "https://api.github.com/users/nonexistent"
            val response = mockk<Response>()

            every { mockClient.newCall(any()).execute() } returns response
            every { response.isSuccessful } returns false
            every { response.code } returns 404
            every { response.body?.string() } returns "Not Found"

            val ex = assertFailsWith<Exception> {
                library.tryRequest(api, repoOwner = "nonexistent")
            }

            assert(ex.message!!.contains("User 'nonexistent' not found"))
        }

        @Test
        fun `getLatestCommitRemote returns expected commit`() {
            val branch = Branch("main", "abc123")
            val api = "https://api.github.com/repos/owner/repo"
            val json = """
            {
                "sha": "abc123",
                "commit": {
                    "message": "Initial commit"
                },
                "parents": [{"sha": "000111"}, {"sha": "000222"}]
            }
            """

            val response = mockk<Response>()
            val body = mockk<ResponseBody>()

            every { mockClient.newCall(any()).execute() } returns response
            every { response.isSuccessful } returns true
            every { response.body } returns body
            every { body.string() } returns json

            val commit = library.getLatestCommitRemote(api, branch, "owner", "repo", null)

            assertEquals("abc123", commit.sha)
            assertEquals("Initial commit", commit.message)
            assertEquals(listOf("000111", "000222"), commit.parentsSha)
        }

        @Test
        fun `getLatestCommitRemote returns parsed commit from GitHub`() {
            val branch = Branch("main", "abc123")
            val owner = "matija"
            val repo = "test-repo"
            val api = "https://api.github.com/repos/$owner/$repo"
            val accessToken = "token123"

            val jsonResponse = """
            {
              "sha": "abc123",
              "commit": { "message": "Initial commit" },
              "parents": [
                { "sha": "parent1" },
                { "sha": "parent2" }
              ]
            }
        """.trimIndent()

            val mockResponse = mockk<Response>()
            val mockBody = mockk<ResponseBody>()

            every { mockClient.newCall(any()).execute() } returns mockResponse
            every { mockResponse.isSuccessful } returns true
            every { mockResponse.body } returns mockBody
            every { mockBody.string() } returns jsonResponse

            val result = library.getLatestCommitRemote(api, branch, owner, repo, accessToken)

            assertEquals("abc123", result.sha)
            assertEquals("Initial commit", result.message)
            assertEquals(listOf("parent1", "parent2"), result.parentsSha)
        }

        @Test
        fun `findModifiedFilesRemote parses file list`() {
            val api = "https://api.github.com/repos/user/repo"
            val latestSha = "abc123"
            val mergeBase = "def456"
            val responseJson = """
                {
                  "files": [
                    {"filename": "file1.txt"},
                    {"filename": "file2.kt"}
                  ]
                }
            """.trimIndent()

            val mockResponse = mockk<Response>()
            val mockBody = mockk<ResponseBody>()

            every { mockClient.newCall(any()).execute() } returns mockResponse
            every { mockResponse.isSuccessful } returns true
            every { mockResponse.body } returns mockBody
            every { mockBody.string() } returns responseJson

            val files = library.findModifiedFilesRemote(api, latestSha, mergeBase)
            assertEquals(listOf("file1.txt", "file2.kt"), files)
        }

    }

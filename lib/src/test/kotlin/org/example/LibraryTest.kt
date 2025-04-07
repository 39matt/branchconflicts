    package org.example

    import io.mockk.*
    import okhttp3.*
    import okio.FileNotFoundException
    import okio.IOException
    import org.json.JSONObject
    import org.junit.jupiter.api.*
    import org.junit.jupiter.api.io.TempDir
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
            mockkConstructor(File::class)
            mockkConstructor(ProcessBuilder::class)
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
        fun `findBranchByNameLocal throws FileNotFoundException for non-existent branch`(@TempDir tempDir: File) {
            val nonExistentBranch = "nonexistent"
            val exception = assertFailsWith<FileNotFoundException> {
                library.findBranchByNameLocal(tempDir.absolutePath, nonExistentBranch)
            }
            assert(exception.message!!.contains("Local branch '$nonExistentBranch' not found"))
        }

        @Test
        fun `findBranchByNameLocal throws Exception when SHA is missing`(@TempDir tempDir: File) {
            val branchName = "feature/empty"
            val gitDir = File(tempDir, ".git/logs/refs/heads")
            val fullBranchPath = File(gitDir, branchName)

            fullBranchPath.parentFile.mkdirs()

            fullBranchPath.writeText("not enough fields here")

            assertFailsWith<Exception> {
                library.findBranchByNameLocal(tempDir.absolutePath, branchName)
            }
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
        fun `getLatestCommitRemote should return expected commit`() {
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
        fun `getLatestCommitRemote should return parsed commit from GitHub`() {
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
        fun `getLatestCommitLocal returns correct commit`(@TempDir tempDir: File) {
            val branchName = "main"
            val branch = Branch(branchName, "abc123")
            val logDir = File(tempDir, ".git/logs/refs/heads")
            logDir.mkdirs()

            val logFile = File(logDir, branchName)
            logFile.writeText(
                """
            0000000000000000000000000000000000000000 abc123 Author <author@example.com> 0 +0000	commit: Initial commit
            abc123 def456 Author <author@example.com> 0 +0000	commit: Second commit
            def456 ghi789 Author <author@example.com> 0 +0000	commit: Third commit
            """.trimIndent()
            )

            val commit = library.getLatestCommitLocal(branch, tempDir.absolutePath)

            assertEquals("ghi789", commit.sha)
            assertEquals("Third commit", commit.message)
            assertEquals(listOf("abc123", "def456"), commit.parentsSha)
        }

        @Test
        fun `getLatestCommitLocal throws FileNotFoundException if file doesn't exist`(@TempDir tempDir: File) {
            val branch = Branch("missing", "xyz")
            assertFailsWith<FileNotFoundException> {
                library.getLatestCommitLocal(branch, tempDir.absolutePath)
            }
        }

        @Test
        fun `getLatestCommitLocal throws IOException for empty file`(@TempDir tempDir: File) {
            val branch = Branch("empty", "xyz")
            val logFile = File(tempDir, ".git/logs/refs/heads/empty")
            logFile.parentFile.mkdirs()
            logFile.writeText("")

            assertFailsWith<IOException> {
                library.getLatestCommitLocal(branch, tempDir.absolutePath)
            }
        }

        @Test
        fun `findModifiedFilesRemote should parse file list`() {
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

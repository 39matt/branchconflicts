/*
 * This source file was generated by the Gradle 'init' task
 */
package org.example

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.FileNotFoundException
import okio.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File


data class Branch(
    var name: String,
    var commitSha: String
)

data class Commit(
    var message: String,
    var sha: String,
    var parentsSha: MutableList<String>
)

class Library {
    val client = OkHttpClient()

    @Throws(Exception::class, IOException::class)
    fun findBranchByNameRemote(api: String, branchName: String, accessToken: String? = null): Branch {

        val request = Request.Builder()
            .url("$api/branches")
            .apply {
                if (!accessToken.isNullOrBlank()) {
                    header("Authorization", "token $accessToken")
                }
            }
            .build()

        val response: Response
        try {
            response = client.newCall(request).execute()
        } catch (e: Exception) {
            throw Exception(e)
        }

        if (!response.isSuccessful) {
            throw Exception("GitHub API error: ${response.code} - ${response.body?.string()}")
        }

        val body = response.body?.string() ?: throw Exception("Empty response body")
        val branchesArray = JSONArray(body)

        for (i in 0 until branchesArray.length()) {
            val branchJson = branchesArray.getJSONObject(i)
            if (branchJson.getString("name") == branchName) {
                val sha = branchJson.getJSONObject("commit").getString("sha")
                return Branch(branchName, sha)
            }
        }

        throw Exception("Branch '$branchName' not found")
    }

    @Throws(IOException::class, Exception::class, FileNotFoundException::class)
    fun findBranchByNameLocal(localRepoPath: String, branchName: String): Branch {
        val branchLogsPath = "$localRepoPath/.git/logs/refs/heads/$branchName"
        val branchDirPath = "$localRepoPath/.git/logs/refs/heads"

        val branchFile = File(branchLogsPath)
        if (!branchFile.exists()) {
            throw FileNotFoundException("Local branch '$branchName' not found")
        }

        val localBranchList = File(branchDirPath).list() ?: emptyArray()
        val branchNameLocal = localBranchList.find { it == branchName }

        val lastLine = branchFile.readLines().lastOrNull()
            ?: throw IOException("No log found for branch '$branchName'")

        val sha = lastLine.split(" ").getOrNull(1) ?: throw Exception("No commit SHA found in the log")

        return Branch(branchNameLocal ?: throw Exception("Branch '$branchName' not found in branch list"), sha)
    }

    @Throws(Exception::class, IOException::class)
    fun getLatestCommitRemote(api: String, branch: Branch, owner: String, repo: String, accessToken: String? = null): Commit {
        val request = Request.Builder()
            .url("$api/commits/${branch.commitSha}")
            .apply {
                if (!accessToken.isNullOrBlank()) {
                    header("Authorization", "token $accessToken")
                }
            }
            .build()

        val response: Response
        try {
            response = client.newCall(request).execute()
        } catch (e: Exception) {
            throw Exception(e)
        }

        if (!response.isSuccessful) {
            if (response.code == 404) {
                throw Exception("Commit ${branch.name} not found")
            }
            throw Exception("GitHub API error: ${response.code} - ${response.body?.string()}")
        }

        val json = JSONObject(response.body?.string() ?: throw Exception("Empty response body"))

        val message = json.getJSONObject("commit").getString("message")
        val sha = json.getString("sha")
        val parentsSha = mutableListOf<String>()

        val parentsArray = json.getJSONArray("parents")
        for (i in 0 until parentsArray.length()) {
            val parentSha = parentsArray.getJSONObject(i).getString("sha")
            parentsSha.add(parentSha)
        }

        return Commit(message, sha, parentsSha)
    }

    @Throws(Exception::class, IOException::class, FileNotFoundException::class)
    fun getLatestCommitLocal(branch: Branch, localRepoPath: String): Commit {
        val logPath = "$localRepoPath/.git/logs/refs/heads/${branch.name}"
        val branchFile = File(logPath)

        if (!branchFile.exists()) {
            throw FileNotFoundException("Local branch '${branch.name}' not found")
        }

        if (branchFile.length() == 0L) {
            throw IOException("Local branch '${branch.name}' does not have any changes")
        }

        val lines = branchFile.readLines()
        if (lines.size < 2) {
            throw IOException("Not enough log entries in branch '${branch.name}'")
        }

        val commit = Commit("", "", mutableListOf())

        for (line in lines.drop(1)) {
            val fields = line.split(" ")
            if (fields.size < 7) continue
            commit.parentsSha.add(fields[0])
        }

        val lastLine = lines.last()
        val lastFields = lastLine.split(" ")
        if (lastFields.size >= 7) {
            commit.sha = lastFields[1]
            commit.message = lastFields.subList(6, lastFields.size).joinToString(" ")
        } else {
            throw IOException("Malformed last line in git log for branch '${branch.name}'")
        }

        return commit
    }

    @Throws(Exception::class)
    fun findMergeBase(api: String, branchA: Branch, branchB: Branch, repoOwner: String, repoName: String, localRepoPath: String, accessToken: String? = null): String {
        val latestCommitRemote = getLatestCommitRemote(api, branchA, repoOwner, repoName, accessToken)
        val latestCommitLocal = getLatestCommitLocal(branchB, localRepoPath)

        for (sha in latestCommitRemote.parentsSha) {
            if (sha in latestCommitLocal.parentsSha) {
                return sha
            }
        }
        throw Exception("Branches '${branchA.name}' and '${branchB.name}' do not share a merge base!")
    }


}

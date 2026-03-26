package com.les.databuoy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ServiceCreationConventionsTest {

    @Test
    fun `golden path assets exist`() {
        assertExists("templates/YourModel.kt.template")
        assertExists("templates/YourModelRequestTag.kt.template")
        assertExists("templates/YourModelServerProcessingConfig.kt.template")
        assertExists("templates/YourModelService.kt.template")
        assertExists("templates/YourModelServiceTest.kt.template")
        assertExists("examples/todo/README.md")
        assertExists("examples/todo/src/main/kotlin/com/les/databuoy/examples/todo/Todo.kt")
        assertExists("examples/todo/src/main/kotlin/com/les/databuoy/examples/todo/TodoRequestTag.kt")
        assertExists("examples/todo/src/main/kotlin/com/les/databuoy/examples/todo/TodoServerProcessingConfig.kt")
        assertExists("examples/todo/src/main/kotlin/com/les/databuoy/examples/todo/TodoService.kt")
        assertExists("examples/todo/src/test/kotlin/com/les/databuoy/examples/todo/TodoServiceTest.kt")
    }

    @Test
    fun `docs and agent guide describe the golden path`() {
        val codex = readRepoFile("CODEX.md")
        val docs = readRepoFile("docs/creating-a-service.md")

        listOf(
            "templates/",
            "examples/todo/",
            "Create the model",
            "Define the request tag enum",
            "Implement the server config",
            "Build the service",
            "Register the service",
            "Add an integration test",
        ).forEach { snippet ->
            assertContains(codex, snippet, "CODEX.md")
            assertContains(docs, snippet, "docs/creating-a-service.md")
        }
    }

    @Test
    fun `templates encode the required service conventions`() {
        assertFileContains(
            "templates/YourModel.kt.template",
            "@Serializable",
            ": SyncableObject<YourModel>",
            "@Transient override val syncStatus",
            "override fun withSyncStatus",
        )
        assertFileContains(
            "templates/YourModelRequestTag.kt.template",
            ": ServiceRequestTag",
            "CREATE(\"create\")",
            "UPDATE(\"update\")",
            "VOID(\"void\")",
        )
        assertFileContains(
            "templates/YourModelServerProcessingConfig.kt.template",
            ": ServerProcessingConfig<YourModel>",
            "override val syncFetchConfig",
            "override val syncUpConfig",
            "fromResponseBody(requestTag: String, responseBody: JsonObject)",
            "override val serviceHeaders",
        )
        assertFileContains(
            "templates/YourModelService.kt.template",
            ": SyncableObjectService<YourModel, YourModelRequestTag>",
            "serializer = YourModel.serializer()",
            "CreateRequestBuilder",
            "UpdateRequestBuilder",
            "VoidRequestBuilder",
            "ResponseUnpacker",
            "requestTag = YourModelRequestTag.CREATE",
            "requestTag = YourModelRequestTag.UPDATE",
            "requestTag = YourModelRequestTag.VOID",
        )
        assertFileContains(
            "templates/YourModelServiceTest.kt.template",
            "TestServiceEnvironment",
            "env.mockRouter.onGet",
            "env.mockRouter.onPost",
            "env.connectivityChecker",
            "stopPeriodicSyncDown()",
        )
    }

    @Test
    fun `todo example stays aligned with the templates`() {
        assertFileContains(
            "examples/todo/src/main/kotlin/com/les/databuoy/examples/todo/Todo.kt",
            "@Serializable",
            ": SyncableObject<Todo>",
            "override fun withSyncStatus",
        )
        assertFileContains(
            "examples/todo/src/main/kotlin/com/les/databuoy/examples/todo/TodoRequestTag.kt",
            ": ServiceRequestTag",
        )
        assertFileContains(
            "examples/todo/src/main/kotlin/com/les/databuoy/examples/todo/TodoServerProcessingConfig.kt",
            ": ServerProcessingConfig<Todo>",
            "override val syncFetchConfig",
            "override val syncUpConfig",
            "override val serviceHeaders",
        )
        assertFileContains(
            "examples/todo/src/main/kotlin/com/les/databuoy/examples/todo/TodoService.kt",
            ": SyncableObjectService<Todo, TodoRequestTag>",
            "requestTag = TodoRequestTag.CREATE",
            "requestTag = TodoRequestTag.UPDATE",
            "requestTag = TodoRequestTag.VOID",
            "HttpRequest.serverIdOrPlaceholder(",
        )
        assertFileContains(
            "examples/todo/src/test/kotlin/com/les/databuoy/examples/todo/TodoServiceTest.kt",
            "TestServiceEnvironment",
            "syncUpLocalChanges",
            "stopPeriodicSyncDown()",
        )
    }

    private fun assertExists(relativePath: String) {
        assertTrue(repoFile(relativePath).exists(), "Expected $relativePath to exist")
    }

    private fun assertFileContains(relativePath: String, vararg snippets: String) {
        val contents = readRepoFile(relativePath)
        snippets.forEach { snippet -> assertContains(contents, snippet, relativePath) }
    }

    private fun assertContains(contents: String, snippet: String, fileName: String) {
        assertTrue(
            contents.contains(snippet),
            "Expected $fileName to contain `$snippet`",
        )
    }

    private fun readRepoFile(relativePath: String): String = repoFile(relativePath).readText()

    private fun repoFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        val root = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "CODEX.md").exists() }
            ?: error("Failed to locate repo root from ${start.path}")
        return File(root, relativePath)
    }
}

package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.TaskCategory
import com.example.data.model.TaskPriority
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TaskRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createTask_validPayload_createsDocumentAndReturnsId() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repository = TaskRepository(firestore)

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createTask(
                title = TASK_TITLE,
                notes = "Chuẩn bị tài liệu họp sáng thứ Hai",
                category = TaskCategory.WORK,
                priority = TaskPriority.HIGH
            )
        }
        assertTrue(createResult.isSuccess)
        val taskId = createResult.getOrThrow()
        assertTrue(taskId.isNotEmpty())
    }

    @Test
    fun getUserTasks_authenticatedOwner_returnsMatchingTasks() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = TaskRepository(firestore)
        val taskId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createTask(title = TASK_TITLE).getOrThrow()
        }

        val tasksResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.getUserTasks(aliceUid)
        }
        assertTrue(tasksResult.isSuccess)
        assertTrue(tasksResult.getOrThrow().any { it.id == taskId })
    }

    @Test
    fun observeTasks_authenticatedOwner_emitsRealtimeUpdatesAndToggle() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = TaskRepository(firestore)
        val taskId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createTask(title = TASK_TITLE).getOrThrow()
        }

        val emittedTasks = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeTasks(aliceUid).first { list -> list.any { it.id == taskId } }
        }
        assertTrue(emittedTasks.any { it.id == taskId && !it.completed })

        val toggleResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.toggleTaskCompletion(taskId, true)
        }
        assertTrue(toggleResult.isSuccess)
    }

    @Test
    fun getTaskById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = TaskRepository(firestore)
        val taskId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createTask(title = TASK_TITLE).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = TaskRepository(firestore)
        val crossUserResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getTaskById(taskId = taskId, ownerUserId = aliceUid)
        }
        assertTrue(crossUserResult.isFailure)
        val exception = crossUserResult.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull(exception)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, exception?.code)
    }

    @Test
    fun observeTasks_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = TaskRepository(firestore)

        var caughtException: Exception? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeTasks("alice_unauth_test").first()
            }
            fail("Expected exception for unauthenticated observeTasks")
        } catch (e: Exception) {
            caughtException = e
        }

        val firestoreException = checkNotNull(caughtException?.unwrapFirestoreException()) {
            "Expected nested FirebaseFirestoreException, got $caughtException"
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException.code)
    }

    private companion object {
        const val ALICE_EMAIL = "alice@test.com"
        const val BOB_EMAIL = "bob@test.com"
        const val TASK_TITLE = "Hoàn thành kế hoạch dự án"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}

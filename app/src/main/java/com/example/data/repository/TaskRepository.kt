package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.TaskCategory
import com.example.data.model.TaskPriority
import com.example.data.model.TodoTask
import com.example.data.remote.OperationType
import com.example.data.remote.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class TaskRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeTasks(userId: String = auth.currentUser?.uid ?: "unauthenticated"): Flow<List<TodoTask>> {
        val path = "users/$userId/tasks"
        return db.collection("users")
            .document(userId)
            .collection("tasks")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    TodoTask::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
    }

    suspend fun getUserTasks(userId: String = auth.currentUser?.uid ?: "unauthenticated"): Result<List<TodoTask>> {
        val path = "users/$userId/tasks"
        return try {
            val snapshot = db.collection("users")
                .document(userId)
                .collection("tasks")
                .get()
                .await()
            val tasks = snapshot.toObjects(
                TodoTask::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(tasks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun getTaskById(
        taskId: String,
        ownerUserId: String = auth.currentUser?.uid ?: "unauthenticated"
    ): Result<TodoTask?> {
        val docRef = db.collection("users")
            .document(ownerUserId)
            .collection("tasks")
            .document(taskId)
        return try {
            val snapshot = docRef.get().await()
            val task = snapshot.toObject(
                TodoTask::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(task)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createTask(
        title: String,
        notes: String = "",
        category: TaskCategory = TaskCategory.PERSONAL,
        priority: TaskPriority = TaskPriority.MEDIUM,
        dueDateMillis: Long = 0L
    ): Result<String> {
        val uid = requireUserId()
        val trimmedTitle = title.trim()
        require(trimmedTitle.isNotEmpty()) { "Task title must not be blank" }
        val docRef = db.collection("users")
            .document(uid)
            .collection("tasks")
            .document()

        val payload = mapOf(
            "userId" to uid,
            "title" to trimmedTitle.take(200),
            "notes" to notes.trim().take(1000),
            "category" to category.name,
            "priority" to priority.name,
            "completed" to false,
            "dueDateMillis" to dueDateMillis.coerceAtLeast(0L),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        return try {
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateTask(task: TodoTask): Result<Unit> {
        val uid = requireUserId()
        require(task.id.isNotBlank()) { "Task ID must not be blank for update" }
        requireNotNull(task.createdAt) { "Task createdAt must not be null for update" }
        val docRef = db.collection("users")
            .document(uid)
            .collection("tasks")
            .document(task.id)

        val updatedModel = task.copy(
            userId = uid,
            title = task.title.trim().take(200),
            notes = task.notes.trim().take(1000),
            dueDateMillis = task.dueDateMillis.coerceAtLeast(0L),
            updatedAt = null
        )

        return try {
            docRef.set(updatedModel).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun toggleTaskCompletion(taskId: String, completed: Boolean): Result<Unit> {
        val uid = requireUserId()
        require(taskId.isNotBlank()) { "Task ID must not be blank" }
        val docRef = db.collection("users")
            .document(uid)
            .collection("tasks")
            .document(taskId)

        val updates = mapOf(
            "completed" to completed,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        return try {
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteTask(taskId: String): Result<Unit> {
        val uid = requireUserId()
        require(taskId.isNotBlank()) { "Task ID must not be blank" }
        val docRef = db.collection("users")
            .document(uid)
            .collection("tasks")
            .document(taskId)

        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun clearCompletedTasks(completedTaskIds: List<String>): Result<Unit> {
        val uid = requireUserId()
        if (completedTaskIds.isEmpty()) return Result.success(Unit)
        val path = "users/$uid/tasks"
        return try {
            val batch = db.batch()
            completedTaskIds.forEach { taskId ->
                if (taskId.isNotBlank()) {
                    val docRef = db.collection("users")
                        .document(uid)
                        .collection("tasks")
                        .document(taskId)
                    batch.delete(docRef)
                }
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }
}

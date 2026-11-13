package com.example.ui.todo

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.TaskCategory
import com.example.data.model.TaskFilter
import com.example.data.model.TaskPriority
import com.example.data.model.TodoTask
import com.example.data.repository.TaskRepository
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TasksUiState {
    data object Loading : TasksUiState
    data class Success(
        val allTasks: List<TodoTask>,
        val filteredTasks: List<TodoTask>,
        val completedCount: Int,
        val totalCount: Int
    ) : TasksUiState
    data class Error(val message: String) : TasksUiState
}

class TodoViewModel(
    private val repository: TaskRepository,
    private val currentUserId: String
) : ViewModel() {

    private val _selectedFilter = MutableStateFlow(TaskFilter.ALL)
    val selectedFilter: StateFlow<TaskFilter> = _selectedFilter.asStateFlow()

    private val _selectedCategory = MutableStateFlow<TaskCategory?>(null)
    val selectedCategory: StateFlow<TaskCategory?> = _selectedCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val upstreamTasksFlow = repository.observeTasks(currentUserId)
        .map<List<TodoTask>, Result<List<TodoTask>>> { Result.success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing tasks", rootError)
            emit(Result.failure(rootError))
        }

    val uiState: StateFlow<TasksUiState> = combine(
        upstreamTasksFlow,
        _selectedFilter,
        _selectedCategory,
        _searchQuery
    ) { result, filter, category, query ->
        result.fold(
            onSuccess = { tasks ->
                val sortedTasks = tasks.sortedWith(
                    compareBy<TodoTask> { it.completed }
                        .thenByDescending { it.priorityEnum.weight }
                        .thenByDescending { it.createdAt ?: Timestamp(0, 0) }
                )

                val filtered = sortedTasks.filter { task ->
                    val matchesStatus = when (filter) {
                        TaskFilter.ALL -> true
                        TaskFilter.ACTIVE -> !task.completed
                        TaskFilter.COMPLETED -> task.completed
                    }
                    val matchesCategory = category == null || task.categoryEnum == category
                    val trimmedQuery = query.trim()
                    val matchesQuery = trimmedQuery.isEmpty() ||
                        task.title.contains(trimmedQuery, ignoreCase = true) ||
                        task.notes.contains(trimmedQuery, ignoreCase = true)

                    matchesStatus && matchesCategory && matchesQuery
                }

                TasksUiState.Success(
                    allTasks = sortedTasks,
                    filteredTasks = filtered,
                    completedCount = sortedTasks.count { it.completed },
                    totalCount = sortedTasks.size
                )
            },
            onFailure = { error ->
                TasksUiState.Error(error.localizedMessage ?: "Không thể tải danh sách công việc")
            }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = TasksUiState.Loading
    )

    fun setFilter(filter: TaskFilter) {
        _selectedFilter.value = filter
    }

    fun setCategoryFilter(category: TaskCategory?) {
        _selectedCategory.value = category
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearActionError() {
        _actionError.value = null
    }

    fun addTask(
        title: String,
        notes: String,
        category: TaskCategory,
        priority: TaskPriority,
        dueDateMillis: Long
    ) {
        viewModelScope.launch {
            val result = repository.createTask(
                title = title,
                notes = notes,
                category = category,
                priority = priority,
                dueDateMillis = dueDateMillis
            )
            result.onFailure { e ->
                _actionError.value = e.localizedMessage ?: "Không thể thêm công việc"
            }
        }
    }

    fun updateTask(
        existingTask: TodoTask,
        title: String,
        notes: String,
        category: TaskCategory,
        priority: TaskPriority,
        dueDateMillis: Long
    ) {
        viewModelScope.launch {
            val updated = existingTask.copy(
                title = title,
                notes = notes,
                category = category.name,
                priority = priority.name,
                dueDateMillis = dueDateMillis
            )
            val result = repository.updateTask(updated)
            result.onFailure { e ->
                _actionError.value = e.localizedMessage ?: "Không thể cập nhật công việc"
            }
        }
    }

    fun toggleTaskCompletion(task: TodoTask) {
        viewModelScope.launch {
            val result = repository.toggleTaskCompletion(task.id, !task.completed)
            result.onFailure { e ->
                _actionError.value = e.localizedMessage ?: "Không thể cập nhật trạng thái"
            }
        }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            val result = repository.deleteTask(taskId)
            result.onFailure { e ->
                _actionError.value = e.localizedMessage ?: "Không thể xóa công việc"
            }
        }
    }

    fun clearCompletedTasks() {
        val currentState = uiState.value as? TasksUiState.Success ?: return
        val completedIds = currentState.allTasks.filter { it.completed }.map { it.id }
        if (completedIds.isEmpty()) return
        viewModelScope.launch {
            val result = repository.clearCompletedTasks(completedIds)
            result.onFailure { e ->
                _actionError.value = e.localizedMessage ?: "Không thể xóa công việc đã hoàn thành"
            }
        }
    }

    private companion object {
        const val TAG = "TodoViewModel"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}

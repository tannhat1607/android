package com.example.data.model

import androidx.annotation.StringRes
import com.example.R
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class TodoTask(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val notes: String = "",
    val category: String = TaskCategory.PERSONAL.name,
    val priority: String = TaskPriority.MEDIUM.name,
    val completed: Boolean = false,
    val dueDateMillis: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val categoryEnum: TaskCategory
        get() = TaskCategory.fromName(category)

    val priorityEnum: TaskPriority
        get() = TaskPriority.fromName(priority)
}

enum class TaskCategory(@StringRes val labelResId: Int) {
    PERSONAL(R.string.category_personal),
    WORK(R.string.category_work),
    SHOPPING(R.string.category_shopping),
    STUDY(R.string.category_study),
    HEALTH(R.string.category_health);

    companion object {
        fun fromName(name: String): TaskCategory =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: PERSONAL
    }
}

enum class TaskPriority(@StringRes val labelResId: Int, val weight: Int) {
    HIGH(R.string.priority_high, 3),
    MEDIUM(R.string.priority_medium, 2),
    LOW(R.string.priority_low, 1);

    companion object {
        fun fromName(name: String): TaskPriority =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MEDIUM
    }
}

enum class TaskFilter(@StringRes val labelResId: Int) {
    ALL(R.string.filter_all),
    ACTIVE(R.string.filter_active),
    COMPLETED(R.string.filter_completed)
}

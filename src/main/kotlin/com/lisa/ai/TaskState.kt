package com.lisa.ai

import java.time.Instant
import java.util.UUID

enum class TaskStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TaskState(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val status: TaskStatus = TaskStatus.QUEUED,
    val currentStep: String = "Waiting to start",
    val completedSteps: Int = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
    val errorMessage: String? = null
) {
    fun update(
        status: TaskStatus = this.status,
        currentStep: String = this.currentStep,
        completedSteps: Int = this.completedSteps,
        errorMessage: String? = this.errorMessage
    ): TaskState = copy(
        status = status,
        currentStep = currentStep,
        completedSteps = completedSteps,
        updatedAt = Instant.now(),
        errorMessage = errorMessage
    )
}

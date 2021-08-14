package pro.devsvc.unitask.store.nitrite

import pro.devsvc.unitask.core.model.Task

interface TaskStore {

    fun store(task: Task, oldTask: Task? = null)
    fun store(tasks: List<Task>)
    fun list(): Sequence<Task>
    fun find(title: String): Task?
    fun find(map: Map<String, Any?>): Task?
}
package pro.devsvc.unitask.core.model

import kotlinx.serialization.Serializable
import pro.devsvc.unitask.core.serial.KZonedDateTimeSerializer
import java.time.ZonedDateTime
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.serializers.DateTimePeriodIso8601Serializer

typealias UniTask = Task

@Serializable
data class Task(
    /** id from notion is uuid, so have to be string */
    var id: String,
    var title: String,
    var type: TaskType = TaskType.TASK
) {

    var desc: String = ""
    var status: String = ""
    /** the user id */
    var assignedUserId: String = ""
    var assignedUserName: String = ""
    @Serializable(with = KZonedDateTimeSerializer::class)
    var estStarted: ZonedDateTime? = null
    @Serializable(with = KZonedDateTimeSerializer::class)
    var deadline: ZonedDateTime? = null
    @Serializable(with = DateTimePeriodIso8601Serializer::class)
    var estimate: DateTimePeriod? = null
    @Serializable(with = KZonedDateTimeSerializer::class)
    var createTime: ZonedDateTime? = null
    @Serializable(with = KZonedDateTimeSerializer::class)
    var lastEditTime: ZonedDateTime? = null
    var lastEditBy: String = ""

    var projectId: String? = null
    var projectName: String? = null
    var planId: String? = null
    var planName: String? = null
    var productId: String? = null
    var productName: String? = null

    var customProperties = mutableMapOf<String, String?>()
}

enum class TaskType(val type: String) {
    TASK("Task"),
    PROJECT("Project"),
    BUG("Bug"),
    PERSON("Person")
}

enum class TaskStatus(val code: String, name: String, val color: String) {
    // WAIT("")
}

package pro.devsvc.unitask.connector.notion

import notion.api.v1.NotionClient
import notion.api.v1.http.OkHttp4Client
import notion.api.v1.logging.Slf4jLogger
import notion.api.v1.model.databases.DatabaseProperty
import notion.api.v1.model.databases.Databases
import notion.api.v1.model.databases.query.filter.condition.SelectFilter
import notion.api.v1.model.databases.query.filter.condition.TextFilter
import notion.api.v1.model.pages.Page
import notion.api.v1.model.pages.PageParent
import notion.api.v1.model.pages.PageProperty
import notion.api.v1.model.pages.PageProperty.RichText
import notion.api.v1.model.pages.PageProperty.RichText.Text
import notion.api.v1.request.pages.CreatePageRequest
import org.slf4j.LoggerFactory
import pro.devsvc.unitask.connector.Connector
import pro.devsvc.unitask.core.model.Task
import pro.devsvc.unitask.core.model.TaskType
import pro.devsvc.unitask.store.nitrite.TaskStore
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField


class NotionConnector(token: String = System.getProperty("NOTION_TOKEN"),
    private val database: String) : Connector {

    private val log = LoggerFactory.getLogger(javaClass)
    private var formatter: DateTimeFormatter
    private val NOTION_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

    init {
        val dtfb = DateTimeFormatterBuilder()
        dtfb.appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX"))
            .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS"))
            .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSS"))
            .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSS"))
            .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"))
            .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSS"))
            .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSS"))
            .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
            .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SS"))
            .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S"))
            .appendOptional(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
        formatter = dtfb.toFormatter().withZone(ZoneId.systemDefault())
    }

    private val client = NotionClient(
        token = token,
        httpClient = OkHttp4Client(connectTimeoutMillis = 10_000),
        logger = Slf4jLogger(),
    )

    private var databaseId = ""
    private val schema = mutableMapOf<String, DatabaseProperty>()

    override fun start(store: TaskStore) {
        for (db in listDatabase().results) {
            if (db.title[0].plainText != database) {
                continue
            }
            databaseId = db.id
            schema.putAll(db.properties)

            val pages = client.queryDatabase(db.id).results
            for (page in pages) {
                syncToStore(page, store)
            }
        }
        syncToNotion(store)
    }

    private fun syncToStore(page: Page, store: TaskStore) {
        val title = page.properties["Name"]?.title?.firstOrNull()?.plainText
        if (title != null) {
            // handle if renamed
            val existing = store.find(mapOf("customProperties.notion_id" to page.id))
            if (existing != null) {
                log.error("")
            }

            val task = Task(title)
            task.customProperties["notion_id"] = page.id
            task.customProperties["status"] = page.properties["Status"]?.select?.id
            task.createTime = parseDateTime(page.createdTime)
            task.estStarted = parseDateTime(page.properties["Due Date"]?.date?.start)
            task.deadline = parseDateTime(page.properties["Due Date"]?.date?.end)
            task.lastEditTime = parseDateTime(page.lastEditedTime)
            val typeName = page.properties["Type"]?.select?.name
            if (typeName != null && typeName.isNotBlank()) {
                task.type = TaskType.valueOf(typeName.toUpperCase())
            }
            store.store(task)
        }
    }

    private fun syncToNotion(store: TaskStore) {
        for (task in store.list()) {
            if (task.title != null) {
                val notionId = task.customProperties["notion_id"]
                val properties = taskToNotionPageProperties(task)
                if (properties.isEmpty()) {
                    continue
                }

                if (task.type == TaskType.TASK && task.projectName != null) {
                    // if project is already in notion, construct the relation
                    // if not, have to do this in next sync. TODO: better solution?
                    val projectInNotion = client.queryDatabase(databaseId, NCompoundFilter(
                        and = listOf(
                            NPropertyFilter("Name", title = TextFilter(task.projectName)),
                            NPropertyFilter("Type", select = SelectFilter("Project"))
                        )
                    ))
                    if (projectInNotion.results.isNotEmpty()) {
                        val projectInNotionPage = projectInNotion.results[0]
                        properties["Project"] = PageProperty(relation = listOf(
                            PageProperty.PageReference(projectInNotionPage.id)
                        ))
                    }
                }

                if (notionId != null) {
                    if (properties.isNotEmpty()) {
                        val page = client.retrievePage(notionId)
                        val notionLastEditTime = parseDateTime(page.lastEditedTime)
                        if (notionLastEditTime!!.isBefore(task.lastEditTime)) {
                            val result = client.updatePageProperties(notionId, properties)
                            log.debug("update result $result")
                        } else {
                            log.debug("task ${task.title} last edit time is before or equals to notion's last edit time, skip...")
                        }
                    }
                } else {
                    client.createPage(CreatePageRequest(
                        PageParent.database(databaseId),
                        properties
                    ))
                }
            }
        }
    }

    private fun taskToNotionPageProperties(task: Task): MutableMap<String, PageProperty> {
        val properties = mutableMapOf<String, PageProperty>()
        if (task.title.isNotBlank()) {
            properties["Name"] = PageProperty(title = listOf(
                RichText(text = Text(task.title))
            ))
        }
        if (task.status.isNotBlank()) {
            properties["Status"] = PageProperty(select = findOptionInSchema("Status", task.status))
        }
        if (task.estStarted != null || task.deadline != null) {
            if (task.estStarted == null) {
                task.estStarted = task.deadline
            }
            properties["Date"] = PageProperty(date = PageProperty.Date(
                start = task.estStarted?.format(NOTION_FMT),
                end = task.deadline?.format(NOTION_FMT),
            ))
        }
        if (task.assignedUserName.isNotBlank()) {
            properties["AssignedTo"] = PageProperty(richText = listOf(RichText(text = Text(task.assignedUserName))))
        }

        properties["ProjectName"] = PageProperty(richText = listOf(RichText(text = Text(task.projectName?:""))))
        properties["Type"] = PageProperty(select = findOptionInSchema("Type", task.type.name))
        properties["last_edited_time"] = PageProperty(richText = listOf(RichText(text = Text(task.lastEditTime?.format(NOTION_FMT)?:""))))
        return properties
    }

    private fun findOptionInSchema(propertyName: String, optionValue: String): DatabaseProperty.Select.Option? {
        val property = schema[propertyName]
        if (property != null) {
            return property.select?.options?.find { it.name.equals(optionValue, ignoreCase = true) }
        }
        return null
    }

    private fun parseDateTime(str: String?): ZonedDateTime? {
        if (str == null) {
            return null
        }
        return ZonedDateTime.parse(str, formatter)
    }

    fun listDatabase(): Databases {
        return client.listDatabases()
    }
}


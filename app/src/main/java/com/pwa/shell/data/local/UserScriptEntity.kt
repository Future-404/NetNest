package com.pwa.shell.data.local

import androidx.room.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

enum class RunAt { DOCUMENT_START, DOCUMENT_END, DOCUMENT_IDLE }
enum class ImportSource { CLIPBOARD, FILE }

@Entity(
    tableName = "user_scripts",
    foreignKeys = [ForeignKey(
        entity = PwaEntity::class,
        parentColumns = ["id"],
        childColumns = ["pwaId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("pwaId"), Index(value = ["pwaId", "name"], unique = true)]
)
data class UserScriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pwaId: Long,
    val name: String,
    val enabled: Boolean = true,
    val matchPatterns: List<String> = listOf("*"),
    val runAt: RunAt = RunAt.DOCUMENT_END,
    val code: String,
    val rawSource: String,
    val importSource: ImportSource,
    val sortOrder: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

class StringListConverter {
    @TypeConverter
    fun fromString(value: String): List<String> {
        return try {
            Json.decodeFromString<List<String>>(value)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromList(list: List<String>): String {
        return Json.encodeToString(list)
    }
}

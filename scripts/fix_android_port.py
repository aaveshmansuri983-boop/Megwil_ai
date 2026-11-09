#!/usr/bin/env python3
from pathlib import Path
import sys

project = Path(sys.argv[1])
src = project / "app/src/main/java/com/megwil/ai"

# Close the coroutine/lock/function scopes in AgentCore.
p = src / "agent/AgentCore.kt"
s = p.read_text()
old = """            } catch (e: Exception) {
                _state.value = AgentState.Error(e.message ?: "Unknown error")
                graphifyRepo.logTask(cleanCommand, "failed: ${e.message}", "", 0)
            }
        }
    }

    suspend fun testConnection(): Result<Long> {"""
new = """            } catch (e: Exception) {
                _state.value = AgentState.Error(e.message ?: "Unknown error")
                graphifyRepo.logTask(cleanCommand, "failed: ${e.message}", "", 0)
            }
        }
        }
    }

    suspend fun testConnection(): Result<Long> {"""
if old not in s:
    raise SystemExit("AgentCore patch anchor not found")
p.write_text(s.replace(old, new, 1))

# Correct OkHttp RequestBody construction.
p = src / "mcp/MCPClient.kt"
s = p.read_text()
if "import okhttp3.MediaType.Companion.toMediaType" not in s:
    s = s.replace("import okhttp3.Request\n", "import okhttp3.Request\nimport okhttp3.MediaType.Companion.toMediaType\nimport okhttp3.RequestBody.Companion.toRequestBody\n")
s = s.replace("""okhttp3.RequestBody.create(
                    "application/json".toByteArray().to okhttp3.MediaType.get("application/json"),
                    requestBody
                )""", """requestBody.toRequestBody("application/json".toMediaType())""")
if "to okhttp3.MediaType" in s:
    raise SystemExit("MCPClient patch incomplete")
p.write_text(s)

# Room DAO must persist the @Entity row, not the non-entity domain model.
p = src / "automation/AutomationDB.kt"
s = p.read_text()
s = s.replace("    val scheduleIntervalMs: Long = 0,", "    val scheduleIntervalMs: Long = 0,\n    val scheduleAtMs: Long = 0,")
s = s.replace("List<AutomationManager.Automation>", "List<AutomationEntity>")
s = s.replace("AutomationManager.Automation?", "AutomationEntity?")
s = s.replace("insert(automation: AutomationManager.Automation)", "insert(automation: AutomationEntity)")
s = s.replace("update(automation: AutomationManager.Automation)", "update(automation: AutomationEntity)")
p.write_text(s)

# Fix the Room DAO query so its command parameter is actually used.
p = src / "graphify/nodes/TaskNodeDao.kt"
s = p.read_text()
s = s.replace('SELECT * FROM task_nodes ORDER BY timestamp DESC LIMIT :limit",\n    suspend fun getTasksByCommand', "SELECT * FROM task_nodes WHERE command LIKE '%' || :command || '%' ORDER BY timestamp DESC LIMIT :limit",\n    suspend fun getTasksByCommand")
if "WHERE command LIKE" not in s:
    raise SystemExit("TaskNodeDao query patch failed")
p.write_text(s)

# Map domain automation schedules to Room columns and back.
p = src / "automation/AutomationManager.kt"
s = p.read_text()
s = s.replace("_automationsFlow.value = dao.getAll()", "_automationsFlow.value = dao.getAll().map { it.toAutomation() }")
s = s.replace("dao.insert(automation)", "dao.insert(automation.toEntity())")
s = s.replace("dao.update(automation)", "dao.update(automation.toEntity())")
s = s.replace("val automation = dao.getById(id) ?: return@withContext\n        val updated = automation.copy(enabled = enabled)\n        dao.update(updated)", "val automation = dao.getById(id)?.toAutomation() ?: return@withContext\n        val updated = automation.copy(enabled = enabled)\n        dao.update(updated.toEntity())")
s = s.replace("val automation = dao.getById(id) ?: return@withContext\n        executeAutomation(automation)", "val automation = dao.getById(id)?.toAutomation() ?: return@withContext\n        executeAutomation(automation)")
s = s.replace("        dao.update(updated)\n    }", "        dao.update(updated.toEntity())\n    }", 1)
if "private fun AutomationManager.Automation.toEntity()" not in s:
    s += """

private fun AutomationManager.Automation.toEntity(): AutomationEntity {
    val type: String
    var hour = 0
    var minute = 0
    var day = 0
    var interval = 0L
    var at = 0L
    when (val value = schedule) {
        is AutomationManager.AutomationSchedule.Daily -> { type = "daily"; hour = value.hour; minute = value.minute }
        is AutomationManager.AutomationSchedule.Weekly -> { type = "weekly"; day = value.dayOfWeek; hour = value.hour; minute = value.minute }
        is AutomationManager.AutomationSchedule.Interval -> { type = "interval"; interval = value.intervalMs }
        is AutomationManager.AutomationSchedule.Once -> { type = "once"; at = value.atMs }
    }
    return AutomationEntity(id, name, command, type, hour, minute, day, interval, at, enabled, lastRun, lastResult, runCount)
}

private fun AutomationEntity.toAutomation(): AutomationManager.Automation {
    val parsedSchedule = when (scheduleType) {
        "daily" -> AutomationManager.AutomationSchedule.Daily(scheduleHour, scheduleMinute)
        "weekly" -> AutomationManager.AutomationSchedule.Weekly(scheduleDayOfWeek, scheduleHour, scheduleMinute)
        "interval" -> AutomationManager.AutomationSchedule.Interval(scheduleIntervalMs)
        "once" -> AutomationManager.AutomationSchedule.Once(scheduleAtMs)
        else -> AutomationManager.AutomationSchedule.Once(scheduleAtMs)
    }
    return AutomationManager.Automation(id, name, command, parsedSchedule, enabled, lastRun, lastResult, runCount)
}
"""
p.write_text(s)

package com.thalia.app.reminders

import com.thalia.planning.Reminder
import com.thalia.planning.ReminderReason
import com.thalia.planning.ReminderStatus
import com.thalia.planning.ReminderTargetType
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderTextTest {
    private val utc = ZoneId.of("UTC")
    private val at = Instant.parse("2026-10-10T15:00:00Z").toEpochMilli()
    private val id = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1e001"

    private fun r(
        status: ReminderStatus = ReminderStatus.SCHEDULED,
        reason: ReminderReason? = null,
        type: ReminderTargetType = ReminderTargetType.NONE,
        zone: String = "UTC",
        posted: Long? = null,
        resolved: Long? = null,
    ) = Reminder(
        id,
        type,
        if (type ==
            ReminderTargetType.NONE
        ) {
            null
        } else {
            "t"
        },
        "Call the dentist",
        at,
        zone,
        status,
        reason,
        posted,
        resolved,
        1,
    )

    @Test
    fun `the time is always labelled around, with the date when it is not today and the zone when it differs`() {
        val morning = Instant.parse("2026-10-10T08:00:00Z").toEpochMilli()
        assertEquals("around 15:00", ReminderText.around(r(), morning, utc))
        assertEquals("around Sat 10 Oct, 15:00", ReminderText.around(r(), Instant.parse("2026-10-09T08:00:00Z").toEpochMilli(), utc))
        assertEquals("around 23:00 (set in UTC)", ReminderText.around(r(), morning, ZoneId.of("Asia/Kuala_Lumpur")))
        assertTrue(ReminderText.around(r(zone = "Asia/Tokyo"), morning, utc).endsWith("(set in Asia/Tokyo)"))
    }

    @Test
    fun `an unanswered reminder says response unknown and never shown, done or missed`() {
        val posted = ReminderText.status(r(ReminderStatus.POSTED, posted = 5))
        assertEquals("Notification posted; response unknown", posted)
        ReminderStatus.entries.forEach { status ->
            val reminder =
                when (status) {
                    ReminderStatus.SCHEDULED -> r()
                    ReminderStatus.POSTED -> r(status, posted = 5)
                    ReminderStatus.ACKNOWLEDGED, ReminderStatus.DISMISSED -> r(status, posted = 5, resolved = 6)
                    ReminderStatus.CANCELLED -> r(status, resolved = 6)
                    ReminderStatus.NOT_POSTED -> r(status, ReminderReason.WINDOW_EXPIRED, resolved = 6)
                }
            val text = ReminderText.status(reminder).lowercase()
            assertFalse(Regex("\\b(shown|completed|done|missed|phone)\\b").containsMatchIn(text), text)
        }
    }

    @Test
    fun `acknowledging says only that the reminder was acknowledged`() {
        assertEquals("You acknowledged this reminder", ReminderText.status(r(ReminderStatus.ACKNOWLEDGED, posted = 5, resolved = 6)))
        assertEquals("You dismissed this reminder", ReminderText.status(r(ReminderStatus.DISMISSED, posted = 5, resolved = 6)))
    }

    @Test
    fun `each observed reason has its own words`() {
        val texts = ReminderReason.entries.map { ReminderText.status(r(ReminderStatus.NOT_POSTED, it, resolved = 6)) }
        assertEquals(texts.size, texts.toSet().size)
        assertTrue(texts.all { it.startsWith("Not posted") })
    }

    @Test
    fun `a gone target is said plainly and a checkpoint is just a prompt to look`() {
        assertEquals("Inbox: Call the dentist", ReminderText.about(r(type = ReminderTargetType.INBOX_ITEM), targetGone = false))
        assertTrue("no longer" in ReminderText.about(r(type = ReminderTargetType.ACTION), targetGone = true))
        assertEquals("Checkpoint. Look at today's plan if you want to.", ReminderText.about(r(), targetGone = false))
    }

    @Test
    fun `every refusal says nothing was scheduled`() {
        ReminderRefusal.entries.forEach { assertTrue("Nothing was scheduled" in ReminderText.refusal(it), it.name) }
    }
}

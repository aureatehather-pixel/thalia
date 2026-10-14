package com.thalia.app.voicecheckins.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.voicecheckins.schedule.DUE
import com.thalia.app.voicecheckins.schedule.KUALA_LUMPUR
import com.thalia.app.voicecheckins.schedule.OccurrenceStore
import com.thalia.app.voicecheckins.schedule.OccurrenceStoreContractTest
import com.thalia.app.voicecheckins.schedule.occurrence
import com.thalia.voicecheckins.domain.CheckInPlan
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The real store, on real SQLite through Room, run on the computer by Robolectric.
 *
 * It inherits every check the in-memory double passes, so the two cannot drift apart, and adds what
 * only a database has: the data survives closing and reopening it, which is what a reboot does.
 *
 * Robolectric is pinned to API 36, the target SDK, and given a bare `Application` so the app's own
 * startup does not run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomOccurrenceStoreTest : OccurrenceStoreContractTest() {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var database: CheckInDatabase? = null

    override fun createStore(): OccurrenceStore {
        val db = Room.inMemoryDatabaseBuilder(context, CheckInDatabase::class.java).build()
        database = db
        return RoomOccurrenceStore(db) { KUALA_LUMPUR }
    }

    override fun closeStore() {
        database?.close()
    }

    private fun openFile(): CheckInDatabase = Room.databaseBuilder(context, CheckInDatabase::class.java, "reopen-test.db").build()

    @Test
    fun `everything stored survives closing and reopening the database`() =
        runTest {
            val first = openFile()
            val before = RoomOccurrenceStore(first) { KUALA_LUMPUR }
            before.insert(occurrence("kept"))
            before.claimForRinging("kept", DUE.plusMillis(150))
            before.transition("kept", CheckInStatus.RINGING, CheckInStatus.MISSED, DUE.plusSeconds(45), OccurrenceReason.RING_TIMEOUT)
            before.insert(occurrence("still-scheduled", at = DUE.plusSeconds(3600)))
            val plan = CheckInPlan.default(KUALA_LUMPUR).withTime(CheckInType.OPENING, LocalTime.of(9, 15))
            before.savePlan(plan)
            first.close()

            val reopened = openFile()
            try {
                val after = RoomOccurrenceStore(reopened) { KUALA_LUMPUR }
                val kept = assertNotNull(after.get("kept"))
                assertEquals(CheckInStatus.MISSED, kept.status)
                assertEquals(OccurrenceReason.RING_TIMEOUT, kept.reason)
                assertEquals(DUE.plusMillis(150), kept.firedAt)
                assertEquals(DUE.plusSeconds(45), kept.resolvedAt)
                assertEquals(listOf("still-scheduled"), after.open().map { it.id })
                assertEquals(plan, after.plan())
            } finally {
                reopened.close()
                context.deleteDatabase("reopen-test.db")
            }
        }

    @Test
    fun `an occurrence keeps the zone it was stamped in`() =
        runTest {
            val auckland = ZoneId.of("Pacific/Auckland")
            val store = createStore()
            store.insert(occurrence("a").copy(zone = auckland))

            assertEquals(auckland, store.get("a")?.zone)
        }

    @Test
    fun `the database refuses a stored row that contradicts its status`() =
        runTest {
            // A row that says ringing but was never fired cannot be read back as an occurrence.
            val db = Room.inMemoryDatabaseBuilder(context, CheckInDatabase::class.java).build()
            try {
                db.occurrences().insert(
                    OccurrenceEntity("bad", "OPENING", DUE.toEpochMilli(), "Asia/Kuala_Lumpur", "RINGING", null, null, null, "bad", 1),
                )
                val refused = runCatching { RoomOccurrenceStore(db) { KUALA_LUMPUR }.get("bad") }
                assertEquals(true, refused.exceptionOrNull() is IllegalArgumentException)
            } finally {
                db.close()
            }
        }
}

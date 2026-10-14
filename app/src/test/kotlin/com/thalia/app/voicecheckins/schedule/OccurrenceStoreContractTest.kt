package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInPlan
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import com.thalia.voicecheckins.domain.QuietPeriod
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import com.thalia.voicecheckins.domain.occurrenceAt
import com.thalia.voicecheckins.domain.occurrenceOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What every [OccurrenceStore] must do, written once and inherited by each implementation.
 *
 * The in-memory double and the Room store both run exactly these checks. That is what makes a test
 * that uses the double a statement about the database, and what makes the claim and the guarded
 * moves trustworthy: one winner, however many callers, on the real store too.
 */
abstract class OccurrenceStoreContractTest {
    protected abstract fun createStore(): OccurrenceStore

    protected open fun closeStore() = Unit

    protected lateinit var store: OccurrenceStore

    private val fired = DUE.plusMillis(150)
    private val done = DUE.plusSeconds(40)

    @Before
    fun openStore() {
        store = createStore()
    }

    @After
    fun shutStore() = closeStore()

    @Test
    fun `an inserted occurrence reads back exactly`() =
        runTest {
            val o = occurrence()
            assertTrue(store.insert(o))
            assertEquals(o, store.get(o.id))
        }

    @Test
    fun `an unknown occurrence reads back as nothing`() =
        runTest {
            assertNull(store.get("nope"))
        }

    @Test
    fun `inserting an id that exists changes nothing and says so`() =
        runTest {
            val original = occurrence()
            assertTrue(store.insert(original))
            store.claimForRinging(original.id, fired)

            assertFalse(store.insert(original.copy(scheduledAt = DUE.plusSeconds(999))))

            // Not replaced, and above all not put back to scheduled after it has rung.
            val stored = assertNotNull(store.get(original.id))
            assertEquals(CheckInStatus.RINGING, stored.status)
            assertEquals(DUE, stored.scheduledAt)
        }

    @Test
    fun `open lists only occurrences that have not ended, earliest first`() =
        runTest {
            store.insert(occurrence("b", at = DUE.plusSeconds(3600)))
            store.insert(occurrence("c", at = DUE.plusSeconds(60)))
            store.insert(occurrence("a", at = DUE))
            store.transition("c", CheckInStatus.SCHEDULED, CheckInStatus.MISSED, done, OccurrenceReason.ALARM_MISSING)

            assertEquals(listOf("a", "b"), store.open().map { it.id })
        }

    @Test
    fun `a ringing or answered occurrence is still open`() =
        runTest {
            store.insert(occurrence("r"))
            store.claimForRinging("r", fired)
            assertEquals(listOf(CheckInStatus.RINGING), store.open().map { it.status })

            store.transition("r", CheckInStatus.RINGING, CheckInStatus.ANSWERED, fired.plusSeconds(2))
            assertEquals(listOf(CheckInStatus.ANSWERED), store.open().map { it.status })
        }

    @Test
    fun `the claim moves a scheduled occurrence to ringing and stamps the fired time`() =
        runTest {
            store.insert(occurrence())

            assertTrue(store.claimForRinging("2026-09-20-morning", fired).claimed)

            val claimed = assertNotNull(store.get("2026-09-20-morning"))
            assertEquals(CheckInStatus.RINGING, claimed.status)
            assertEquals(fired, claimed.firedAt)
            assertNull(claimed.resolvedAt)
        }

    @Test
    fun `the claim succeeds once and only once`() =
        runTest {
            store.insert(occurrence())

            assertTrue(store.claimForRinging("2026-09-20-morning", fired).claimed)
            assertFalse(store.claimForRinging("2026-09-20-morning", fired.plusSeconds(1)).claimed)

            // The second attempt must not have disturbed the first one's fired time.
            assertEquals(fired, store.get("2026-09-20-morning")?.firedAt)
        }

    @Test
    fun `the claim fails for an occurrence that does not exist`() =
        runTest {
            assertEquals(ClaimResult.NOT_CLAIMED, store.claimForRinging("nope", fired))
        }

    @Test
    fun `a generic transition into ringing is refused before anything is touched`() =
        runTest {
            store.insert(occurrence())

            // claimForRinging is the only path into RINGING: it alone stamps the fired time
            // atomically with the move, so the generic move must refuse this destination outright,
            // whatever it claims to move from.
            for (from in CheckInStatus.entries) {
                val refused =
                    assertFailsWith<IllegalArgumentException> {
                        store.transition("2026-09-20-morning", from, CheckInStatus.RINGING, fired)
                    }
                assertEquals("RINGING can only be reached through claimForRinging", refused.message)
            }

            val untouched = assertNotNull(store.get("2026-09-20-morning"))
            assertEquals(CheckInStatus.SCHEDULED, untouched.status)
            assertNull(untouched.firedAt)

            // The refusals cost nothing: the claim still wins, and stamps the fired time.
            assertTrue(store.claimForRinging("2026-09-20-morning", fired).claimed)
            assertEquals(fired, store.get("2026-09-20-morning")?.firedAt)
        }

    @Test
    fun `the claim fails for an occurrence that has already ended`() =
        runTest {
            store.insert(occurrence())
            store.transition("2026-09-20-morning", CheckInStatus.SCHEDULED, CheckInStatus.MISSED, done, OccurrenceReason.ALARM_MISSING)

            assertEquals(ClaimResult.NOT_CLAIMED, store.claimForRinging("2026-09-20-morning", fired))
            assertEquals(CheckInStatus.MISSED, store.get("2026-09-20-morning")?.status)
        }

    @Test
    fun `many claims at once have exactly one winner`() =
        runTest {
            store.insert(occurrence())

            val results =
                withContext(Dispatchers.Default) {
                    (1..64)
                        .map { attempt ->
                            async { store.claimForRinging("2026-09-20-morning", fired.plusMillis(attempt.toLong())).claimed }
                        }.awaitAll()
                }

            assertEquals(1, results.count { it }, "exactly one claim may win")
            assertEquals(CheckInStatus.RINGING, store.get("2026-09-20-morning")?.status)
        }

    @Test
    fun `a claim and a missed mark racing for one occurrence have exactly one winner`() =
        runTest {
            repeat(20) { round ->
                val id = "race-$round"
                store.insert(occurrence(id))

                val results =
                    withContext(Dispatchers.Default) {
                        listOf(
                            async { store.claimForRinging(id, fired).claimed },
                            async {
                                store.transition(
                                    id,
                                    CheckInStatus.SCHEDULED,
                                    CheckInStatus.MISSED,
                                    done,
                                    OccurrenceReason.ALARM_MISSING,
                                )
                            },
                        ).awaitAll()
                    }

                assertEquals(1, results.count { it }, "round $round: exactly one may win")
                val status = store.get(id)?.status
                assertTrue(status == CheckInStatus.RINGING || status == CheckInStatus.MISSED, "round $round ended $status")
                // A call left ringing would hold the phone, and the one-call rule would suppress the next round's claim.
                if (status == CheckInStatus.RINGING) store.transition(id, CheckInStatus.RINGING, CheckInStatus.DECLINED, done)
            }
        }

    @Test
    fun `a move happens only from the status it names`() =
        runTest {
            store.insert(occurrence())
            store.claimForRinging("2026-09-20-morning", fired)

            // It is ringing, not scheduled, so a move that expects scheduled is refused and changes nothing.
            val refused =
                store.transition(
                    "2026-09-20-morning",
                    CheckInStatus.SCHEDULED,
                    CheckInStatus.CANCELLED,
                    done,
                    OccurrenceReason.USER_CANCELLED,
                )
            assertFalse(refused)
            assertEquals(CheckInStatus.RINGING, store.get("2026-09-20-morning")?.status)

            assertTrue(store.transition("2026-09-20-morning", CheckInStatus.RINGING, CheckInStatus.ANSWERED, fired.plusSeconds(2)))
            assertEquals(CheckInStatus.ANSWERED, store.get("2026-09-20-morning")?.status)
        }

    @Test
    fun `a move on an unknown occurrence changes nothing`() =
        runTest {
            assertFalse(store.transition("nope", CheckInStatus.SCHEDULED, CheckInStatus.MISSED, done, OccurrenceReason.ALARM_MISSING))
        }

    @Test
    fun `a terminal move stamps the resolved time and stores the reason`() =
        runTest {
            store.insert(occurrence())
            store.claimForRinging("2026-09-20-morning", fired)

            assertTrue(
                store.transition("2026-09-20-morning", CheckInStatus.RINGING, CheckInStatus.MISSED, done, OccurrenceReason.RING_TIMEOUT),
            )

            val ended = assertNotNull(store.get("2026-09-20-morning"))
            assertEquals(CheckInStatus.MISSED, ended.status)
            assertEquals(OccurrenceReason.RING_TIMEOUT, ended.reason)
            assertEquals(fired, ended.firedAt)
            assertEquals(done, ended.resolvedAt)
        }

    @Test
    fun `an open move leaves the resolved time empty`() =
        runTest {
            store.insert(occurrence())
            store.claimForRinging("2026-09-20-morning", fired)
            store.transition("2026-09-20-morning", CheckInStatus.RINGING, CheckInStatus.ANSWERED, fired.plusSeconds(2))

            assertNull(store.get("2026-09-20-morning")?.resolvedAt)
        }

    @Test
    fun `an occurrence that never rang can be missed and has no fired time`() =
        runTest {
            store.insert(occurrence())

            store.transition("2026-09-20-morning", CheckInStatus.SCHEDULED, CheckInStatus.MISSED, done, OccurrenceReason.ALARM_MISSING)

            val ended = assertNotNull(store.get("2026-09-20-morning"))
            assertNull(ended.firedAt)
            assertEquals(done, ended.resolvedAt)
        }

    @Test
    fun `an ended occurrence can never be moved again`() =
        runTest {
            store.insert(occurrence())
            store.transition("2026-09-20-morning", CheckInStatus.SCHEDULED, CheckInStatus.MISSED, done, OccurrenceReason.ALARM_MISSING)

            assertFailsWith<IllegalArgumentException> {
                store.transition("2026-09-20-morning", CheckInStatus.MISSED, CheckInStatus.MISSED, done, OccurrenceReason.OVERDUE)
            }
            assertFailsWith<IllegalArgumentException> {
                store.transition("2026-09-20-morning", CheckInStatus.MISSED, CheckInStatus.CANCELLED, done, OccurrenceReason.USER_CANCELLED)
            }
            assertEquals(OccurrenceReason.ALARM_MISSING, store.get("2026-09-20-morning")?.reason)
        }

    @Test
    fun `an illegal move is refused before anything is touched`() =
        runTest {
            store.insert(occurrence())

            assertFailsWith<IllegalArgumentException> {
                store.transition("2026-09-20-morning", CheckInStatus.SCHEDULED, CheckInStatus.ANSWERED, done)
            }
            assertEquals(CheckInStatus.SCHEDULED, store.get("2026-09-20-morning")?.status)
        }

    @Test
    fun `a reason is required exactly where the status needs one`() =
        runTest {
            store.insert(occurrence())

            assertFailsWith<IllegalArgumentException> {
                store.transition("2026-09-20-morning", CheckInStatus.SCHEDULED, CheckInStatus.MISSED, done)
            }
            assertFailsWith<IllegalArgumentException> {
                store.transition(
                    "2026-09-20-morning",
                    CheckInStatus.SCHEDULED,
                    CheckInStatus.CANCELLED,
                    done,
                    OccurrenceReason.RING_TIMEOUT,
                )
            }
            assertEquals(CheckInStatus.SCHEDULED, store.get("2026-09-20-morning")?.status)

            // A status that must not carry a reason. Ringing is the only such status reachable from
            // scheduled, and it is refused outright, so this is checked one step later instead.
            store.claimForRinging("2026-09-20-morning", fired)
            val refused =
                assertFailsWith<IllegalArgumentException> {
                    store.transition(
                        "2026-09-20-morning",
                        CheckInStatus.RINGING,
                        CheckInStatus.ANSWERED,
                        done,
                        OccurrenceReason.ALARM_MISSING,
                    )
                }
            assertEquals("ANSWERED must not carry a reason, got ALARM_MISSING", refused.message)
            assertEquals(CheckInStatus.RINGING, store.get("2026-09-20-morning")?.status)
        }

    @Test
    fun `recent occurrences are listed latest first and limited`() =
        runTest {
            store.insert(occurrence("a", at = DUE))
            store.insert(occurrence("b", at = DUE.plusSeconds(10)))
            store.insert(occurrence("c", at = DUE.plusSeconds(20)))

            assertEquals(listOf("c", "b"), store.observeRecent(2).first().map { it.id })
        }

    @Test
    fun `recent occurrences follow a change`() =
        runTest {
            store.insert(occurrence("a"))
            assertEquals(
                CheckInStatus.SCHEDULED,
                store
                    .observeRecent(5)
                    .first()
                    .single()
                    .status,
            )

            store.claimForRinging("a", fired)

            assertEquals(
                CheckInStatus.RINGING,
                store
                    .observeRecent(5)
                    .first()
                    .single()
                    .status,
            )
        }

    @Test
    fun `the first plan is the default, in the zone it was created with`() =
        runTest {
            val plan = store.plan()

            assertEquals(LocalTime.of(10, 0), plan.opening.localTime)
            assertEquals(LocalTime.of(15, 0), plan.closing.localTime)
            assertTrue(plan.opening.enabled && plan.closing.enabled)
            assertEquals(KUALA_LUMPUR, plan.zone)
        }

    @Test
    fun `a saved plan is read back and never overwritten by the default`() =
        runTest {
            val changed =
                CheckInPlan
                    .default(KUALA_LUMPUR)
                    .withTime(CheckInType.OPENING, LocalTime.of(9, 30))
                    .withEnabled(CheckInType.CLOSING, false)

            store.plan()
            store.savePlan(changed)

            assertEquals(changed, store.plan())
            // Asking again must not put the default back.
            assertEquals(changed, store.plan())
        }

    // Slots and revisions: one live occurrence per day and type, with the history kept.

    private val opening = CheckInPlan.default(KUALA_LUMPUR).opening
    private val day = LocalDate.of(2026, 9, 21)

    private fun revision(
        n: Int,
        time: LocalTime = LocalTime.of(10, n),
    ) = opening.occurrenceAt(day, time, n)

    private fun liveIn(slot: List<ScheduledOccurrence>) = slot.filter { it.isEligible }

    @Test
    fun `a slot reads back its occurrences oldest revision first, and an unused slot is empty`() =
        runTest {
            assertEquals(emptyList(), store.slot("2026-09-21-opening"))

            store.insert(revision(2))
            store.insert(revision(1))
            store.insert(opening.copy(type = CheckInType.CLOSING).occurrenceOn(day))

            assertEquals(listOf(1, 2), store.slot("2026-09-21-opening").map { it.revision })
            assertEquals(listOf("2026-09-21-closing"), store.slot("2026-09-21-closing").map { it.id })
        }

    @Test
    fun `superseding retires the old occurrence and records the new one together`() =
        runTest {
            store.insert(revision(1))

            assertTrue(store.supersede("2026-09-21-opening", revision(2), fired))

            val history = store.slot("2026-09-21-opening")
            assertEquals(listOf(CheckInStatus.CANCELLED, CheckInStatus.SCHEDULED), history.map { it.status })
            assertEquals(OccurrenceReason.SUPERSEDED, history[0].reason)
            assertEquals(fired, history[0].resolvedAt)
            assertEquals(listOf("2026-09-21-opening-r2"), liveIn(history).map { it.id })
        }

    @Test
    fun `superseding an occurrence that is ringing changes nothing`() =
        runTest {
            store.insert(revision(1))
            store.claimForRinging("2026-09-21-opening", fired)

            assertFalse(store.supersede("2026-09-21-opening", revision(2), fired.plusSeconds(1)))

            assertEquals(listOf(CheckInStatus.RINGING), store.slot("2026-09-21-opening").map { it.status })
        }

    @Test
    fun `superseding an occurrence that already ended or never existed changes nothing`() =
        runTest {
            store.insert(revision(1))
            store.transition("2026-09-21-opening", CheckInStatus.SCHEDULED, CheckInStatus.CANCELLED, fired, OccurrenceReason.USER_CANCELLED)

            assertFalse(store.supersede("2026-09-21-opening", revision(2), fired))
            assertFalse(store.supersede("nope", revision(2), fired))

            assertEquals(listOf(1), store.slot("2026-09-21-opening").map { it.revision })
        }

    @Test
    fun `superseding into a revision that already exists leaves the old occurrence scheduled`() =
        runTest {
            store.insert(revision(1))
            store.insert(revision(2))

            assertFalse(store.supersede("2026-09-21-opening", revision(2, LocalTime.of(12, 0)), fired))

            val history = store.slot("2026-09-21-opening")
            assertEquals(CheckInStatus.SCHEDULED, history[0].status, "the old one must not be lost when the new one cannot be recorded")
            assertEquals(LocalTime.of(10, 2), history[1].localDateTime.toLocalTime(), "the existing revision is not overwritten")
        }

    @Test
    fun `superseding refuses a replacement that is not the next revision of the same slot`() =
        runTest {
            store.insert(revision(1))

            assertFailsWith<IllegalArgumentException> { store.supersede("2026-09-21-opening", revision(3), fired) }
            assertFailsWith<IllegalArgumentException> {
                store.supersede("2026-09-21-opening", opening.occurrenceOn(day.plusDays(1)), fired)
            }
            assertEquals(CheckInStatus.SCHEDULED, store.get("2026-09-21-opening")?.status)
        }

    @Test
    fun `many supersedes at once have exactly one winner and leave one live occurrence`() =
        runTest {
            store.insert(revision(1))

            val results =
                withContext(Dispatchers.Default) {
                    (1..32)
                        .map { attempt ->
                            async { store.supersede("2026-09-21-opening", revision(2, LocalTime.of(11, attempt % 60)), fired) }
                        }.awaitAll()
                }

            assertEquals(1, results.count { it })
            val history = store.slot("2026-09-21-opening")
            assertEquals(2, history.size)
            assertEquals(1, liveIn(history).size)
        }

    @Test
    fun `a claim and a supersede racing for one occurrence have exactly one winner`() =
        runTest {
            repeat(20) { round ->
                val slotDay = day.plusDays(round + 1L)
                val first = opening.occurrenceAt(slotDay, LocalTime.of(10, 0), 1)
                val second = opening.occurrenceAt(slotDay, LocalTime.of(11, 0), 2)
                store.insert(first)

                val (claimed, superseded) =
                    withContext(Dispatchers.Default) {
                        val c = async { store.claimForRinging(first.id, fired).claimed }
                        val s = async { store.supersede(first.id, second, fired) }
                        c.await() to s.await()
                    }

                assertTrue(claimed != superseded, "round $round: exactly one of claim and supersede may win")
                val history = store.slot(first.slot)
                assertEquals(1, liveIn(history).size, "round $round: exactly one live occurrence")
                if (claimed) {
                    assertEquals(listOf(CheckInStatus.RINGING), history.map { it.status }, "round $round")
                    // Free the phone for the next round, as the call ending would.
                    store.transition(first.id, CheckInStatus.RINGING, CheckInStatus.DECLINED, done)
                } else {
                    assertEquals(listOf(CheckInStatus.CANCELLED, CheckInStatus.SCHEDULED), history.map { it.status }, "round $round")
                }
            }
        }

    @Test
    fun `a superseded occurrence can never be claimed`() =
        runTest {
            store.insert(revision(1))
            store.supersede("2026-09-21-opening", revision(2), fired)

            assertEquals(ClaimResult.NOT_CLAIMED, store.claimForRinging("2026-09-21-opening", fired.plusSeconds(1)))

            assertEquals(CheckInStatus.CANCELLED, store.get("2026-09-21-opening")?.status)
            assertTrue(store.claimForRinging("2026-09-21-opening-r2", fired.plusSeconds(1)).claimed)
        }

    // One call at a time: the claim itself refuses a second call and suppresses it.

    @Test
    fun `a claim while another call is ringing suppresses it as a call in progress, in the same step`() =
        runTest {
            store.insert(occurrence("first"))
            store.insert(occurrence("second", at = DUE.plusSeconds(20)))
            assertEquals(ClaimResult.CLAIMED, store.claimForRinging("first", fired))

            assertEquals(ClaimResult.SUPPRESSED, store.claimForRinging("second", fired.plusSeconds(20)))

            val second = assertNotNull(store.get("second"))
            assertEquals(CheckInStatus.SUPPRESSED, second.status)
            assertEquals(OccurrenceReason.CALL_IN_PROGRESS, second.reason)
            assertNull(second.firedAt, "a suppressed call never rang")
            assertEquals(fired.plusSeconds(20), second.resolvedAt)
            assertEquals(CheckInStatus.RINGING, store.get("first")?.status, "the call on the phone is untouched")
        }

    @Test
    fun `an answered call also holds the phone, and one that has ended does not`() =
        runTest {
            store.insert(occurrence("talking"))
            store.insert(occurrence("during", at = DUE.plusSeconds(30)))
            store.insert(occurrence("after", at = DUE.plusSeconds(90)))
            store.claimForRinging("talking", fired)
            store.transition("talking", CheckInStatus.RINGING, CheckInStatus.ANSWERED, fired.plusSeconds(3))

            assertEquals(ClaimResult.SUPPRESSED, store.claimForRinging("during", fired.plusSeconds(30)))

            store.transition("talking", CheckInStatus.ANSWERED, CheckInStatus.COMPLETED, fired.plusSeconds(60))
            assertEquals(ClaimResult.CLAIMED, store.claimForRinging("after", fired.plusSeconds(90)))
        }

    @Test
    fun `a suppressed occurrence can never be claimed afterwards`() =
        runTest {
            store.insert(occurrence("first"))
            store.insert(occurrence("second"))
            store.claimForRinging("first", fired)
            store.claimForRinging("second", fired)
            store.transition("first", CheckInStatus.RINGING, CheckInStatus.DECLINED, done)

            assertEquals(ClaimResult.NOT_CLAIMED, store.claimForRinging("second", done.plusSeconds(1)))
            assertEquals(CheckInStatus.SUPPRESSED, store.get("second")?.status)
        }

    @Test
    fun `a claim for an occurrence that is not scheduled changes nothing even while another call is ringing`() =
        runTest {
            store.insert(occurrence("ringing"))
            store.insert(occurrence("ended"))
            store.claimForRinging("ringing", fired)
            store.transition("ended", CheckInStatus.SCHEDULED, CheckInStatus.CANCELLED, done, OccurrenceReason.USER_CANCELLED)

            assertEquals(ClaimResult.NOT_CLAIMED, store.claimForRinging("ended", fired))
            assertEquals(ClaimResult.NOT_CLAIMED, store.claimForRinging("nope", fired))
            assertEquals(CheckInStatus.CANCELLED, store.get("ended")?.status)
        }

    @Test
    fun `two alarms delivered at once have exactly one winner, and the loser is suppressed`() =
        runTest {
            repeat(40) { round ->
                val a = "race-$round-a"
                val b = "race-$round-b"
                store.insert(occurrence(a))
                store.insert(occurrence(b))

                val results =
                    withContext(Dispatchers.Default) {
                        listOf(async { store.claimForRinging(a, fired) }, async { store.claimForRinging(b, fired) }).awaitAll()
                    }

                assertEquals(1, results.count { it == ClaimResult.CLAIMED }, "round $round: exactly one rings, got $results")
                assertEquals(1, results.count { it == ClaimResult.SUPPRESSED }, "round $round: the other is suppressed, got $results")
                val statuses = listOf(a, b).map { store.get(it)?.status }.toSet()
                assertEquals(setOf(CheckInStatus.RINGING, CheckInStatus.SUPPRESSED), statuses, "round $round")

                // End the winner so the next round starts with the phone free.
                val winner = if (store.get(a)?.status == CheckInStatus.RINGING) a else b
                store.transition(winner, CheckInStatus.RINGING, CheckInStatus.DECLINED, done)
            }
        }

    @Test
    fun `many alarms delivered at once leave exactly one call on the phone`() =
        runTest {
            val ids = (1..24).map { "burst-$it" }
            ids.forEach { store.insert(occurrence(it)) }

            val results =
                withContext(Dispatchers.Default) {
                    ids.map { id -> async { store.claimForRinging(id, fired) } }.awaitAll()
                }

            assertEquals(1, results.count { it == ClaimResult.CLAIMED })
            assertEquals(ids.size - 1, results.count { it == ClaimResult.SUPPRESSED })
            assertEquals(1, store.open().size, "only the winner is still open")
            assertEquals(CheckInStatus.RINGING, store.open().single().status)
        }

    // The quiet period

    @Test
    fun `the quiet period starts as the default, switched off`() =
        runTest {
            assertEquals(QuietPeriod.DEFAULT, store.quietPeriod())
            assertFalse(store.quietPeriod().enabled)
        }

    @Test
    fun `a saved quiet period is read back exactly, including one that spans midnight`() =
        runTest {
            val night = QuietPeriod(LocalTime.of(22, 30), LocalTime.of(6, 45), enabled = true)
            store.saveQuietPeriod(night)
            assertEquals(night, store.quietPeriod())

            store.saveQuietPeriod(night.copy(enabled = false))
            assertEquals(night.copy(enabled = false), store.quietPeriod(), "switching it off keeps its times")
        }
}

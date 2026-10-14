package com.thalia.app.planning.data

import android.app.Application
import android.content.Context
import android.content.res.XmlResourceParser
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.BuildConfig
import com.thalia.app.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * How production storage is wired, checked without opening it: SQLCipher's native library does not
 * run on the JVM. Whether SQLCipher really keeps a corrupt or wrongly keyed file, and really encrypts,
 * is checked on a device in ADR 0009 slice 1B.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlanningWiringTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `production opens through SQLCipher's own helper with a corruption handler that keeps the file`() {
        val outer = assertIs<NonDestructiveOpenHelperFactory>(PlanningDatabaseOpener.encryptedHelperFactory(ByteArray(32)))
        val factory = assertIs<KeepingSqlCipherOpenHelperFactory>(outer.delegate)

        val helper =
            factory.create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("wiring-only.db")
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(1) {
                            override fun onCreate(db: SupportSQLiteDatabase) = Unit

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )

        assertIs<net.zetetic.database.sqlcipher.SQLiteOpenHelper>(helper)
        assertSame(KeepingSqlCipherOpenHelperFactory.KeepFile, field(helper, "mErrorHandler"))
        assertEquals(0, field(helper, "mMinimumSupportedVersion"), "a positive minimum would delete older databases")
    }

    @Test
    fun `debug and release keep the real app's planning names and Keystore alias`() {
        if (BuildConfig.BUILD_TYPE == "geminiCheck") {
            // The isolated live check build runs these shared tests too (its own host tests are on); it must not share names.
            assertEquals("geminicheck-planning", BuildConfig.PLANNING_STORAGE_NAME)
            assertEquals("thalia-geminicheck-planning-db-v1", BuildConfig.PLANNING_KEY_ALIAS)
            return
        }
        if (BuildConfig.BUILD_TYPE == "chatgptCheck") {
            // The isolated ChatGPT-plan proof runs these shared tests too; it must not share names either.
            assertEquals("chatgptcheck-planning", BuildConfig.PLANNING_STORAGE_NAME)
            assertEquals("thalia-chatgptcheck-planning-db-v1", BuildConfig.PLANNING_KEY_ALIAS)
            return
        }
        assertEquals(PlanningFiles.PRODUCTION_NAME, BuildConfig.PLANNING_STORAGE_NAME)
        assertEquals(KeystorePassphraseSealer.PRODUCTION_ALIAS, BuildConfig.PLANNING_KEY_ALIAS)
    }

    @Test
    fun `the recovery check's screen and checks are in neither the debug nor the release build`() {
        listOf("com.thalia.app.recoverycheck.RecoveryCheckActivity", "com.thalia.app.recoverycheck.RecoveryChecks").forEach { name ->
            assertFailsWith<ClassNotFoundException>(name) { Class.forName(name) }
        }
    }

    @Test
    fun `the backup rules name the files where production actually puts them`() {
        val dataDir = context.dataDir
        val files = PlanningFiles.of(context, PlanningFiles.PRODUCTION_NAME)

        assertEquals(dataDir.canonicalFile, files.root.canonicalFile)
        assertEquals(File(dataDir, "databases/planning.db").canonicalFile, files.database.canonicalFile)
        assertEquals(File(dataDir, "databases/planning-restore.db").canonicalFile, files.restoreDatabase.canonicalFile)
        assertEquals(File(dataDir, "no_backup/planning-key.sealed").canonicalFile, files.key.canonicalFile)
        assertEquals(File(dataDir, "no_backup/planning-restore-key.sealed").canonicalFile, files.restoreKey.canonicalFile)
        assertEquals(File(dataDir, "no_backup/planning-replace.journal").canonicalFile, files.journal.canonicalFile)
        assertEquals(File(dataDir, "no_backup/planning-set-aside").canonicalFile, files.setAsideRoot.canonicalFile)
    }

    @Test
    fun `planning files are excluded from cloud backup and from device transfer`() {
        val excluded = mutableMapOf<String, MutableSet<String>>()
        var section: String? = null
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        while (parser.next() != XmlResourceParser.END_DOCUMENT) {
            if (parser.eventType != XmlResourceParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "exclude" ->
                    excluded.getOrPut(requireNotNull(section)) { mutableSetOf() } +=
                        "${parser.getAttributeValue(null, "domain")}:${parser.getAttributeValue(null, "path")}"
            }
        }

        // The database domain is the app's databases directory; the root domain is its data directory.
        // Every file production can create, including the .staging names of its atomic writes.
        val files = PlanningFiles.of(context, PlanningFiles.PRODUCTION_NAME)
        val databases =
            (listOf(files.database) + files.databaseSidecars + listOf(files.restoreDatabase) + files.restoreSidecars).map {
                "database:${it.name}"
            }
        val keys =
            listOf(files.key, files.newKey, files.restoreKey, files.journal).flatMap { listOf(it.name, "${it.name}.staging") } +
                files.setAsideRoot.name
        val planningFiles = (databases + keys.map { "root:no_backup/$it" }).toSet()
        assertEquals(17, planningFiles.size)
        // The AI folder (keys, sealed sign-in, host id, consent, ledger) is excluded as a whole directory, by the name production uses.
        val aiFolder = "file:${com.thalia.app.ai.AiServices.DIRECTORY_NAME}"
        assertEquals("file:ai", aiFolder)
        assertEquals<Set<String>?>(planningFiles + aiFolder, excluded["cloud-backup"])
        assertEquals<Set<String>?>(planningFiles + aiFolder, excluded["device-transfer"])
    }

    private fun field(
        helper: Any,
        name: String,
    ): Any? =
        net.zetetic.database.sqlcipher.SQLiteOpenHelper::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(helper)
}

package com.hawksnest.util

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Guards the backup-exclusion invariant, which is enforced **by file path** and therefore rots
 * silently.
 *
 * `backup_rules.xml` / `data_extraction_rules.xml` exclude one specific DataStore file by name.
 * That is correct today, because the file it names is the one holding the HA long-lived token and
 * the RTSP camera password. But the rule is a path, not a policy: add a second DataStore for
 * something sensitive and it is cloud-backed-up and device-transferred by default, with nothing
 * failing and nobody noticing until a house credential is sitting in someone's Google Drive.
 *
 * So this test does not assert "every DataStore is excluded" — two of them are deliberately backed
 * up. It asserts that **every DataStore is classified**. A new one fails the build until someone
 * decides which list it belongs in, which is the decision that was being skipped.
 */
class BackupExclusionTest {

    /** DataStores holding credentials or anything else that must not leave the device. */
    private val mustBeExcluded = setOf(
        // HA base URL + Keystore-wrapped long-lived token, plus the RTSP camera user/password.
        "hawksnest_prefs",
    )

    /**
     * DataStores that are fine to back up, listed explicitly so the choice is visible.
     * Adding a name here should be a deliberate act, not a way to make a red test green.
     */
    private val mayBeBackedUp = setOf(
        // Whether push is switched on. Not a credential; restoring it is a convenience.
        "hawksnest_push",
        // Per-entity hide/rename choices. Losing these on a new phone would be an annoyance,
        // and they reveal nothing an attacker could use.
        "hawksnest_device_prefs",
    )

    private val moduleRoot: File by lazy {
        // Test working directory is the Gradle module dir on some setups and the repo root on
        // others; resolve whichever actually contains the sources.
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { if (File(it, "src/main/java").isDirectory) it else File(it, "android/app") }
            .firstOrNull { File(it, "src/main/java").isDirectory }
            ?: error("Could not locate the app module from ${File("").absolutePath}")
    }

    private fun declaredDataStores(): Map<String, String> {
        val re = Regex("""preferencesDataStore\(\s*name\s*=\s*"([^"]+)"""")
        return File(moduleRoot, "src/main/java").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { f -> re.findAll(f.readText()).map { it.groupValues[1] to f.name } }
            .toMap()
    }

    private fun rules(name: String) = File(moduleRoot, "src/main/res/xml/$name").readText()

    @Test
    fun `every DataStore is either excluded from backup or explicitly allowed`() {
        val found = declaredDataStores()
        assertTrue(
            "Found no preferencesDataStore declarations — the scan is broken, not the app.",
            found.isNotEmpty(),
        )
        val classified = mustBeExcluded + mayBeBackedUp
        val unclassified = found.keys - classified
        if (unclassified.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Unclassified DataStore(s): " +
                        unclassified.joinToString { "$it (${found[it]})" })
                    appendLine()
                    appendLine("Backup exclusion is enforced by FILE PATH, so a new DataStore is")
                    appendLine("backed up and device-transferred by default. Decide which it is:")
                    appendLine()
                    appendLine("  Holds a credential or anything private?")
                    appendLine("    → add an <exclude domain=\"file\" ")
                    appendLine("      path=\"datastore/<name>.preferences_pb\" /> to BOTH")
                    appendLine("      res/xml/backup_rules.xml and res/xml/data_extraction_rules.xml")
                    appendLine("      (cloud-backup AND device-transfer), then add it to")
                    appendLine("      mustBeExcluded in this test.")
                    appendLine()
                    appendLine("  Harmless to restore onto a new phone?")
                    appendLine("    → add it to mayBeBackedUp in this test, with a note saying why.")
                },
            )
        }
    }

    @Test
    fun `sensitive DataStores are excluded from cloud backup and device transfer`() {
        val backup = rules("backup_rules.xml")
        val extraction = rules("data_extraction_rules.xml")

        for (name in mustBeExcluded) {
            val path = "datastore/$name.preferences_pb"
            assertTrue(
                "$path is not excluded in backup_rules.xml (Auto Backup, API <= 30)",
                backup.contains(path),
            )
            // data_extraction_rules.xml has two independent sections and an exclude in one does
            // not imply the other — a token kept out of Google Drive can still ride a
            // phone-to-phone transfer. Require both.
            val cloud = extraction.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
            val transfer =
                extraction.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
            assertTrue("$path is not excluded from <cloud-backup>", cloud.contains(path))
            assertTrue("$path is not excluded from <device-transfer>", transfer.contains(path))
        }
    }

    @Test
    fun `the app actually points at these rule files`() {
        // An exclusion nothing references protects nothing.
        val manifest = File(moduleRoot, "src/main/AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest does not set android:fullBackupContent=\"@xml/backup_rules\"",
            manifest.contains("@xml/backup_rules"),
        )
        assertTrue(
            "AndroidManifest does not set android:dataExtractionRules=\"@xml/data_extraction_rules\"",
            manifest.contains("@xml/data_extraction_rules"),
        )
    }
}

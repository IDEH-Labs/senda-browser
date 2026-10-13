package org.senda.browser.e2e.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * E2 — encrypted backup (priority 2): create it, restore it into a cleared e2e app, and a wrong passphrase is
 * rejected. Android's file picker is another app and shows the person's own files in Downloads: inside it only the
 * test file is touched, by its exact name, and failure messages never copy what the picker shows. The test file is
 * deleted at the end.
 */
@RunWith(AndroidJUnit4::class)
class BackupTest : E2eTest() {

    private val fileName = "e2e-senda-backup.senda"
    private val passphrase = "e2e test passphrase only"

    override fun startPrefs(): Map<String, Any> = mapOf(
        "user_bookmarks" to
            "[{\"id\":\"e2e1\",\"title\":\"e2e-bookmark-one\",\"url\":\"${LocalWeb.url("/a.html")}\",\"timestamp\":1}," +
            "{\"id\":\"e2e2\",\"title\":\"e2e-bookmark-two\",\"url\":\"${LocalWeb.url("/b.html")}\",\"timestamp\":2}]"
    )

    /** In the file picker: no screen contents in the message, they are the person's files. */
    private fun inPicker(selector: androidx.test.uiautomator.BySelector, what: String): UiObject2 =
        E2e.find(selector, E2e.TIMEOUT) ?: fail("File picker: $what not found").let { error("unreachable") }

    /** Taps in the picker, again if it was still moving (its drawer animates). */
    private fun pickerTap(selector: androidx.test.uiautomator.BySelector, what: String) {
        repeat(5) {
            if (runCatching { inPicker(selector, what).click() }.isSuccess) return
            Thread.sleep(500)
        }
        fail("File picker: could not tap $what")
    }

    private fun pickerToDownloads() {
        pickerTap(By.desc(Pattern.compile("(Mostrar raíces|Show roots)")), "the folders button")
        Thread.sleep(800)
        pickerTap(By.text(Pattern.compile("(Descargas|Downloads)")), "Downloads")
        Thread.sleep(1000)
    }

    private fun openBackupDialog() {
        E2e.openSettings()
        E2e.tapTextInList(Es.backupTitle)
        E2e.need(By.text(Es.backupCreate), "the backup dialog")
    }

    /** Types [value] in the field labelled [label], again if the screen changed meanwhile. */
    private fun fill(label: String, value: String) {
        repeat(5) {
            val ok = runCatching {
                val field = E2e.device.findObjects(By.clazz("android.widget.EditText")).firstOrNull { it.hasObject(By.text(label)) }
                field?.text = value
                field != null
            }.getOrDefault(false)
            if (ok) return
            Thread.sleep(500)
        }
        fail("No field \"$label\"")
    }

    /** Taps the last element with [text] (the dialog's button, not the title), again if the screen changed. */
    private fun tapLast(text: String) {
        repeat(5) {
            if (runCatching { E2e.device.findObjects(By.text(text)).last().click() }.isSuccess) return
            Thread.sleep(500)
        }
        fail("No \"$text\" to tap")
    }

    private fun deleteTestFile() {
        val path = "/storage/emulated/0/Download/$fileName"
        E2e.sh("content delete --uri content://media/external/file --where _data='$path'")
        E2e.sh("rm -f $path")
    }

    @Test
    fun e2_backupCreateRestoreWrongPassphrase() {
        deleteTestFile()
        try {
            // Create
            E2e.launch()
            openBackupDialog()
            E2e.tapText(Es.backupCreate)
            fill(Es.backupPassLabel, passphrase)
            fill(Es.backupPassConfirm, passphrase)
            E2e.tapText(Es.backupSave)
            val name = inPicker(By.clazz("android.widget.EditText"), "the file name field")
            pickerToDownloads()
            (E2e.find(By.clazz("android.widget.EditText"), 3000) ?: name).text = fileName
            pickerTap(By.text(Pattern.compile("(?i)(guardar|save)")).clickable(true), "the save button")
            assertTrue("The backup was not saved", E2e.has(By.textContains(Es.backupLastPrefix), 15_000))
            assertTrue("The file is not in Downloads", E2e.sh("ls /storage/emulated/0/Download/$fileName").contains(fileName))

            // Restore into a cleared e2e app, first with a wrong passphrase
            E2e.reset(testProxyPrefs())
            E2e.launch()
            openBackupDialog()
            E2e.tapText(Es.backupRestore)
            pickerToDownloads()
            pickerTap(By.text(fileName), "the test file")
            E2e.need(By.text(Es.backupOpenTitle), "the passphrase dialog")
            fill(Es.backupPassLabel, "a wrong passphrase here")
            tapLast(Es.backupRestore)
            assertTrue("A wrong passphrase was not rejected", E2e.has(By.text(Es.backupWrongPass), 30_000))
            fill(Es.backupPassLabel, passphrase)
            tapLast(Es.backupRestore)
            E2e.need(By.text(Es.backupRestoreConfirm), "the restore preview", 30_000).click()
            Thread.sleep(2000)
            E2e.device.pressBack()
            E2e.device.pressBack()
            Thread.sleep(800)

            E2e.openMenu()
            E2e.tapTextInList(Es.bookmarks)
            assertTrue("Bookmarks not restored", E2e.has(By.textContains("e2e-bookmark-one"), 5000) && E2e.has(By.textContains("e2e-bookmark-two"), 2000))
            E2e.device.pressBack()
        } finally {
            deleteTestFile()
            assertTrue("The test file was not deleted", !E2e.sh("ls /storage/emulated/0/Download/").contains(fileName))
        }
    }
}

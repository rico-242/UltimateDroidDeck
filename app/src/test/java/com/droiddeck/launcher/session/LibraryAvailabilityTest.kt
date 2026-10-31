package com.droiddeck.launcher.session

import android.os.Environment
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryAvailabilityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()
    private val plenty = 100L * 1024 * 1024 * 1024

    @Test fun anUnpluggedVolumeIsMissingEvenIfItsFolderLingers() {
        for (state in listOf(Environment.MEDIA_UNMOUNTED, Environment.MEDIA_REMOVED, Environment.MEDIA_BAD_REMOVAL, Environment.MEDIA_EJECTING)) {
            assertEquals(state, LibraryState.MISSING, LibraryAvailability.classify(state, true, true, true, plenty))
        }
    }

    @Test fun drivesAndroidDoesNotListAreJudgedByTheirFolders() {
        assertEquals(LibraryState.AVAILABLE, LibraryAvailability.classify(Environment.MEDIA_UNKNOWN, true, true, true, plenty))
        assertEquals(LibraryState.MISSING, LibraryAvailability.classify(Environment.MEDIA_UNKNOWN, false, false, null, -1))
    }

    @Test fun readOnlyNotWritableAndLowSpaceAreTold() {
        assertEquals(LibraryState.READ_ONLY, LibraryAvailability.classify(Environment.MEDIA_MOUNTED_READ_ONLY, true, true, false, plenty))
        assertEquals(LibraryState.NOT_WRITABLE, LibraryAvailability.classify(Environment.MEDIA_MOUNTED, true, true, false, plenty))
        assertEquals(LibraryState.LOW_SPACE, LibraryAvailability.classify(Environment.MEDIA_MOUNTED, true, true, true, 1024))
        assertEquals(LibraryState.AVAILABLE, LibraryAvailability.classify(Environment.MEDIA_MOUNTED, true, true, true, -1))
    }

    @Test fun eroFsIsRecognisedThroughCauses() {
        assertTrue(LibraryAvailability.readOnlyError(IOException("x", IOException("open failed: EROFS (Read-only file system)"))))
        assertFalse(LibraryAvailability.readOnlyError(IOException("open failed: EACCES (Permission denied)")))
    }

    @Test fun checkLeavesNothingBehind() {
        val library = tmp.newFolder("steam")
        val status = LibraryAvailability.check(library.path) { Environment.MEDIA_MOUNTED }
        assertTrue(status.usable)
        assertEquals(0, library.list()!!.size)
        val fresh = File(tmp.root, "not-yet")
        assertTrue(LibraryAvailability.check(fresh.path) { Environment.MEDIA_MOUNTED }.usable)
        assertFalse(fresh.exists())
    }

    @Test fun anAbsentMountPointIsNeverRecreated() {
        val drive = File(tmp.root, "storage/ABCD-1234")
        val path = File(drive, "Android/data/com.droiddeck.launcher/files/steam").path
        SessionPrefs.setGameStorage(app, path, "Sandisk USB")
        assertEquals(LibraryState.MISSING, LibraryAvailability.check(path).state)
        val problem = GameStorage.prepare(app, path)
        assertNotNull(problem)
        assertTrue(problem!!, problem.contains("Sandisk USB"))
        assertFalse(drive.exists())
    }

    @Test fun aReconnectedDriveIsUsableAgainWithItsFilesIntact() {
        val chosen = File(tmp.root, "usb/Games")
        SessionPrefs.setGameStorage(app, chosen.path, "USB")
        assertEquals(LibraryState.MISSING, GameStorage.selectedStatus(app)!!.second.state)
        File(chosen, "steamapps").mkdirs()
        val manifest = File(chosen, "steamapps/appmanifest_220.acf").apply { writeText("x") }
        assertTrue(GameStorage.selectedStatus(app)!!.second.usable)
        assertNull(GameStorage.prepare(app, chosen.path))
        assertEquals("x", manifest.readText())
    }

    @Test fun choosingInternalStorageHasNoDriveToCheck() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        assertNull(GameStorage.selectedStatus(app))
    }

    @Test fun theSavedChoiceIsReadBackWithItsLabel() {
        val chosen = tmp.newFolder("SSD")
        SessionPrefs.setGameStorage(app, chosen.path, "Samsung T7")
        val reopened = GameStorage.selectedStatus(app)!!
        assertEquals(chosen.path, reopened.first.path)
        assertEquals("Samsung T7", reopened.first.name)
    }
}

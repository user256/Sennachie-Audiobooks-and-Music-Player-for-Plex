package org.johnfegan.plextouch.data

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.johnfegan.plextouch.R
import org.johnfegan.plextouch.ui.uiText
import org.johnfegan.plextouch.ui.UserFacing

class AudiobookProgressTest {
    private val connection = PlexConnection("https://example.test", "test-token")
    private val album = PlexAlbum("10", "Book", "Author", null, 2)
    private val chapter = PlexChapterProgress("11", "Chapter one", 100_000, 40_000, 0, 123)
    private val progress = ListeningProgress(album, LibraryMode.AUDIOBOOK, connection.serverUrl, 0,
        20_000, 20_000, 200_000, 1_700_000_000_000, trackId = "11", accountScope = connection.progressScope())

    @Test fun scopeIsStableOpaqueAndAccountSpecific() {
        assertEquals(connection.progressScope(), connection.copy(serverUrl = connection.serverUrl + "/").progressScope())
        assertEquals(64, connection.progressScope().length)
        assertNotEquals(connection.progressScope(), connection.copy(token = "another-account").progressScope())
        assertNotEquals(connection.progressScope(), connection.copy(serverUrl = "https://other.test").progressScope())
        assertFalse(connection.progressScope().contains(connection.token))
    }

    @Test fun accountBackedScopeSurvivesServerTokenRotation() {
        val account = "account-token"
        assertEquals(connection.progressScope(account), connection.copy(token = "rotated-server-token").progressScope(account))
        assertNotEquals(connection.progressScope(account), connection.progressScope("different-account-token"))
    }

    @Test fun upgradeMigrationOnlyMovesTheMatchingServerTokenScope() {
        val oldScope = connection.progressScope()
        val currentScope = connection.progressScope("account-token")
        val other = progress.copy(server = "https://other.test", accountScope = oldScope)
        val unrelated = progress.copy(accountScope = "b".repeat(64))
        val migrated = migrateListeningProgressScope(listOf(progress.copy(accountScope = oldScope), other, unrelated), connection.serverUrl, oldScope, currentScope)
        assertEquals(currentScope, migrated[0].accountScope)
        assertEquals(oldScope, migrated[1].accountScope)
        assertEquals("b".repeat(64), migrated[2].accountScope)
    }

    @Test fun checkpointMatchesStableIdNotReorderedIndex() {
        val chapters = listOf(chapter.copy(id = "12"), chapter)
        assertEquals("11", phoneCheckpoint(progress, connection, chapters).trackId)
        assertEquals(20_000L, phoneCheckpoint(progress, connection, chapters).positionMs)
    }

    @Test fun savedHistoryRetainsCheckpointIdentityAcrossSerializationAndMerge() {
        val saved = mergeListeningProgress(emptyList(), progress).single()
        val restored = Gson().fromJson(Gson().toJson(saved), ListeningProgress::class.java)
        assertEquals(progress.trackId, restored.trackId)
        assertEquals(progress.accountScope, restored.accountScope)
        assertEquals(phoneCheckpoint(progress, connection, listOf(chapter)), phoneCheckpoint(restored, connection, listOf(chapter)))
    }

    @Test fun rejectLegacyHistoryWithoutDiscardingIt() {
        val old = Gson().fromJson("""{"album":{"id":"10"},"mode":"AUDIOBOOK","server":"https://example.test","trackIndex":0,"positionMs":20000}""", ListeningProgress::class.java)
        assertNull(old.trackId)
        assertNull(old.accountScope)
        assertThrows(IllegalArgumentException::class.java) { phoneCheckpoint(old, connection, listOf(chapter)) }
        assertEquals(20_000L, old.positionMs)
    }

    @Test fun rejectWrongAccountOrServer() {
        assertThrows(IllegalArgumentException::class.java) { phoneCheckpoint(progress, connection.copy(token = "other"), listOf(chapter)) }
        assertThrows(IllegalArgumentException::class.java) { phoneCheckpoint(progress.copy(server = "other"), connection, listOf(chapter)) }
    }

    @Test fun rejectMissingDuplicateOrCompletedChapter() {
        for (chapters in listOf(emptyList(), listOf(chapter.copy(id = "12")), listOf(chapter, chapter))) {
            assertThrows(IllegalArgumentException::class.java) { phoneCheckpoint(progress, connection, chapters) }
        }
        assertThrows(IllegalArgumentException::class.java) { phoneCheckpoint(progress.copy(finished = true), connection, listOf(chapter)) }
    }

    @Test fun rejectMusicAndInvalidOffsets() {
        assertThrows(IllegalArgumentException::class.java) { phoneCheckpoint(progress.copy(mode = LibraryMode.MUSIC), connection, listOf(chapter)) }
        for (offset in listOf(-1L, 100_000L, 100_001L)) {
            assertThrows(IllegalArgumentException::class.java) { phoneCheckpoint(progress.copy(positionMs = offset), connection, listOf(chapter)) }
        }
        assertEquals(0L, phoneCheckpoint(progress.copy(positionMs = 0), connection, listOf(chapter)).positionMs)
    }

    @Test fun multiplePlexOffsetsRemainExplicitChoices() {
        val chapters = listOf(chapter, chapter.copy(id = "12", positionMs = 10_000))
        assertEquals(2, chapters.count { it.resumable })
        assertFalse(chapter.copy(positionMs = 0, playCount = 1).resumable)
        assertFalse(chapter.copy(positionMs = 100_000).resumable)
        assertFalse(chapter.copy(durationMs = 0).resumable)
    }

    @Test fun confirmedRewindIsSentAndVerifiedNotMaxMerged() = runBlocking {
        var remote = listOf(chapter)
        var sends = 0
        val phone = phoneCheckpoint(progress, connection, remote)
        AudiobookProgressExchange({ remote }, { sends++; remote = listOf(chapter.copy(positionMs = it.positionMs)) })
            .export(remote, phone) { true }
        assertEquals(1, sends)
        assertEquals(20_000L, remote.single().positionMs)
    }

    @Test fun changedServerStatePreventsWrite() = runBlocking {
        var sends = 0
        val expected = listOf(chapter)
        val phone = phoneCheckpoint(progress, connection, expected)
        for (changed in listOf(chapter.copy(positionMs = 41_000), chapter.copy(playCount = 1), chapter.copy(lastViewedAt = 124))) {
            val result = runCatching { AudiobookProgressExchange({ listOf(changed) }, { sends++ }).export(expected, phone) { true } }
            assertTrue(result.isFailure)
        }
        assertEquals(0, sends)
    }

    @Test fun phoneChangeDuringReadPreventsWrite() = runBlocking {
        var valid = true
        var sends = 0
        val expected = listOf(chapter)
        val result = runCatching {
            AudiobookProgressExchange({ valid = false; expected }, { sends++ })
                .export(expected, phoneCheckpoint(progress, connection, expected)) { valid }
        }
        assertTrue(result.isFailure)
        assertEquals(0, sends)
    }

    @Test fun missingReadBackOrIgnoredOffsetNeverClaimsSuccess() = runBlocking {
        val expected = listOf(chapter)
        for (after in listOf(expected, emptyList())) {
            var sent = false
            val result = runCatching {
                AudiobookProgressExchange({ if (sent) after else expected }, { sent = true })
                    .export(expected, phoneCheckpoint(progress, connection, expected)) { true }
            }
            assertEquals(uiText(R.string.progress_not_confirmed), (result.exceptionOrNull() as UserFacing).text)
            assertEquals(20_000L, progress.positionMs)
        }
    }

    @Test fun sendFailureDoesNotRetryOrChangeLocalPosition() = runBlocking {
        var sends = 0
        val expected = listOf(chapter)
        val result = runCatching {
            AudiobookProgressExchange({ expected }, { sends++; throw java.io.IOException("offline") })
                .export(expected, phoneCheckpoint(progress, connection, expected)) { true }
        }
        assertTrue(result.isFailure)
        assertEquals(1, sends)
        assertEquals(20_000L, progress.positionMs)
    }
}

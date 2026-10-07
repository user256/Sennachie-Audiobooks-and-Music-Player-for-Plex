package org.johnfegan.plextouch.data

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ListeningLogTest {
    private val london = ZoneId.of("Europe/London")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun at(text: String, zone: ZoneId = london): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private fun sample(start: Long, end: Long, zone: ZoneId = london, scope: String? = "A", server: String = "home", album: String = "book", mode: LibraryMode = LibraryMode.AUDIOBOOK) =
        ListeningSample(scope, server, mode, album, start, end, zone)
    private fun List<ListeningDay>.on(date: String) = filter { it.date == date }.sumOf { it.listenedMs }

    @Test fun aSampleAcrossLocalMidnightIsSplitBetweenTheTwoDays() {
        val log = recordListening(emptyList(), sample(at("2026-01-15T23:59:55"), at("2026-01-16T00:00:05")))
        assertEquals(5_000, log.on("2026-01-15"))
        assertEquals(5_000, log.on("2026-01-16"))
        assertEquals(at("2026-01-16T00:00:05"), log.single { it.date == "2026-01-16" }.lastAt)
    }

    @Test fun theDayIsTheLocalDateInTheZoneOfListeningAndTheZoneIsRecorded() {
        val instant = at("2026-01-15T23:30:00")
        val inLondon = recordListening(emptyList(), sample(instant - 10_000, instant))
        val inTokyo = recordListening(emptyList(), sample(instant - 10_000, instant, zone = tokyo))
        assertEquals("2026-01-15", inLondon.single().date)
        assertEquals("Europe/London", inLondon.single().zone)
        assertEquals("2026-01-16", inTokyo.single().date)
        assertEquals("Asia/Tokyo", inTokyo.single().zone)
        // A trip: listening in London one day and in Tokyo the next is explained by the zones the stats report.
        val trip = recordListening(inLondon, sample(at("2026-01-17T09:00:00", tokyo), at("2026-01-17T09:00:10", tokyo), zone = tokyo))
        assertEquals(listOf("Asia/Tokyo", "Europe/London"), listeningStats(trip, "home", "A", LocalDate.parse("2026-01-17")).zones)
    }

    @Test fun daylightSavingDaysCountRealElapsedTimeOnTheirOwnDate() {
        // 29 March 2026: London jumps from 01:00 GMT to 02:00 BST. Ten real seconds straddle the jump.
        val spring = recordListening(emptyList(), sample(at("2026-03-29T00:59:55"), at("2026-03-29T00:59:55") + 10_000))
        assertEquals(10_000, spring.single().listenedMs)
        assertEquals("2026-03-29", spring.single().date)
        // Playing the whole 23-hour day in heartbeat-sized samples adds up to 23 hours, not 24.
        var log = emptyList<ListeningDay>()
        val start = at("2026-03-29T00:00:00")
        val end = at("2026-03-30T00:00:00")
        var cursor = start
        while (cursor < end) { log = recordListening(log, sample(cursor, minOf(cursor + 10_000, end))); cursor += 10_000 }
        assertEquals(23 * 3_600_000L, log.on("2026-03-29"))
        assertEquals(0, log.on("2026-03-30"))
        // 25 October 2026: 01:00–02:00 happens twice; ten seconds across the repeat are still ten seconds on that date.
        val autumn = at("2026-10-25T01:59:55")
        assertEquals(10_000, recordListening(emptyList(), sample(autumn, autumn + 10_000)).on("2026-10-25"))
    }

    @Test fun duplicateAndOverlappingSamplesAreCountedOnce() {
        val t = at("2026-02-01T10:00:00")
        var log = recordListening(emptyList(), sample(t, t + 10_000))
        assertEquals(log, recordListening(log, sample(t, t + 10_000)))
        log = recordListening(log, sample(t + 5_000, t + 15_000))
        assertEquals(15_000, log.single().listenedMs)
        // A sample entirely inside counted time adds nothing.
        assertEquals(log, recordListening(log, sample(t + 2_000, t + 8_000)))
    }

    @Test fun aSingleSampleIsCappedSoAStalledClockCannotAddHours() {
        val t = at("2026-02-01T10:00:00")
        val log = recordListening(emptyList(), sample(t, t + 2 * 3_600_000))
        assertEquals(ListeningLog.MAX_SAMPLE_MS, log.single().listenedMs)
        assertTrue(recordListening(emptyList(), sample(t, t)).isEmpty())
        assertTrue(recordListening(emptyList(), sample(t + 5, t)).isEmpty())
    }

    @Test fun aWallClockSetBackDoesNotFreezeCounting() {
        val t = at("2026-02-01T10:00:00")
        val ahead = recordListening(emptyList(), sample(t + 3_600_000, t + 3_600_000 + 10_000))
        val after = recordListening(ahead, sample(t, t + 10_000))
        assertEquals(20_000, after.single().listenedMs)
    }

    @Test fun retentionKeepsFourHundredLocalDays() {
        val first = at("2025-01-01T12:00:00")
        val old = recordListening(emptyList(), sample(first, first + 10_000))
        val day399 = LocalDate.parse("2025-01-01").plusDays(399).atTime(12, 0).atZone(london).toInstant().toEpochMilli()
        assertEquals(2, recordListening(old, sample(day399, day399 + 10_000)).size)
        val day400 = day399 + 86_400_000
        val pruned = recordListening(old, sample(day400, day400 + 10_000))
        assertEquals(listOf(LocalDate.parse("2025-01-01").plusDays(400).toString()), pruned.map { it.date })
    }

    @Test fun streaksCountConsecutiveLocalDaysOverTheThreshold() {
        var log = emptyList<ListeningDay>()
        fun listen(date: String, seconds: Int) {
            var cursor = at("${date}T20:00:00")
            repeat(seconds / 10) { log = recordListening(log, sample(cursor, cursor + 10_000)); cursor += 10_000 }
        }
        listOf("2026-01-01", "2026-01-02", "2026-01-03", "2026-01-05", "2026-01-06").forEach { listen(it, 120) }
        listen("2026-01-07", 30)
        val sunday = listeningStats(log, "home", "A", LocalDate.parse("2026-01-07"))
        assertEquals(2, sunday.currentStreak) // today has not reached a minute yet, yesterday did
        assertEquals(3, sunday.longestStreak)
        assertEquals(30_000, sunday.todayMs)
        assertEquals(5 * 120_000L + 30_000, sunday.weekMs)
        assertEquals(5 * 120_000L + 30_000, sunday.retainedMs)
        assertEquals(6, sunday.daysListened)
        assertEquals(LocalDate.parse("2026-01-01"), sunday.since)
        assertEquals(0, listeningStats(log, "home", "A", LocalDate.parse("2026-01-09")).currentStreak)
        assertEquals(3, listeningStats(log, "home", "A", LocalDate.parse("2026-01-03")).currentStreak)
        listen("2026-01-07", 90)
        assertEquals(3, listeningStats(log, "home", "A", LocalDate.parse("2026-01-07")).currentStreak)
        assertEquals(ListeningStats(), listeningStats(emptyList(), "home", "A", LocalDate.parse("2026-01-07")))
    }

    @Test fun musicAndBooksOnOneDayCountTogetherForStreaks() {
        val t = at("2026-01-10T08:00:00")
        var log = emptyList<ListeningDay>()
        repeat(3) { log = recordListening(log, sample(t + it * 10_000L, t + (it + 1) * 10_000L)) }
        repeat(3) { log = recordListening(log, sample(t + (3 + it) * 10_000L, t + (4 + it) * 10_000L, mode = LibraryMode.MUSIC, album = "album")) }
        assertEquals(2, log.size)
        assertEquals(1, listeningStats(log, "home", "A", LocalDate.parse("2026-01-10")).currentStreak)
    }

    @Test fun scopesAndServersAreIsolatedForCountingStatsAndClearing() {
        val t = at("2026-02-01T10:00:00")
        var log = recordListening(emptyList(), sample(t, t + 10_000))
        // Another account at the same moment is not a duplicate of the first.
        log = recordListening(log, sample(t, t + 10_000, scope = "B"))
        log = recordListening(log, sample(t, t + 10_000, server = "cabin"))
        assertEquals(3, log.size)
        val today = LocalDate.parse("2026-02-01")
        assertEquals(10_000, listeningStats(log, "home", "A", today).todayMs)
        assertEquals(10_000, listeningStats(log, "home", "B", today).todayMs)
        assertEquals(0, listeningStats(log, "home", null, today).todayMs)
        val cleared = clearListeningLog(log, "home", "A")
        assertEquals(0, listeningStats(cleared, "home", "A", today).todayMs)
        assertEquals(10_000, listeningStats(cleared, "home", "B", today).todayMs)
        assertEquals(10_000, listeningStats(cleared, "cabin", "A", today).todayMs)
    }

    @Test fun albumsPerDayAreDistinctMostRecentLastAndBounded() {
        val t = at("2026-02-01T10:00:00")
        var log = emptyList<ListeningDay>()
        (0..MAX_ALBUMS_PER_DAY + 4).forEach { log = recordListening(log, sample(t + it * 10_000L, t + (it + 1) * 10_000L, album = "b$it")) }
        log = recordListening(log, sample(t + 100 * 10_000L, t + 101 * 10_000L, album = "b10"))
        val albums = log.single().albums
        assertEquals(MAX_ALBUMS_PER_DAY, albums.size)
        assertEquals("b10", albums.last())
        assertEquals(1, albums.count { it == "b10" })
    }

    @Test fun theLogRoundTripsAndDamagedEntriesAreDropped() {
        val t = at("2026-02-01T10:00:00")
        val log = recordListening(emptyList(), sample(t, t + 10_000))
        val json = Gson().toJson(log)
        assertEquals(log, Gson().fromJson(json, Array<ListeningDay>::class.java).toList())
        val damaged = Gson().fromJson("""[{"server":"home","listenedMs":5}, {"server":"home","date":"bad","zone":"UTC","mode":"MUSIC","albums":[]}]""", Array<ListeningDay>::class.java)
        assertTrue(damaged.none { it.wellFormed() })
        assertTrue(pruneListeningLog(damaged.toList() + log, LocalDate.parse("2026-02-01")) == log)
    }
}

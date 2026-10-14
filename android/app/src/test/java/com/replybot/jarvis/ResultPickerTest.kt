package com.replybot.jarvis

import com.replybot.jarvis.ResultPicker.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultPickerTest {

    // Spotify's results for "kesaria song" on a real phone (row top, the row's words).
    private val spotify = listOf(
        Row(534, "More releases\nShow all"),
        Row(358, "Kesariya (From \"Brahmastra\")\nSong • Pritam, Arijit Singh, Amitabh Bhattacharya"),
        Row(666, "Teri Mitti\nSong • B Praak, Manoj Muntashir"),
        Row(842, "Khesari Lal Yadav\nArtist"),
        Row(1024, "Kesariya (From \"Brahmastra\")\nSingle • Pritam, Arijit Singh, Amitabh Bhattachary"),
        Row(1200, "Kesariya - Slowed and Reverb\nSong • Mirshad Bin Moideen, Pritam, Arijit Singh"),
        Row(1376, "Kesari (Original Motion Picture Soundtrack)\nAlbum • Tanishk Bagchi, Arko, Jasleen Royal, Manoj"),
    )

    @Test
    fun misheardSpellingStillFindsTheTopSong() {
        assertEquals(358, ResultPicker.pick(spotify, "kesaria song", waited = false)?.top)
        assertEquals(358, ResultPicker.pick(spotify, "Kesariya", waited = false)?.top)
    }

    @Test
    fun theWordsDecideNotJustTheTop() {
        assertEquals(1200, ResultPicker.pick(spotify, "kesariya slowed and reverb", waited = false)?.top)
        assertEquals(666, ResultPicker.pick(spotify, "teri mitti", waited = false)?.top)
    }

    @Test
    fun songsBeforeArtistsWhenBothMatch() {
        val rows = listOf(
            Row(300, "Arijit Singh\nArtist"),
            Row(480, "Tum Hi Ho\nSong • Arijit Singh"),
        )
        assertEquals(480, ResultPicker.pick(rows, "arijit singh", waited = false)?.top)
    }

    @Test
    fun nothingMatchingMeansWaitThenTrustTheTopSong() {
        val rows = listOf(Row(700, "Teri Mitti\nSong • B Praak"), Row(500, "Some Artist\nArtist"))
        assertNull(ResultPicker.pick(rows, "zxqv blorp", waited = false))
        assertEquals(700, ResultPicker.pick(rows, "zxqv blorp", waited = true)?.top)
        assertNull(ResultPicker.pick(emptyList(), "kesariya", waited = true))
    }

    @Test
    fun looseButNotTooLoose() {
        assertTrue(ResultPicker.similar("kesaria", "kesariya"))
        assertTrue(ResultPicker.similar("beat", "beats"))
        assertFalse(ResultPicker.similar("kesariya", "kesari"))
        assertFalse(ResultPicker.similar("tum", "tu"))
        assertFalse(ResultPicker.similar("love", "live"))
    }
}

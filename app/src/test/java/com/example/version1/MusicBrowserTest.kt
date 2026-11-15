package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class MusicBrowserTest {
    @Test fun vlcNeverFallsBackToAnUnrelatedOrSystemStubProvider() {
        val vlc = MusicBrowserCandidate("org.videolan.vlc", "PlaybackService", true, true)
        val stub = MusicBrowserCandidate("com.android.music", "Stub", true, true)
        assertEquals(vlc, selectMusicBrowser(MusicSource.VLC, listOf(stub, vlc)))
        assertNull(selectMusicBrowser(MusicSource.VLC, listOf(stub)))
    }
    @Test fun loadingAndUnknownStatesMustNotClaimPlayback() {
        assertEquals("재생 중", musicPlaybackLabel(3))
        assertEquals("일시정지됨", musicPlaybackLabel(2))
        for (state in listOf(0, 6, 7, 8, 99)) assertNotEquals("재생 중", musicPlaybackLabel(state))
    }
    @Test fun anInstalledPlayerWithoutAnExportedHandoffIsNotReportedAsMissing() {
        assertEquals(MusicAppAccess.UNSUPPORTED, musicAppAccess(openable = false, present = true))
        assertEquals(MusicAppAccess.NOT_INSTALLED, musicAppAccess(openable = false, present = false))
        assertEquals(MusicAppAccess.OPENABLE, musicAppAccess(openable = true, present = true))
    }
    @Test fun onlyTheSelectedOfficialProviderCanBeChosen() {
        val melon = MusicBrowserCandidate("com.iloen.melon", "Browser", true, true)
        val other = MusicBrowserCandidate("unrelated.music", "Browser", true, true)
        assertEquals(melon, selectMusicBrowser(MusicSource.MELON, listOf(other, melon)))
        assertNull(selectMusicBrowser(MusicSource.YOUTUBE_MUSIC, listOf(melon)))
        assertNull(selectMusicBrowser(MusicSource.AMBIENT, listOf(other, melon)))
    }
    @Test fun privateOrDisabledServicesCannotBeHandedToTheVehiclePlayer() {
        val service = MusicBrowserCandidate("com.iloen.melon", "Browser", true, true)
        assertNull(selectMusicBrowser(MusicSource.MELON, listOf(service.copy(exported = false))))
        assertNull(selectMusicBrowser(MusicSource.MELON, listOf(service.copy(enabled = false))))
    }
}

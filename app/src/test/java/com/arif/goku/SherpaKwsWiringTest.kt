package com.arif.goku

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.arif.goku.voice.KwsDownloadStage
import com.arif.goku.voice.KwsModelManager
import com.arif.goku.voice.WakePhraseMatch
import com.arif.goku.voice.WakeWordMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SherpaKwsWiringTest {

    @Test
    fun sherpaAvailabilityCheckDoesNotThrowWhenLocalAarAbsent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = KwsModelManager(context)
        val state = manager.refreshStatus()
        assertEquals(KwsModelManager.isSherpaOnnxClassAvailable(), state.hasSherpaNativeBinding)
    }

    @Test
    fun createKeywordSpotterSessionDegradesGracefullyWhenModelFilesMissing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = KwsModelManager(context)
        manager.deleteModel()

        val state = manager.refreshStatus()
        assertEquals(KwsDownloadStage.NOT_DOWNLOADED, state.stage)
        assertFalse(state.hasOnnxFiles)
        assertNull(manager.createKeywordSpotterSession())
    }

    @Test
    fun wakeWordMatcherHandlesSherpaLabelsAndBpeTokens() {
        assertEquals(WakePhraseMatch.HEY_GOKU, WakeWordMatcher.matchPhrase("HEY_GOKU"))
        assertEquals(WakePhraseMatch.HEY_GOKU, WakeWordMatcher.matchPhrase("▁HEY ▁GO KU"))
        assertEquals(WakePhraseMatch.HEY_GOKU, WakeWordMatcher.matchPhrase("Hey Goku"))
        assertEquals(WakePhraseMatch.GOKU_STOP, WakeWordMatcher.matchPhrase("GOKU_STOP"))
        assertEquals(WakePhraseMatch.GOKU_STOP, WakeWordMatcher.matchPhrase("▁GO KU ▁STOP"))
        assertEquals(WakePhraseMatch.GOKU_STOP, WakeWordMatcher.matchPhrase("Goku stop"))
        assertNull(WakeWordMatcher.matchPhrase("hello world"))
    }
}

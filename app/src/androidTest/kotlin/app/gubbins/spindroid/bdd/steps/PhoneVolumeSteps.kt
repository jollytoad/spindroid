package app.gubbins.spindroid.bdd.steps

import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import app.gubbins.spindroid.bdd.support.ComposeRuleHolder
import app.gubbins.spindroid.bdd.support.TestSession
import app.gubbins.spindroid.capture.CaptureStatusHolder
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/**
 * The steps that read the phone's own volume and the sound the capture
 * carries.
 *
 * The volume belongs to the phone, not to the app, so the steps set and
 * read it through the audio manager the way the app itself would. The
 * capture level comes from the app's own holder, because the ear cannot
 * tell the steps what the stream carries, and the tone gives the stream
 * a voice that the sessions alone never make.
 */
class PhoneVolumeSteps {
    private val composeRule get() = ComposeRuleHolder.current.composeRule
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun stopTheTone() {
        // A tone that outlives its scenario keeps the phone loud for
        // the scenarios that follow.
        TestSession.tone(false)
    }

    @After
    fun leaveThePhoneAudible() {
        // A scenario that ends with the phone muted leaves it muted,
        // and someone picks the phone up between the runs. A scenario
        // that muted the phone on purpose has already said what it
        // wanted, so the kindness comes after the last word.
        val audio = audio()
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, AUDIBLE_VOLUME, 0)
            } catch (exception: SecurityException) {
                // The phone sits under a rule that forbids the change,
                // and the next scenario sets its own volume anyway.
            }
        }
    }

    @Given("the phone's media volume reads {int}")
    fun thePhonesMediaVolumeReads(volume: Int) {
        audio().setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        val now = audio().getStreamVolume(AudioManager.STREAM_MUSIC)
        check(now == volume) {
            "The volume did not take: it reads $now, and the step asked for $volume."
        }
    }

    @Then("the phone's media volume still reads {int}")
    fun thePhoneHoldsTheMediaVolume(volume: Int) {
        val now = audio().getStreamVolume(AudioManager.STREAM_MUSIC)
        check(now == volume) {
            "The volume reads $now, and the step expected $volume."
        }
    }

    @When("a tone plays on the phone")
    fun aTonePlaysOnThePhone() {
        TestSession.tone(true)
    }

    @Then("the captured sound rises above silence")
    fun theCapturedSoundRises() {
        awaitUntil(AWAIT_SOUND_MILLIS) {
            CaptureStatusHolder.state.value.peak > 0
        }
    }

    @Then("the captured sound keeps its level")
    fun theCapturedSoundKeepsItsLevel() {
        // A few capture windows pass first, so a level that falls late
        // still says so.
        Thread.sleep(WATCH_LEVEL_MILLIS)
        check(CaptureStatusHolder.state.value.peak > 0) {
            "The capture carried no sound after the phone went quiet, " +
                "so the capture follows the volume of the phone."
        }
    }

    /** The audio manager of the phone. */
    private fun audio(): AudioManager =
        context.getSystemService(AudioManager::class.java)

    /** Waits until the condition holds, and fails when it never does. */
    private fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        check(condition()) { "The condition did not hold within ${timeoutMillis / 1000} seconds." }
    }

    private companion object {
        /** How long the sound may take to show in the capture. */
        const val AWAIT_SOUND_MILLIS = 15_000L

        /** How long the level is watched after the volume change. */
        const val WATCH_LEVEL_MILLIS = 6_000L

        /** How long a wait sleeps between two checks. */
        const val POLL_MILLIS = 250L

        /** The volume a courteous end leaves on the phone. */
        const val AUDIBLE_VOLUME = 12    }
}

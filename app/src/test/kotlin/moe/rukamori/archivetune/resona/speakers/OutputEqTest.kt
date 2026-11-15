/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EQ per output device: mapping and switching. Same cases as the iPad's OutputEqTests. */
class OutputEqTest {
    private val flat = EqSnapshot(enabled = true, profileId = "flat", bandLevelsMb = List(5) { 0 })
    private val bass = EqSnapshot(enabled = true, profileId = "system:2", bandLevelsMb = listOf(700, 400, 0, 0, 0), bassBoostEnabled = true, bassBoostStrength = 500)
    private val off = flat.copy(enabled = false)

    private val headphones = AudioOutput(OutputKind.BLUETOOTH, "Soundcore Space One")
    private val echo = AudioOutput(OutputKind.BLUETOOTH, "Echo Dot")

    @Test
    fun keys() {
        assertEquals("builtin", OutputEqRules.key(AudioOutput.BUILT_IN))
        assertEquals("bluetooth:soundcore space one", OutputEqRules.key(headphones))
        assertEquals(OutputEqRules.key(headphones), OutputEqRules.key(AudioOutput(OutputKind.BLUETOOTH, " Soundcore Space One ")))
        assertNotEquals(OutputEqRules.key(AudioOutput(OutputKind.CAST, "Echo Dot")), OutputEqRules.key(echo))
    }

    @Test
    fun unknownDeviceUsesTheDefault() {
        val state = OutputEqState(standard = flat)
        assertEquals(flat, OutputEqRules.resolve(state, OutputEqRules.key(echo)))
        assertFalse(OutputEqRules.hasOwn(state, OutputEqRules.key(echo)))
    }

    @Test
    fun editIsRememberedPerDeviceAndSwitchesBack() {
        val phones = OutputEqRules.key(headphones)
        val state = OutputEqRules.record(OutputEqState(standard = flat), phones, "Soundcore Space One", bass)
        assertTrue(OutputEqRules.hasOwn(state, phones))
        assertEquals("Soundcore Space One", state.devices[phones]?.name)
        assertEquals(flat, state.standard)

        assertEquals(flat, OutputEqRules.switchTo(state, "builtin", bass))
        assertEquals(bass, OutputEqRules.switchTo(state, phones, flat))
        assertNull(OutputEqRules.switchTo(state, phones, bass))
        assertNull(OutputEqRules.switchTo(state, OutputEqRules.key(echo), flat))
    }

    @Test
    fun sameForAllUsesAndEditsTheDefault() {
        val phones = OutputEqRules.key(headphones)
        var state = OutputEqRules.record(OutputEqState(standard = flat), phones, "Soundcore Space One", bass).copy(sameForAll = true)
        assertEquals(flat, OutputEqRules.resolve(state, phones))
        assertFalse(OutputEqRules.hasOwn(state, phones))
        state = OutputEqRules.record(state, phones, "Soundcore Space One", off)
        assertEquals(off, state.standard)
        assertEquals("the device's own EQ is kept for later", bass, state.devices[phones]?.eq)
        assertEquals(off, OutputEqRules.resolve(state, "builtin"))
        assertEquals(bass, OutputEqRules.resolve(state.copy(sameForAll = false), phones))
    }

    @Test
    fun useDefaultForgetsTheDevice() {
        val phones = OutputEqRules.key(headphones)
        val state = OutputEqRules.useStandard(OutputEqRules.record(OutputEqState(standard = flat), phones, "x", bass), phones)
        assertFalse(OutputEqRules.hasOwn(state, phones))
        assertEquals(flat, OutputEqRules.resolve(state, phones))
    }

    @Test
    fun stateSurvivesSaving() {
        val state = OutputEqRules.record(OutputEqState(standard = flat), "bluetooth:echo dot", "Echo Dot", bass)
        assertEquals(state, OutputEq.decode(OutputEq.encode(state)))
        assertEquals(OutputEqState(), OutputEq.decode("{kaputt"))
        assertEquals(OutputEqState(), OutputEq.decode(null))
    }

    @Test
    fun hintLabels() {
        assertEquals(EqLabel.Off, OutputEqRules.label(off, emptyMap()))
        assertEquals(EqLabel.Flat, OutputEqRules.label(flat, emptyMap()))
        assertEquals(EqLabel.System(3), OutputEqRules.label(bass, emptyMap()))
        assertEquals(EqLabel.Profile("Mein Kopfhörer"), OutputEqRules.label(flat.copy(profileId = "profile:p1"), mapOf("p1" to "Mein Kopfhörer")))
        assertEquals(EqLabel.Custom, OutputEqRules.label(flat.copy(profileId = "profile:weg"), emptyMap()))
        assertEquals(EqLabel.Custom, OutputEqRules.label(flat.copy(profileId = "manual"), emptyMap()))
    }
}

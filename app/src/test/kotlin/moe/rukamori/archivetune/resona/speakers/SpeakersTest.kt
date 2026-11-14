/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona.speakers

import android.bluetooth.BluetoothClass
import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakersTest {
    @Test
    fun bluetoothWinsOverWiredAndSpeaker() {
        val outputs =
            listOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "Pixel",
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES to "Kabel",
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "Echo Dot",
            )
        assertEquals(AudioOutput(OutputKind.BLUETOOTH, "Echo Dot"), Speakers.pick(outputs))
        assertEquals(OutputKind.HEADPHONES, Speakers.pick(outputs.take(2)).kind)
        assertEquals(AudioOutput.BUILT_IN, Speakers.pick(outputs.take(1)))
        assertEquals(AudioOutput.BUILT_IN, Speakers.pick(emptyList()))
    }

    @Test
    fun kinds() {
        assertEquals(OutputKind.BUILT_IN, Speakers.kindFor(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(OutputKind.BLUETOOTH, Speakers.kindFor(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertEquals(OutputKind.HEADPHONES, Speakers.kindFor(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(OutputKind.TV, Speakers.kindFor(AudioDeviceInfo.TYPE_HDMI))
        assertEquals(OutputKind.OTHER, Speakers.kindFor(-12345))
        assertFalse(AudioOutput.BUILT_IN.isExternal)
        assertTrue(AudioOutput(OutputKind.BLUETOOTH, "Echo Dot").isExternal)
    }

    @Test
    fun onlyAudioDevicesAreListed() {
        assertTrue(Speakers.isAudio(BluetoothClass.Device.Major.AUDIO_VIDEO, false))
        assertTrue(Speakers.isAudio(BluetoothClass.Device.Major.UNCATEGORIZED, true))
        assertFalse(Speakers.isAudio(BluetoothClass.Device.Major.PHONE, false))
        assertFalse(Speakers.isAudio(BluetoothClass.Device.Major.COMPUTER, false))
        assertFalse(Speakers.isAudio(null, false))
    }

    @Test
    fun headphoneNames() {
        assertTrue(Speakers.looksLikeHeadphones("Platzhalter Buds Pro"))
        assertTrue(Speakers.looksLikeHeadphones("WH-1000XM9"))
        assertFalse(Speakers.looksLikeHeadphones("Echo Dot"))
    }
}

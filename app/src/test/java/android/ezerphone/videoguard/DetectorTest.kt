package android.ezerphone.videoguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorTest {
    @Test fun findsDecoderLibs() {
        val entries = sequenceOf(
            "classes.dex", "lib/arm64-v8a/libavcodec.so", "lib/arm64-v8a/libavcodec-58.so",
            "lib/armeabi-v7a/libvlcjni.so", "lib/arm64-v8a/libmpv.so", "lib/arm64-v8a/libdav1d.so",
            "lib/arm64-v8a/libijkplayer.so", "lib/arm64-v8a/libffmpegJNI.so", "assets/lib/libvpx.so",
            "lib/arm64-v8a/libc++_shared.so", "lib/arm64-v8a/libsqlite.so", "res/raw/libavcodec.txt"
        )
        assertEquals(
            listOf("libavcodec.so", "libavcodec-58.so", "libvlcjni.so", "libmpv.so", "libdav1d.so",
                "libijkplayer.so", "libffmpegJNI.so", "libvpx.so"),
            Detector.decoderLibs(entries)
        )
    }

    @Test fun ignoresOrdinaryLibs() {
        for (f in listOf("libc++_shared.so", "libcrashlytics.so", "libimage.so", "libflutter.so", "avcodec.so"))
            assertFalse(f, Detector.isDecoderLib(f))
        assertTrue(Detector.isDecoderLib("LIBAVFORMAT.SO"))
    }
}

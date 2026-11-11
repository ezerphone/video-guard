package android.ezerphone.videoguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecFilterTest {
    private val sample = """<?xml version="1.0" encoding="utf-8" ?>
<!-- header comment with <MediaCodec name="in.comment" type="video/avc"/> -->
<MediaCodecs>
    <Include href="media_codecs_google_audio.xml" />
    <Settings>
        <Setting name="max-video-encoder-input-buffers" value="12" />
    </Settings>
    <Encoders>
        <MediaCodec name="c2.enc.avc" type="video/avc">
            <Limit name="size" min="2x2" max="1920x1088" />
        </MediaCodec>
    </Encoders>
    <Decoders>
        <MediaCodec name="c2.android.aac.decoder" type="audio/mp4a-latm">
            <Limit name="channel-count" max="8" />
        </MediaCodec>
        <MediaCodec name="c2.android.avc.decoder"
                    type="video/avc">
            <Alias name="OMX.google.h264.decoder" />
            <Limit name="size" min="2x2" max="4080x4080" />
        </MediaCodec>
        <MediaCodec name="c2.android.vp9.decoder" type='video/x-vnd.on2.vp9' update="true"/>
        <!-- <MediaCodec name="commented.out" type="audio/raw"/> -->
        <MediaCodec name="multi">
            <Type name="video/hevc" />
        </MediaCodec>
        <MediaCodec name="c2.x.audio" type="audio/raw"/><MediaCodec name="inline.video" type="video/av01"/>
        <MediaCodec name="perf.update.only" update="true">
            <Limit name="measured-frame-rate-320x240" range="100-200" />
        </MediaCodec>
    </Decoders>
</MediaCodecs>
"""

    @Test fun removesOnlyVideoDecoders() {
        val r = CodecFilter.strip(sample)
        assertEquals(listOf("c2.android.avc.decoder", "c2.android.vp9.decoder", "multi", "inline.video"), r.removed)
        assertTrue(r.text.contains("c2.android.aac.decoder"))
        assertTrue(r.text.contains("""<MediaCodec name="c2.x.audio" type="audio/raw"/>"""))
        assertTrue("encoder kept", r.text.contains("c2.enc.avc"))
        assertTrue("comments kept", r.text.contains("in.comment") && r.text.contains("commented.out"))
        assertFalse(r.text.contains("OMX.google.h264.decoder"))
        assertNull(CodecFilter.verify(sample, r.text))
    }

    @Test fun keepsFormattingOfUntouchedLines() {
        val r = CodecFilter.strip(sample)
        assertTrue(r.text.contains("    <Decoders>\n        <MediaCodec name=\"c2.android.aac.decoder\""))
        assertFalse("no blank lines left behind", r.text.contains("\n\n"))
    }

    @Test fun removesUpdatesOfVideoCodecsDefinedElsewhere() {
        val names = CodecFilter.videoDecoderNames(sample)
        assertTrue("c2.android.avc.decoder" in names && "multi" in names)
        val perf = """<MediaCodecs>
    <Decoders>
        <MediaCodec name="c2.android.avc.decoder" update="true">
            <Limit name="measured-frame-rate-320x240" range="300-400" />
        </MediaCodec>
        <MediaCodec name="c2.android.aac.decoder" update="true" />
    </Decoders>
</MediaCodecs>"""
        val r = CodecFilter.strip(perf, names)
        assertEquals(listOf("c2.android.avc.decoder"), r.removed)
        assertTrue(r.text.contains("c2.android.aac.decoder"))
        assertNull(CodecFilter.verify(perf, r.text, names))
    }

    @Test fun noDecodersMeansNoChange() {
        val x = "<MediaCodecs>\n    <Encoders>\n        <MediaCodec name=\"e\" type=\"video/avc\"/>\n    </Encoders>\n</MediaCodecs>\n"
        val r = CodecFilter.strip(x)
        assertTrue(r.removed.isEmpty())
        assertEquals(x, r.text)
    }

    @Test fun verifyCatchesBrokenOutput() {
        val r = CodecFilter.strip(sample)
        assertTrue(CodecFilter.verify(sample, r.text.replace("</Decoders>", "")) != null)
        assertTrue(CodecFilter.verify(sample, r.text.replace("c2.x.audio", "zzz")) != null)
        assertTrue(CodecFilter.verify(sample, sample) != null) // video left in
    }

    @Test fun idempotent() {
        val once = CodecFilter.strip(sample).text
        val twice = CodecFilter.strip(once)
        assertTrue(twice.removed.isEmpty())
        assertEquals(once, twice.text)
    }
}

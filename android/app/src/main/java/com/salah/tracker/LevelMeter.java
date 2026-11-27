package com.salah.tracker;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Passes the live audio through unchanged and measures how loud it is every half second,
 * so the player can tell when the adhān has finished and the masjid has gone quiet.
 * "Loud" is judged against the loudest voice heard so far (the mu'adhdhin), so a fan or
 * air-conditioner hum in the background counts as quiet.
 */
@OptIn(markerClass = UnstableApi.class)
final class LevelMeter extends BaseAudioProcessor {
    volatile long lastLoud = 0, loudMs = 0;
    private float voice = 0f;
    private int window = 22050, n = 0;
    private double sum = 0;

    void clear() { lastLoud = 0; loudMs = 0; voice = 0f; n = 0; sum = 0; }

    @Override
    protected AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat f) throws AudioProcessor.UnhandledAudioFormatException {
        if (f.encoding != C.ENCODING_PCM_16BIT) throw new AudioProcessor.UnhandledAudioFormatException(f);
        window = Math.max(1000, f.sampleRate * Math.max(1, f.channelCount) / 2);
        return f;
    }

    @Override
    public void queueInput(ByteBuffer in) {
        int len = in.remaining();
        if (len == 0) return;
        ByteBuffer d = in.duplicate().order(ByteOrder.nativeOrder());
        for (int i = d.position(); i + 1 < d.limit(); i += 2) {
            double s = d.getShort(i);
            sum += s * s;
            if (++n >= window) { half(Math.sqrt(sum / n) / 32768.0); sum = 0; n = 0; }
        }
        ByteBuffer out = replaceOutputBuffer(len);
        out.put(in);
        out.flip();
    }

    private void half(double rms) {
        float r = (float) rms;
        if (r > voice) voice = r;
        else if (r > voice * 0.5f) voice = voice * 0.95f + r * 0.05f;
        if (r > Math.max(voice * 0.3f, 0.01f)) { lastLoud = System.currentTimeMillis(); loudMs += 500; }
    }
}

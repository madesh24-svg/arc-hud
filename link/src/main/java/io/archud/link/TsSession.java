package io.archud.link;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Reads a video on this phone with Android's own media reader and writes it as a continuous MPEG
 * transport stream, starting at a requested time. Video (H.264/H.265) and sound (AAC, AC-3, E-AC-3, MP3)
 * are passed through unchanged, only repackaged, so the phone does almost no work. Every seek is a new
 * session that starts at the new time, so the TV never needs the file's index.
 */
final class TsSession {
    interface Cancel {
        boolean cancelled();
    }

    /** What the phone found in the file. */
    static final class Info {
        boolean ok;
        long durationMs;
        int videoTrack = -1;
        int audioTrack = -1;
        String videoMime = "";
        String audioMime = "";
        /** Short plain text for the TV to show (for example that the sound is not supported). */
        String note = "";
    }

    private static final int SAMPLE_BUFFER = 8 << 20;
    private static final byte[] AUD_H264 = {0, 0, 0, 1, 0x09, (byte) 0xF0};
    private static final byte[] AUD_HEVC = {0, 0, 0, 1, 0x46, 0x01, 0x50};
    private static final int[] AAC_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000,
            12000, 11025, 8000, 7350};

    private final Info info;
    private final MediaExtractor video;
    private final MediaExtractor audio;
    private final long startUs;
    private final byte[] paramSets;
    private final boolean hevc;
    private final int audioType;
    private final byte[] adtsBase;
    /** AAC frames waiting to go out together in one packet, as broadcast streams do. */
    private final ByteArrayOutputStream audioBatch = new ByteArrayOutputStream();
    private long batchPts;
    private int batchFrames;
    /** First picture time actually used (a key frame at or before the one asked for), in milliseconds. */
    final long startMs;

    // ---------------------------------------------------------------- what can be streamed

    static int audioStreamType(String mime) {
        if ("audio/mp4a-latm".equals(mime)) {
            return 0x0F;
        }
        if ("audio/ac3".equals(mime)) {
            return 0x81;
        }
        if ("audio/eac3".equals(mime)) {
            return 0x87;
        }
        if ("audio/mpeg".equals(mime)) {
            return 0x03;
        }
        return 0;
    }

    /** Looks at the file: is it a video this packer can stream, how long is it, is the sound usable. */
    static Info probe(Context c, Uri uri) {
        Info i = new Info();
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(c, uri, null);
            String unsupportedAudio = "";
            for (int t = 0; t < ex.getTrackCount(); t++) {
                MediaFormat f = ex.getTrackFormat(t);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m == null) {
                    continue;
                }
                if (m.startsWith("video/") && i.videoTrack < 0) {
                    if ("video/avc".equals(m) || "video/hevc".equals(m)) {
                        i.videoTrack = t;
                        i.videoMime = m;
                        if (f.containsKey(MediaFormat.KEY_DURATION)) {
                            i.durationMs = f.getLong(MediaFormat.KEY_DURATION) / 1000;
                        }
                    }
                } else if (m.startsWith("audio/") && i.audioTrack < 0) {
                    if (audioStreamType(m) != 0) {
                        i.audioTrack = t;
                        i.audioMime = m;
                    } else if (unsupportedAudio.isEmpty()) {
                        unsupportedAudio = m;
                    }
                }
            }
            if (i.audioTrack < 0 && !unsupportedAudio.isEmpty()) {
                i.note = "No sound: " + unsupportedAudio.replace("audio/", "") + " audio is not supported";
            }
            i.ok = i.videoTrack >= 0 && i.durationMs > 0;
        } catch (IOException | RuntimeException e) {
            i.ok = false;
        } finally {
            ex.release();
        }
        return i;
    }

    // ---------------------------------------------------------------- one stream

    /** Opens the file and moves to the key frame at or before wantMs. */
    TsSession(Context c, Uri uri, Info info, long wantMs, boolean wantAudio) throws IOException {
        this.info = info;
        this.hevc = "video/hevc".equals(info.videoMime);
        video = new MediaExtractor();
        MediaExtractor a = null;
        try {
            video.setDataSource(c, uri, null);
            video.selectTrack(info.videoTrack);
            video.seekTo(Math.max(0, wantMs) * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long t0 = video.getSampleTime();
            if (t0 < 0) {
                video.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                t0 = video.getSampleTime();
            }
            if (t0 < 0) {
                throw new IOException("The video has no pictures");
            }
            startUs = t0;
            startMs = t0 / 1000;
            paramSets = parameterSets(video.getTrackFormat(info.videoTrack));

            int type = 0;
            byte[] adts = null;
            if (wantAudio && info.audioTrack >= 0) {
                a = new MediaExtractor();
                a.setDataSource(c, uri, null);
                a.selectTrack(info.audioTrack);
                a.seekTo(t0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                type = audioStreamType(info.audioMime);
                if (type == 0x0F) {
                    adts = adtsHeaderBase(a.getTrackFormat(info.audioTrack));
                    if (adts == null) {
                        a.release();
                        a = null;
                        type = 0;
                    }
                }
            }
            audio = a;
            audioType = type;
            adtsBase = adts;
        } catch (IOException | RuntimeException e) {
            video.release();
            if (a != null) {
                a.release();
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
        }
    }

    void close() {
        video.release();
        if (audio != null) {
            audio.release();
        }
    }

    /** Writes the stream until the end of the file, or until cancelled or the TV goes away. */
    void stream(OutputStream out, Cancel cancel) throws IOException {
        TsMuxer mux = new TsMuxer(out, hevc ? 0x24 : 0x1B, audio != null ? audioType : 0);
        mux.writeTables();
        ByteBuffer buf = ByteBuffer.allocate(SAMPLE_BUFFER);
        ByteArrayOutputStream unit = new ByteArrayOutputStream(1 << 20);
        long lastPcr = 0;
        long tv = video.getSampleTime();
        long ta = audio != null ? audio.getSampleTime() : -1;
        while (tv >= 0 || ta >= 0) {
            if (cancel.cancelled()) {
                return;
            }
            boolean takeAudio = ta >= 0 && (tv < 0 || ta <= tv);
            MediaExtractor ex = takeAudio ? audio : video;
            buf.clear();
            int size = ex.readSampleData(buf, 0);
            if (size < 0) {
                if (takeAudio) {
                    ta = -1;
                } else {
                    tv = -1;
                }
                continue;
            }
            long timeUs = ex.getSampleTime();
            int flags = ex.getSampleFlags();
            byte[] data = buf.array();
            long pts = TsMuxer.BASE + Math.max(0, (timeUs - startUs)) * 9 / 100;
            if (takeAudio) {
                writeAudio(mux, data, size, pts);
            } else if (timeUs >= startUs || (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                boolean key = (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                lastPcr = Math.max(lastPcr, pts - 27_000);
                if (key) {
                    mux.writeTables();
                }
                unit.reset();
                unit.write(hevc ? AUD_HEVC : AUD_H264, 0, hevc ? AUD_HEVC.length : AUD_H264.length);
                if (key && paramSets.length > 0) {
                    unit.write(paramSets, 0, paramSets.length);
                }
                toAnnexB(data, size, unit);
                mux.writePes(TsMuxer.PID_VIDEO, 0xE0, unit.toByteArray(), 0, unit.size(), pts, lastPcr, key);
            }
            ex.advance();
            if (takeAudio) {
                ta = ex.getSampleTime();
            } else {
                tv = ex.getSampleTime();
            }
        }
        flushAudio(mux);
        out.flush();
    }

    private void flushAudio(TsMuxer mux) throws IOException {
        if (batchFrames > 0) {
            byte[] bytes = audioBatch.toByteArray();
            mux.writePes(TsMuxer.PID_AUDIO, 0xC0, bytes, 0, bytes.length, batchPts, -1, false);
            audioBatch.reset();
            batchFrames = 0;
        }
    }

    // ---------------------------------------------------------------- conversions

    private void writeAudio(TsMuxer mux, byte[] data, int size, long pts) throws IOException {
        if (audioType == 0x0F) {
            byte[] frame = new byte[size + 7];
            int frameLength = size + 7;
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) 0xF1;
            frame[2] = adtsBase[0];
            frame[3] = (byte) ((adtsBase[1] & 0xC0) | ((frameLength >> 11) & 0x03));
            frame[4] = (byte) ((frameLength >> 3) & 0xFF);
            frame[5] = (byte) (((frameLength & 0x07) << 5) | 0x1F);
            frame[6] = (byte) 0xFC;
            System.arraycopy(data, 0, frame, 7, size);
            if (batchFrames == 0) {
                batchPts = pts;
            }
            audioBatch.write(frame, 0, frame.length);
            batchFrames++;
            if (batchFrames >= 6 || audioBatch.size() >= 6000) {
                flushAudio(mux);
            }
        } else {
            int streamId = audioType == 0x03 ? 0xC0 : 0xBD;
            mux.writePes(TsMuxer.PID_AUDIO, streamId, data, 0, size, pts, -1, false);
        }
    }

    /** From the AAC configuration: the fixed ADTS header bytes (profile, sampling rate, channels). */
    private static byte[] adtsHeaderBase(MediaFormat f) {
        ByteBuffer csd = f.getByteBuffer("csd-0");
        if (csd == null || csd.remaining() < 2) {
            return null;
        }
        int b0 = csd.get(csd.position()) & 0xFF;
        int b1 = csd.get(csd.position() + 1) & 0xFF;
        int aot = b0 >> 3;
        int rate = ((b0 & 0x07) << 1) | (b1 >> 7);
        int channels = (b1 >> 3) & 0x0F;
        if (aot == 5 || aot == 29) {
            aot = 2;
        }
        if (aot < 1 || aot > 4 || rate >= AAC_RATES.length || channels < 1 || channels > 7) {
            return null;
        }
        byte[] r = new byte[2];
        r[0] = (byte) (((aot - 1) << 6) | (rate << 2) | ((channels >> 2) & 0x01));
        r[1] = (byte) ((channels & 0x03) << 6);
        return r;
    }

    /** SPS/PPS (or VPS/SPS/PPS) from the file's format, with start codes, to put before each key frame. */
    private static byte[] parameterSets(MediaFormat f) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (String key : new String[] {"csd-0", "csd-1"}) {
            ByteBuffer b = f.getByteBuffer(key);
            if (b == null) {
                continue;
            }
            byte[] bytes = new byte[b.remaining()];
            b.duplicate().get(bytes);
            o.write(bytes, 0, bytes.length);
        }
        return o.toByteArray();
    }

    private static boolean startsWithStartCode(byte[] d, int size) {
        return size >= 4 && d[0] == 0 && d[1] == 0 && (d[2] == 1 || (d[2] == 0 && d[3] == 1));
    }

    /** Converts length-prefixed pictures (as stored in MP4/MKV) to start-code form; others pass unchanged. */
    private static void toAnnexB(byte[] d, int size, ByteArrayOutputStream out) {
        if (startsWithStartCode(d, size)) {
            out.write(d, 0, size);
            return;
        }
        int p = 0;
        int mark = out.size();
        while (p + 4 <= size) {
            int n = ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            p += 4;
            if (n <= 0 || p + n > size) {
                break;
            }
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(d, p, n);
            p += n;
        }
        if (p != size) {
            // not length-prefixed after all: send the picture as it came
            byte[] kept = out.toByteArray();
            out.reset();
            out.write(kept, 0, mark);
            out.write(d, 0, size);
        }
    }
}

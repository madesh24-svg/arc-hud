package io.archud.link;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Minimal MPEG transport stream writer: one program with one video and (optionally) one audio stream.
 * TVs are built to play this format as a continuous stream, so the phone can start it at any time.
 */
final class TsMuxer {
    static final int PID_PMT = 0x1000;
    static final int PID_VIDEO = 0x100;
    static final int PID_AUDIO = 0x101;
    /** First timestamp, so the clock reference can run a little ahead of the first picture. */
    static final long BASE = 90_000;

    private final OutputStream out;
    private final int videoType;
    private final int audioType;
    private final int[] counters = new int[4];
    private final byte[] pkt = new byte[188];

    /** videoType: 0x1B H.264, 0x24 H.265. audioType: 0 none, 0x0F AAC, 0x81 AC-3, 0x87 E-AC-3, 0x03 MP3. */
    TsMuxer(OutputStream out, int videoType, int audioType) {
        this.out = out;
        this.videoType = videoType;
        this.audioType = audioType;
    }

    /** Program tables: written at the start and before every key frame so a player can join anywhere. */
    void writeTables() throws IOException {
        byte[] pat = new byte[16];
        pat[0] = 0x00;
        pat[1] = (byte) 0xB0;
        pat[2] = 0x0D;
        pat[3] = 0x00;
        pat[4] = 0x01;
        pat[5] = (byte) 0xC1;
        pat[6] = 0x00;
        pat[7] = 0x00;
        pat[8] = 0x00;
        pat[9] = 0x01;
        pat[10] = (byte) (0xE0 | (PID_PMT >> 8));
        pat[11] = (byte) (PID_PMT & 0xFF);
        putCrc(pat, 12);
        writeSection(0, pat);

        int streams = audioType != 0 ? 2 : 1;
        int length = 9 + 5 * streams + 4;
        byte[] pmt = new byte[3 + length];
        pmt[0] = 0x02;
        pmt[1] = (byte) (0xB0 | ((length >> 8) & 0x0F));
        pmt[2] = (byte) (length & 0xFF);
        pmt[3] = 0x00;
        pmt[4] = 0x01;
        pmt[5] = (byte) 0xC1;
        pmt[6] = 0x00;
        pmt[7] = 0x00;
        pmt[8] = (byte) (0xE0 | (PID_VIDEO >> 8));
        pmt[9] = (byte) (PID_VIDEO & 0xFF);
        pmt[10] = (byte) 0xF0;
        pmt[11] = 0x00;
        int p = 12;
        p = putStream(pmt, p, videoType, PID_VIDEO);
        if (audioType != 0) {
            p = putStream(pmt, p, audioType, PID_AUDIO);
        }
        putCrc(pmt, p);
        writeSection(PID_PMT, pmt);
    }

    private static int putStream(byte[] b, int p, int type, int pid) {
        b[p] = (byte) type;
        b[p + 1] = (byte) (0xE0 | (pid >> 8));
        b[p + 2] = (byte) (pid & 0xFF);
        b[p + 3] = (byte) 0xF0;
        b[p + 4] = 0x00;
        return p + 5;
    }

    private static void putCrc(byte[] b, int at) {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < at; i++) {
            crc ^= (b[i] & 0xFF) << 24;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
        }
        b[at] = (byte) (crc >>> 24);
        b[at + 1] = (byte) (crc >>> 16);
        b[at + 2] = (byte) (crc >>> 8);
        b[at + 3] = (byte) crc;
    }

    private void writeSection(int pid, byte[] section) throws IOException {
        for (int i = 0; i < 188; i++) {
            pkt[i] = (byte) 0xFF;
        }
        pkt[0] = 0x47;
        pkt[1] = (byte) (0x40 | ((pid >> 8) & 0x1F));
        pkt[2] = (byte) (pid & 0xFF);
        pkt[3] = (byte) (0x10 | nextCounter(pid));
        pkt[4] = 0x00;
        System.arraycopy(section, 0, pkt, 5, section.length);
        out.write(pkt, 0, 188);
    }

    private int nextCounter(int pid) {
        int slot = pid == 0 ? 0 : (pid == PID_PMT ? 1 : (pid == PID_VIDEO ? 2 : 3));
        int c = counters[slot];
        counters[slot] = (c + 1) & 0x0F;
        return c;
    }

    /**
     * One access unit (a picture or an audio frame) as a PES packet, cut into transport packets.
     * pcr90 &gt;= 0 puts a clock reference on the first packet; randomAccess marks a key frame.
     */
    void writePes(int pid, int streamId, byte[] data, int off, int len, long pts90,
                  long pcr90, boolean randomAccess) throws IOException {
        byte[] hdr = new byte[14];
        hdr[0] = 0x00;
        hdr[1] = 0x00;
        hdr[2] = 0x01;
        hdr[3] = (byte) streamId;
        int pesLength = pid == PID_VIDEO ? 0 : (len + 8 <= 0xFFFF ? len + 8 : 0);
        hdr[4] = (byte) (pesLength >> 8);
        hdr[5] = (byte) pesLength;
        hdr[6] = (byte) 0x80;
        hdr[7] = (byte) 0x80;
        hdr[8] = 0x05;
        hdr[9] = (byte) (0x21 | (((pts90 >> 30) & 0x07) << 1));
        hdr[10] = (byte) ((pts90 >> 22) & 0xFF);
        hdr[11] = (byte) ((((pts90 >> 15) & 0x7F) << 1) | 1);
        hdr[12] = (byte) ((pts90 >> 7) & 0xFF);
        hdr[13] = (byte) (((pts90 & 0x7F) << 1) | 1);

        int total = hdr.length + len;
        int sent = 0;
        boolean first = true;
        while (sent < total) {
            int remaining = total - sent;
            boolean pcr = first && pcr90 >= 0;
            boolean adaptFirst = first && (pcr || randomAccess);
            int fixed = adaptFirst ? (2 + (pcr ? 6 : 0)) : 0;
            int avail = 184 - fixed;
            int take = Math.min(avail, remaining);
            int stuff = avail - take;
            int a = fixed + stuff;

            pkt[0] = 0x47;
            pkt[1] = (byte) ((first ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
            pkt[2] = (byte) (pid & 0xFF);
            pkt[3] = (byte) (((a > 0 ? 3 : 1) << 4) | nextCounter(pid));
            int p = 4;
            if (a > 0) {
                pkt[p++] = (byte) (a - 1);
                if (a >= 2) {
                    pkt[p++] = (byte) ((adaptFirst && randomAccess ? 0x40 : 0x00) | (pcr ? 0x10 : 0x00));
                    if (pcr) {
                        long base = pcr90 & 0x1FFFFFFFFL;
                        pkt[p++] = (byte) (base >> 25);
                        pkt[p++] = (byte) (base >> 17);
                        pkt[p++] = (byte) (base >> 9);
                        pkt[p++] = (byte) (base >> 1);
                        pkt[p++] = (byte) (((base & 1) << 7) | 0x7E);
                        pkt[p++] = 0x00;
                    }
                    while (p < 4 + a) {
                        pkt[p++] = (byte) 0xFF;
                    }
                }
            }
            int copied = 0;
            while (copied < take) {
                int at = sent + copied;
                if (at < hdr.length) {
                    int n = Math.min(take - copied, hdr.length - at);
                    System.arraycopy(hdr, at, pkt, p, n);
                    p += n;
                    copied += n;
                } else {
                    int n = take - copied;
                    System.arraycopy(data, off + (at - hdr.length), pkt, p, n);
                    p += n;
                    copied += n;
                }
            }
            out.write(pkt, 0, 188);
            sent += take;
            first = false;
        }
    }
}

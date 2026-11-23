package io.archud;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Just enough protobuf wire format for the TV remote protocol: write and read messages by field number. */
final class Pb {
    private Pb() {
    }

    /** Builds one message. Every call returns this builder so fields chain. */
    static final class W {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        W num(int field, long v) {
            varint((long) field << 3);
            varint(v);
            return this;
        }

        W bytes(int field, byte[] b) {
            varint(((long) field << 3) | 2);
            varint(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        W str(int field, String s) {
            return bytes(field, s.getBytes(StandardCharsets.UTF_8));
        }

        W msg(int field, W sub) {
            return bytes(field, sub.toBytes());
        }

        byte[] toBytes() {
            return out.toByteArray();
        }

        private void varint(long value) {
            long v = value;
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
        }
    }

    /** Reads the fields of one message: numbers come back as Long, everything else as byte[]. */
    static Map<Integer, List<Object>> parse(byte[] b) {
        Map<Integer, List<Object>> m = new HashMap<>();
        int[] pos = {0};
        while (pos[0] < b.length) {
            long key = readVarint(b, pos);
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7);
            Object v;
            if (wire == 0) {
                v = Long.valueOf(readVarint(b, pos));
            } else if (wire == 2) {
                int n = (int) readVarint(b, pos);
                if (n < 0 || pos[0] + n > b.length) {
                    throw new IllegalArgumentException("bad length");
                }
                v = Arrays.copyOfRange(b, pos[0], pos[0] + n);
                pos[0] += n;
            } else if (wire == 5 || wire == 1) {
                int n = wire == 5 ? 4 : 8;
                if (pos[0] + n > b.length) {
                    throw new IllegalArgumentException("bad fixed field");
                }
                v = Arrays.copyOfRange(b, pos[0], pos[0] + n);
                pos[0] += n;
            } else {
                throw new IllegalArgumentException("wire type " + wire);
            }
            List<Object> list = m.get(field);
            if (list == null) {
                list = new ArrayList<>();
                m.put(field, list);
            }
            list.add(v);
        }
        return m;
    }

    private static long readVarint(byte[] b, int[] pos) {
        long result = 0;
        int shift = 0;
        while (pos[0] < b.length && shift < 70) {
            int x = b[pos[0]++] & 0xFF;
            result |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("bad varint");
    }

    static long num(Map<Integer, List<Object>> m, int field, long fallback) {
        List<Object> list = m.get(field);
        if (list != null && !list.isEmpty() && list.get(0) instanceof Long) {
            return (Long) list.get(0);
        }
        return fallback;
    }

    static byte[] bytes(Map<Integer, List<Object>> m, int field) {
        List<Object> list = m.get(field);
        if (list != null && !list.isEmpty() && list.get(0) instanceof byte[]) {
            return (byte[]) list.get(0);
        }
        return null;
    }

    static String str(Map<Integer, List<Object>> m, int field) {
        byte[] b = bytes(m, field);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }
}

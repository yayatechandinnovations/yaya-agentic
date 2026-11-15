package com.yayatechandinnovations.yayaagentic.headless;

import java.security.SecureRandom;

/** ULIDs: 48 bits of time and 80 random bits as 26 Crockford base-32 characters, sortable by creation. */
final class Ulids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulids() {}

    static String next(long epochMillis) {
        char[] out = new char[26];
        long time = epochMillis;
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        long hi = 0;
        long lo = 0;
        for (int i = 0; i < 5; i++) hi = (hi << 8) | (random[i] & 0xff);
        for (int i = 5; i < 10; i++) lo = (lo << 8) | (random[i] & 0xff);
        for (int i = 17; i >= 10; i--) {
            out[i] = ALPHABET[(int) (hi & 31)];
            hi >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = ALPHABET[(int) (lo & 31)];
            lo >>>= 5;
        }
        return new String(out);
    }
}

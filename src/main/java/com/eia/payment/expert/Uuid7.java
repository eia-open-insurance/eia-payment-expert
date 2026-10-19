package com.eia.payment.expert;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUIDv7 generator (SPEC 10.1): 48-bit Unix millisecond timestamp,
 * version 7, 12 random bits, variant 10 and 62 random bits. Time-ordered for
 * index locality.
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuid7() {
    }

    public static UUID generate() {
        long unixMillis = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long msb = ((unixMillis & 0xFFFFFFFFFFFFL) << 16) | 0x7000L | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}

package dev.mcp.gateway.rest;

import java.security.SecureRandom;
import java.util.UUID;

/** Request correlation/idempotency only. The backend still owns every returned payment ID. */
final class UuidV7RequestKey {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7RequestKey() {
    }

    static String next() {
        long timestamp = System.currentTimeMillis() & 0x0000FFFFFFFFFFFFL;
        long most = (timestamp << 16) | 0x7000 | RANDOM.nextInt(0x1000);
        long least = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least).toString();
    }
}

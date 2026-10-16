package com.example.repro;

import java.nio.charset.StandardCharsets;

import io.lettuce.core.protocol.ProtocolKeyword;

/**
 * The fix: one stable keyword per command. Enum {@code toString()} returns the constant name, e.g. {@code PING}.
 */
public enum ReproCommand implements ProtocolKeyword {

    PING;

    private final byte[] bytes = name().getBytes(StandardCharsets.US_ASCII);

    @Override
    public byte[] getBytes() {
        return bytes;
    }

}

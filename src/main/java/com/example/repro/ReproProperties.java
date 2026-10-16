package com.example.repro;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param keyword {@code anonymous} creates a new {@code ProtocolKeyword} per call without overriding {@code toString()};
 *        {@code stable} reuses an enum constant.
 * @param rate commands per second.
 * @param stopAtHeapRatio stop sending commands once used heap exceeds this fraction of the maximum, so a JFR
 *        recording can still be written; {@code 0} disables the guard and lets the JVM run out of memory.
 */
@ConfigurationProperties("repro")
public record ReproProperties(KeywordMode keyword, int rate, double stopAtHeapRatio) {

    public enum KeywordMode {
        ANONYMOUS, STABLE
    }

}

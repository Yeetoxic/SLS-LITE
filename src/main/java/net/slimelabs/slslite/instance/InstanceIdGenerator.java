package net.slimelabs.slslite.instance;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

public final class InstanceIdGenerator {

    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final Pattern VALID_BLUEPRINT_ID =
            Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final int RANDOM_LENGTH = 10;

    private final RandomGenerator random;

    public InstanceIdGenerator() {
        this(new SecureRandom());
    }

    InstanceIdGenerator(RandomGenerator random) {
        this.random = random;
    }

    public String generate(String blueprintId) {
        if (blueprintId == null || !VALID_BLUEPRINT_ID.matcher(blueprintId).matches()) {
            throw new IllegalArgumentException("Invalid blueprint ID: " + blueprintId);
        }

        StringBuilder id = new StringBuilder(blueprintId).append('-');
        for (int index = 0; index < RANDOM_LENGTH; index++) {
            id.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return id.toString();
    }
}

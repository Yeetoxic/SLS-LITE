package net.slimelabs.slslite.security;

import com.velocitypowered.api.proxy.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Supplier;

public final class AdminClaimService {

    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
            .toCharArray();

    private final AdministratorStore administrators;
    private final boolean onlineMode;
    private final boolean allowInsecureOfflineAdministrators;
    private final Duration expiry;
    private final Clock clock;
    private final Supplier<String> codeGenerator;
    private ClaimCode activeCode;

    public AdminClaimService(
            AdministratorStore administrators,
            boolean onlineMode,
            boolean allowInsecureOfflineAdministrators,
            Duration expiry
    ) {
        SecureRandom random = new SecureRandom();
        this.administrators = administrators;
        this.onlineMode = onlineMode;
        this.allowInsecureOfflineAdministrators = allowInsecureOfflineAdministrators;
        this.expiry = expiry;
        this.clock = Clock.systemUTC();
        this.codeGenerator = () -> generateCode(random);
    }

    AdminClaimService(
            AdministratorStore administrators,
            boolean onlineMode,
            boolean allowInsecureOfflineAdministrators,
            Duration expiry,
            Clock clock,
            Supplier<String> codeGenerator
    ) {
        this.administrators = administrators;
        this.onlineMode = onlineMode;
        this.allowInsecureOfflineAdministrators = allowInsecureOfflineAdministrators;
        this.expiry = expiry;
        this.clock = clock;
        this.codeGenerator = codeGenerator;
    }

    public synchronized String issueCode() {
        requireSecureIdentity();
        String code = normalize(codeGenerator.get());
        activeCode = new ClaimCode(code, clock.instant().plus(expiry));
        return format(code);
    }

    public synchronized ClaimResult claim(Player player, String submittedCode)
            throws IOException {
        if (administrators.contains(player.getUniqueId())) {
            return ClaimResult.ALREADY_ADMINISTRATOR;
        }
        if (!onlineMode && !allowInsecureOfflineAdministrators) {
            return ClaimResult.OFFLINE_MODE_BLOCKED;
        }
        if (activeCode == null) {
            return ClaimResult.NO_ACTIVE_CODE;
        }
        if (!clock.instant().isBefore(activeCode.expiresAt())) {
            activeCode = null;
            return ClaimResult.EXPIRED;
        }
        String normalized = normalize(submittedCode);
        if (!MessageDigest.isEqual(
                activeCode.value().getBytes(StandardCharsets.US_ASCII),
                normalized.getBytes(StandardCharsets.US_ASCII)
        )) {
            return ClaimResult.INVALID;
        }
        administrators.add(player.getUniqueId(), player.getUsername());
        activeCode = null;
        return ClaimResult.CLAIMED;
    }

    public void requireSecureIdentity() {
        if (!onlineMode && !allowInsecureOfflineAdministrators) {
            throw new InsecureOfflineModeException();
        }
    }

    private static String generateCode(SecureRandom random) {
        StringBuilder code = new StringBuilder(8);
        for (int index = 0; index < 8; index++) {
            code.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        }
        return code.toString();
    }

    private static String normalize(String code) {
        return code.replace("-", "").trim().toUpperCase(Locale.ROOT);
    }

    private static String format(String code) {
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    private record ClaimCode(String value, Instant expiresAt) {
    }

    public enum ClaimResult {
        CLAIMED,
        INVALID,
        EXPIRED,
        NO_ACTIVE_CODE,
        ALREADY_ADMINISTRATOR,
        OFFLINE_MODE_BLOCKED
    }

    public static final class InsecureOfflineModeException extends IllegalStateException {
        public InsecureOfflineModeException() {
            super("Persistent in-game administrators are disabled while Velocity "
                    + "is in offline mode");
        }
    }
}

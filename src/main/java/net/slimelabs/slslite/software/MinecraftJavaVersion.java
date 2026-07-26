package net.slimelabs.slslite.software;

public final class MinecraftJavaVersion {

    private MinecraftJavaVersion() {
    }

    public static int requiredMajor(
            SoftwareConfigurator configurator,
            String version
    ) {
        int[] parts = numericParts(version);
        if (parts[0] >= 26) {
            return 25;
        }
        if (parts[0] != 1) {
            return 21;
        }
        int minor = parts[1];
        int patch = parts[2];
        if (configurator == SoftwareConfigurator.PAPER) {
            if (minor >= 20) {
                return 21;
            }
            if (minor >= 17) {
                return 17;
            }
            if (minor == 16 && patch >= 5) {
                return 16;
            }
            if (minor >= 12) {
                return 11;
            }
            return 8;
        }
        if (minor > 20 || minor == 20 && patch >= 5) {
            return 21;
        }
        if (minor >= 18) {
            return 17;
        }
        if (minor == 17) {
            return 16;
        }
        return 8;
    }

    private static int[] numericParts(String version) {
        String[] values = version.split("\\.");
        int[] result = new int[]{0, 0, 0};
        for (int index = 0; index < Math.min(values.length, 3); index++) {
            String digits = values[index].replaceAll("[^0-9].*$", "");
            if (!digits.isEmpty()) {
                result[index] = Integer.parseInt(digits);
            }
        }
        return result;
    }
}

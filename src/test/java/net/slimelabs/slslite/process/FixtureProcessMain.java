package net.slimelabs.slslite.process;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class FixtureProcessMain {

    private FixtureProcessMain() {
    }

    public static void main(String[] arguments) throws Exception {
        String mode = arguments[0];
        switch (mode) {
            case "ready-stop" -> {
                System.out.println("FIXTURE READY");
                System.out.flush();
                try (BufferedReader input = new BufferedReader(new InputStreamReader(
                        System.in,
                        StandardCharsets.UTF_8
                ))) {
                    String line;
                    while ((line = input.readLine()) != null && !"stop".equals(line)) {
                        System.out.println("RECEIVED:" + line);
                        System.out.flush();
                    }
                }
            }
            case "ignore-stop" -> {
                System.out.println("FIXTURE READY");
                System.out.flush();
                Thread.sleep(30_000);
            }
            case "silent" -> Thread.sleep(30_000);
            case "crash" -> System.exit(7);
            default -> throw new IllegalArgumentException("Unknown fixture mode: " + mode);
        }
    }
}

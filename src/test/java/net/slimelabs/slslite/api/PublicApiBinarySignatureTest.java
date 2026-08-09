package net.slimelabs.slslite.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicApiBinarySignatureTest {

  private static final String BASELINE_RESOURCE = "/api/public-api-1.2.sha256";

  @Test
  void publicJvmDescriptorsMatchTheCheckedOnePointTwoBaseline() throws Exception {
    Path apiClasses =
        Path.of(System.getProperty("user.dir")).resolve("target/classes/net/slimelabs/slslite/api");
    assertTrue(Files.isDirectory(apiClasses), "Compiled public API classes are unavailable");

    List<String> signatures = new ArrayList<>();
    try (var paths = Files.walk(apiClasses)) {
      for (Path path :
          paths
              .filter(Files::isRegularFile)
              .filter(candidate -> candidate.getFileName().toString().endsWith(".class"))
              .filter(candidate -> !apiClasses.relativize(candidate).startsWith("internal"))
              .sorted(Comparator.comparing(Path::toString))
              .toList()) {
        signatures.addAll(readSignatures(path));
      }
    }
    signatures.sort(String::compareTo);
    String canonical = String.join("\n", signatures) + "\n";
    String actual = sha256(canonical);
    Path report =
        Path.of(System.getProperty("user.dir")).resolve("target/api-signature/public-api-1.2.txt");
    Files.createDirectories(report.getParent());
    Files.writeString(report, canonical, StandardCharsets.UTF_8);
    String expected;
    try (var input = PublicApiBinarySignatureTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
      if (input == null) {
        throw new AssertionError("Missing API binary signature baseline " + BASELINE_RESOURCE);
      }
      expected = new String(input.readAllBytes(), StandardCharsets.US_ASCII).strip();
    }

    assertEquals(
        expected,
        actual,
        () ->
            "Public API JVM descriptors changed. Review binary compatibility and, only for an "
                + "accepted contract change, replace the baseline with: "
                + actual
                + ". Canonical descriptors were written to "
                + report);
  }

  private static List<String> readSignatures(Path classFile) throws IOException {
    try (DataInputStream input =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(classFile)))) {
      if (input.readInt() != 0xCAFEBABE) {
        throw new IOException("Invalid class file: " + classFile);
      }
      input.readUnsignedShort();
      input.readUnsignedShort();
      Object[] constants = readConstantPool(input);
      int classAccess = input.readUnsignedShort();
      int thisClass = input.readUnsignedShort();
      int superClass = input.readUnsignedShort();
      String className = className(constants, thisClass);
      int interfaceCount = input.readUnsignedShort();
      List<String> interfaces = new ArrayList<>(interfaceCount);
      for (int index = 0; index < interfaceCount; index++) {
        interfaces.add(className(constants, input.readUnsignedShort()));
      }
      interfaces.sort(String::compareTo);

      boolean exported = visible(classAccess);
      List<String> signatures = new ArrayList<>();
      if (exported) {
        signatures.add(
            "C "
                + className
                + " "
                + hex(classAccess)
                + " "
                + (superClass == 0 ? "-" : className(constants, superClass))
                + " "
                + String.join(",", interfaces));
      }
      readMembers(input, constants, className, "F", exported, signatures);
      readMembers(input, constants, className, "M", exported, signatures);
      skipAttributes(input);
      return signatures;
    }
  }

  private static Object[] readConstantPool(DataInputStream input) throws IOException {
    Object[] constants = new Object[input.readUnsignedShort()];
    for (int index = 1; index < constants.length; index++) {
      int tag = input.readUnsignedByte();
      switch (tag) {
        case 1 -> constants[index] = input.readUTF();
        case 3, 4 -> input.readInt();
        case 5, 6 -> {
          input.readLong();
          index++;
        }
        case 7 -> constants[index] = new ClassReference(input.readUnsignedShort());
        case 8, 16, 19, 20 -> input.readUnsignedShort();
        case 9, 10, 11, 12, 17, 18 -> {
          input.readUnsignedShort();
          input.readUnsignedShort();
        }
        case 15 -> {
          input.readUnsignedByte();
          input.readUnsignedShort();
        }
        default -> throw new IOException("Unsupported class constant-pool tag " + tag);
      }
    }
    return constants;
  }

  private static void readMembers(
      DataInputStream input,
      Object[] constants,
      String className,
      String kind,
      boolean exportedClass,
      List<String> signatures)
      throws IOException {
    int count = input.readUnsignedShort();
    for (int index = 0; index < count; index++) {
      int access = input.readUnsignedShort();
      String name = utf8(constants, input.readUnsignedShort());
      String descriptor = utf8(constants, input.readUnsignedShort());
      if (exportedClass && visible(access)) {
        signatures.add(kind + " " + className + " " + name + " " + descriptor + " " + hex(access));
      }
      skipAttributes(input);
    }
  }

  private static void skipAttributes(DataInputStream input) throws IOException {
    int count = input.readUnsignedShort();
    for (int index = 0; index < count; index++) {
      input.readUnsignedShort();
      int length = input.readInt();
      if (length < 0) {
        throw new IOException("Class attribute exceeds the supported size");
      }
      input.skipNBytes(length);
    }
  }

  private static String className(Object[] constants, int index) throws IOException {
    Object constant = constants[index];
    if (!(constant instanceof ClassReference reference)) {
      throw new IOException("Invalid class constant-pool reference " + index);
    }
    return utf8(constants, reference.nameIndex()).replace('/', '.');
  }

  private static String utf8(Object[] constants, int index) throws IOException {
    Object constant = constants[index];
    if (!(constant instanceof String value)) {
      throw new IOException("Invalid UTF-8 constant-pool reference " + index);
    }
    return value;
  }

  private static boolean visible(int access) {
    return (access & (0x0001 | 0x0004)) != 0;
  }

  private static String hex(int access) {
    return String.format("%04x", access);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
          .toUpperCase(java.util.Locale.ROOT);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record ClassReference(int nameIndex) {}
}

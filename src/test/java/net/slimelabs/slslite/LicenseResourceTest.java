package net.slimelabs.slslite;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class LicenseResourceTest {

  @Test
  void packagesProjectLicense() {
    assertNotNull(
        getClass().getClassLoader().getResource("META-INF/licenses/LICENSE"),
        "The distributed plugin must contain its AGPL license");
  }
}

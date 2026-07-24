package net.slimelabs.slslite.instance;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceIdGeneratorTest {

    @Test
    void createsSlsCompatibleCompositeId() {
        InstanceIdGenerator generator = new InstanceIdGenerator(new Random(42));

        String first = generator.generate("block-hunt");
        String second = generator.generate("block-hunt");

        assertTrue(first.matches("block-hunt\\.[0-9abcdefhkmnorsuvwxz]{6}"));
        assertTrue(InstanceIdGenerator.isValid(first));
        assertNotEquals(first, second);
    }

    @Test
    void rejectsUnsafeBlueprintId() {
        InstanceIdGenerator generator = new InstanceIdGenerator(new Random(42));

        assertThrows(IllegalArgumentException.class, () -> generator.generate("../escape"));
    }
}

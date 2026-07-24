package net.slimelabs.slslite.resource;

import java.util.HashMap;
import java.util.Map;

public final class ResourceBudget {

    private final int totalMemoryMiB;
    private final Map<String, Integer> reservations = new HashMap<>();
    private int reservedMemoryMiB;

    public ResourceBudget(int totalMemoryMiB) {
        if (totalMemoryMiB <= 0) {
            throw new IllegalArgumentException("totalMemoryMiB must be positive");
        }
        this.totalMemoryMiB = totalMemoryMiB;
    }

    public synchronized boolean tryReserve(String instanceId, int memoryMiB) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        if (memoryMiB <= 0) {
            throw new IllegalArgumentException("memoryMiB must be positive");
        }
        if (reservations.containsKey(instanceId)) {
            throw new IllegalStateException("Instance already has a reservation: " + instanceId);
        }
        if (memoryMiB > availableMemoryMiB()) {
            return false;
        }

        reservations.put(instanceId, memoryMiB);
        reservedMemoryMiB += memoryMiB;
        return true;
    }

    public synchronized int release(String instanceId) {
        Integer released = reservations.remove(instanceId);
        if (released == null) {
            return 0;
        }
        reservedMemoryMiB -= released;
        return released;
    }

    public int totalMemoryMiB() {
        return totalMemoryMiB;
    }

    public synchronized int reservedMemoryMiB() {
        return reservedMemoryMiB;
    }

    public synchronized int availableMemoryMiB() {
        return totalMemoryMiB - reservedMemoryMiB;
    }

    public synchronized Map<String, Integer> reservations() {
        return Map.copyOf(reservations);
    }
}

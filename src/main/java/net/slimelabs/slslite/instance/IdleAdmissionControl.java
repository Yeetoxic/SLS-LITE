package net.slimelabs.slslite.instance;

public interface IdleAdmissionControl {

    boolean hasPendingJoin(String instanceId);

    boolean tryDrain(String instanceId);

    void cancelDrain(String instanceId);
}

package net.slimelabs.slslite.velocity;

import java.net.InetSocketAddress;

public interface BackendRegistry {

    void register(String name, InetSocketAddress address);

    void unregister(String name);
}

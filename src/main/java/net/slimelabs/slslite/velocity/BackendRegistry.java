package net.slimelabs.slslite.velocity;

import java.net.InetSocketAddress;

public interface BackendRegistry {

    void register(String name, InetSocketAddress address);

    default void register(
            String name,
            InetSocketAddress address,
            int protocol
    ) {
        register(name, address);
    }

    void unregister(String name);
}

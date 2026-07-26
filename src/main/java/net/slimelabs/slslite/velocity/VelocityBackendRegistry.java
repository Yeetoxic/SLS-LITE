package net.slimelabs.slslite.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

public final class VelocityBackendRegistry implements BackendRegistry {

    private final ProxyServer proxy;
    private final BackendProtocolSynchronizer protocols;
    private final Map<String, ServerInfo> registrations = new HashMap<>();

    public VelocityBackendRegistry(ProxyServer proxy) {
        this(proxy, BackendProtocolSynchronizer.disabled());
    }

    public VelocityBackendRegistry(
            ProxyServer proxy,
            BackendProtocolSynchronizer protocols
    ) {
        this.proxy = proxy;
        this.protocols = protocols;
    }

    @Override
    public synchronized void register(String name, InetSocketAddress address) {
        register(name, address, OptionalInt.empty());
    }

    @Override
    public synchronized void register(
            String name,
            InetSocketAddress address,
            int protocol
    ) {
        register(
                name,
                address,
                protocol > 0 ? OptionalInt.of(protocol) : OptionalInt.empty()
        );
    }

    private void register(
            String name,
            InetSocketAddress address,
            OptionalInt protocol
    ) {
        if (registrations.containsKey(name) || proxy.getServer(name).isPresent()) {
            throw new IllegalStateException("Velocity server name is already registered: " + name);
        }
        ServerInfo serverInfo = new ServerInfo(name, address);
        RegisteredServer registeredServer = proxy.registerServer(serverInfo);
        try {
            protocols.synchronize(name, registeredServer, protocol);
        } catch (RuntimeException exception) {
            proxy.unregisterServer(serverInfo);
            throw exception;
        }
        registrations.put(name, serverInfo);
    }

    @Override
    public synchronized void unregister(String name) {
        ServerInfo serverInfo = registrations.remove(name);
        if (serverInfo != null) {
            protocols.remove(name);
            proxy.unregisterServer(serverInfo);
        }
    }
}

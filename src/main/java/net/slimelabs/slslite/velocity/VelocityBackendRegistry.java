package net.slimelabs.slslite.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

public final class VelocityBackendRegistry implements BackendRegistry {

    private final ProxyServer proxy;
    private final Map<String, ServerInfo> registrations = new HashMap<>();

    public VelocityBackendRegistry(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public synchronized void register(String name, InetSocketAddress address) {
        if (registrations.containsKey(name) || proxy.getServer(name).isPresent()) {
            throw new IllegalStateException("Velocity server name is already registered: " + name);
        }
        ServerInfo serverInfo = new ServerInfo(name, address);
        proxy.registerServer(serverInfo);
        registrations.put(name, serverInfo);
    }

    @Override
    public synchronized void unregister(String name) {
        ServerInfo serverInfo = registrations.remove(name);
        if (serverInfo != null) {
            proxy.unregisterServer(serverInfo);
        }
    }
}

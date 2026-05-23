package dev.bluespice.fabric;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

final class FabricLifecycleHooks {
    private static final String EVENTS_CLASS =
            "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents";
    private static final String SERVER_STOPPED_CLASS =
            "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStopped";

    private FabricLifecycleHooks() {}

    static void registerShutdownHook() {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> eventsClass = Class.forName(EVENTS_CLASS, true, loader);
            Class<?> listenerClass = Class.forName(SERVER_STOPPED_CLASS, true, loader);
            Object serverStoppedEvent = eventsClass.getField("SERVER_STOPPED").get(null);
            Object listener = Proxy.newProxyInstance(
                    loader,
                    new Class<?>[] {listenerClass},
                    (proxy, method, args) -> {
                        if ("onServerStopped".equals(method.getName())) {
                            FabricEngineProvider.shutdown();
                        }
                        return null;
                    });
            registerMethod(serverStoppedEvent).invoke(serverStoppedEvent, listener);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to register Fabric shutdown hook", e);
        }
    }

    private static Method registerMethod(Object event) throws NoSuchMethodException {
        for (Method method : event.getClass().getMethods()) {
            if ("register".equals(method.getName()) && method.getParameterCount() == 1) {
                return method;
            }
        }
        throw new NoSuchMethodException(event.getClass().getName() + ".register");
    }
}

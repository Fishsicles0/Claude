package de.maxhenkel.voicechat.api.events;

import java.util.function.Consumer;

public interface EventRegistration {
    <T extends Event> void registerEvent(Class<T> type, Consumer<T> listener, int priority);
    default <T extends Event> void registerEvent(Class<T> type, Consumer<T> listener) { registerEvent(type, listener, 0); }
}

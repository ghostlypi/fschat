package dev.fschat.daemon.sync;

/**
 * Receives transcript updates for delivery to a viewing client (the local TCP
 * server in front of Vim). A no-op default is used when nothing is watching.
 */
@FunctionalInterface
public interface UpdateSink {

    UpdateSink NONE = update -> {
    };

    void deliver(ViewUpdate update);
}

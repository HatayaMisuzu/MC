package com.mccompanion.runtime.session;

public interface SessionPeer {
    String id();

    String remoteAddress();

    boolean isOpen();

    void send(String text);

    void close(int code, String reason);
}

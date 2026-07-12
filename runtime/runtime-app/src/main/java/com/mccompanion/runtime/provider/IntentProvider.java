package com.mccompanion.runtime.provider;

import com.mccompanion.runtime.intent.Intent;

public interface IntentProvider extends AutoCloseable {
    Intent parse(String userText) throws ProviderException;

    @Override
    default void close() {
    }
}

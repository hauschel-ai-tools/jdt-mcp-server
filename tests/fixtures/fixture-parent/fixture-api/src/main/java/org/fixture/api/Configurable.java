package org.fixture.api;

public interface Configurable {
    void configure(String key, String value);
    String getConfig(String key);
}

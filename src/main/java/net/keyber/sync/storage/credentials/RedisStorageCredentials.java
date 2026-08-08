package net.keyber.sync.storage.credentials;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RedisStorageCredentials {

    @Builder.Default
    private final String address = "localhost";

    @Builder.Default
    private final int port = 6379;

    private final String username;

    private final String password;

    @Builder.Default
    private final String channel = "playersync";

    @Builder.Default
    private final int minIdle = 1;

    @Builder.Default
    private final int maxTotal = 16;
}

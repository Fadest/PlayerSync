package net.keyber.sync.storage.credentials;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MongoStorageCredentials {

    @Builder.Default
    private final String uri = "mongodb://localhost:27017";

    @Builder.Default
    private final String database = "playersync";

    @Builder.Default
    private final String collection = "players";

    @Builder.Default
    private final int threadPoolSize = 4;

    public String describe() {
        // mongodb://user:pass@host -> mongodb://***@host
        return uri.replaceAll("://[^@/]*@", "://***@");
    }
}

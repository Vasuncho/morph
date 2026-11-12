package com.vasuncho.morph;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a username to its canonical UUID, name and signed textures via the Mojang API.
 * Runs entirely off the main thread and never touches Bukkit state.
 */
public final class ProfileFetcher {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String NAME_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/%s?unsigned=false";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final MorphManager manager;

    public ProfileFetcher(MorphManager manager) {
        this.manager = manager;
    }

    /** Never completes exceptionally; every failure is mapped to a {@link LookupResult}. */
    public CompletableFuture<LookupResult> lookup(String name) {
        LookupResult.Found cached = manager.cachedLookup(name);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return get(NAME_URL + name)
                .thenCompose(response -> switch (response.statusCode()) {
                    case 200 -> {
                        String id = JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString();
                        yield get(PROFILE_URL.formatted(id)).thenApply(ProfileFetcher::parseProfile);
                    }
                    case 204, 404 -> CompletableFuture.<LookupResult>completedFuture(new LookupResult.NotFound());
                    case 429 -> CompletableFuture.<LookupResult>completedFuture(new LookupResult.RateLimited());
                    default -> CompletableFuture.<LookupResult>completedFuture(
                            new LookupResult.Failed("Mojang returned HTTP " + response.statusCode()));
                })
                .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(ProfileFetcher::mapError)
                .thenApply(result -> {
                    if (result instanceof LookupResult.Found found) {
                        manager.cacheLookup(name, found);
                    }
                    return result;
                });
    }

    private CompletableFuture<HttpResponse<String>> get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "Morph-Plugin")
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private static LookupResult parseProfile(HttpResponse<String> response) {
        return switch (response.statusCode()) {
            case 200 -> {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                UUID uuid = undashed(json.get("id").getAsString());
                String canonicalName = json.get("name").getAsString();
                ProfileProperty textures = null;
                if (json.has("properties")) {
                    for (JsonElement element : json.getAsJsonArray("properties")) {
                        JsonObject property = element.getAsJsonObject();
                        if ("textures".equals(property.get("name").getAsString())) {
                            String signature = property.has("signature") ? property.get("signature").getAsString() : null;
                            textures = new ProfileProperty("textures", property.get("value").getAsString(), signature);
                        }
                    }
                }
                yield new LookupResult.Found(uuid, canonicalName, textures);
            }
            case 204, 404 -> new LookupResult.NotFound();
            case 429 -> new LookupResult.RateLimited();
            default -> new LookupResult.Failed("Mojang session server returned HTTP " + response.statusCode());
        };
    }

    private static LookupResult mapError(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) {
            return new LookupResult.TimedOut();
        }
        String message = cause.getMessage();
        return new LookupResult.Failed(cause.getClass().getSimpleName() + (message == null ? "" : ": " + message));
    }

    private static UUID undashed(String hex) {
        return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16, 32), 16));
    }

    /** Outcome of an async Mojang profile lookup. */
    public sealed interface LookupResult {

        /** @param textures the signed "textures" property, or null for a default Steve/Alex skin */
        record Found(UUID uuid, String name, @Nullable ProfileProperty textures) implements LookupResult {
        }

        record NotFound() implements LookupResult {
        }

        record RateLimited() implements LookupResult {
        }

        record TimedOut() implements LookupResult {
        }

        record Failed(String reason) implements LookupResult {
        }
    }
}

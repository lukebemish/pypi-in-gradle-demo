package dev.lukebemish.pypigradle;

import com.squareup.moshi.Moshi;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public record PyPIIndexMetadata(Map<String, List<WhoCares>> releases) {
    public record WhoCares() {}

    public static @Nullable PyPIIndexMetadata fromJson(InputStream input) {
        var moshi = new Moshi.Builder().build();
        var adapter = moshi.adapter(PyPIIndexMetadata.class);
        try {
            var json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return adapter.fromJson(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

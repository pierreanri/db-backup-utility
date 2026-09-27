/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import io.github.pierreanri.dbbackup.util.Mappers;
import io.github.pierreanri.dbbackup.util.PathUtils;

/**
 * Locates, reads, interpolates and validates the YAML configuration file.
 *
 * <p>String values may reference environment variables with {@code ${NAME}} or
 * {@code ${NAME:-default}}; {@code $${...}} produces a literal {@code ${...}}.
 */
public final class ConfigLoader {

    /** Environment variable pointing to the configuration file. */
    public static final String ENV_CONFIG = "DBBACKUP_CONFIG";

    private static final Pattern VARIABLE = Pattern.compile("\\$(\\$)?\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");

    private final Function<String, String> env;
    private final Path workingDir;
    private final Path appHome;

    public ConfigLoader() {
        this(System::getenv, Path.of("").toAbsolutePath(), PathUtils.appHome());
    }

    public ConfigLoader(Function<String, String> env, Path workingDir, Path appHome) {
        this.env = Objects.requireNonNull(env);
        this.workingDir = workingDir;
        this.appHome = appHome;
    }

    /**
     * Loads the configuration from {@code explicitPath}, or from the first file found in
     * {@code $DBBACKUP_CONFIG}, {@code ./dbbackup.yml} and {@code ~/.dbbackup/config.yml}.
     * Returns an empty configuration when no file exists.
     */
    public LoadedConfig load(Path explicitPath) {
        Optional<Path> path = locate(explicitPath);
        if (path.isEmpty()) {
            return new LoadedConfig(AppConfig.empty(), null, List.of());
        }
        return loadFile(path.get());
    }

    public Optional<Path> locate(Path explicitPath) {
        if (explicitPath != null) {
            return Optional.of(requireExisting(explicitPath, "--config"));
        }
        String fromEnv = env.apply(ENV_CONFIG);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Optional.of(requireExisting(PathUtils.expand(fromEnv), ENV_CONFIG));
        }
        List<Path> candidates = List.of(
                workingDir.resolve("dbbackup.yml"),
                workingDir.resolve("dbbackup.yaml"),
                appHome.resolve("config.yml"),
                appHome.resolve("config.yaml"));
        return candidates.stream().filter(Files::isRegularFile).findFirst();
    }

    /** Default location used by {@code dbbackup config init}. */
    public Path defaultLocation() {
        return appHome.resolve("config.yml");
    }

    public LoadedConfig loadFile(Path path) {
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("Cannot read config file " + path + ": " + e.getMessage(), e);
        }
        return parse(content, path);
    }

    public LoadedConfig parse(String yaml, Path source) {
        String origin = source == null ? "config" : source.toString();
        if (yaml == null || yaml.isBlank()) {
            return new LoadedConfig(AppConfig.empty(), source, List.of());
        }
        JsonNode tree;
        try {
            tree = Mappers.yaml().readTree(yaml);
        } catch (JsonProcessingException e) {
            throw new ConfigException("Invalid YAML in " + origin + ": " + describe(e), e);
        }
        if (tree == null || tree.isMissingNode() || tree.isNull()) {
            return new LoadedConfig(AppConfig.empty(), source, List.of());
        }
        if (!tree.isObject()) {
            throw new ConfigException("Invalid config " + origin + ": the top level must be a mapping");
        }
        List<String> warnings = new ArrayList<>();
        JsonNode interpolated = interpolate(tree, "", warnings);

        AppConfig config;
        try {
            config = Mappers.yaml().treeToValue(interpolated, AppConfig.class);
        } catch (JsonProcessingException e) {
            throw new ConfigException("Invalid config " + origin + ": " + describe(e), e);
        }
        List<String> errors = ConfigValidator.validate(config);
        if (!errors.isEmpty()) {
            throw new ConfigException("Invalid config " + origin + ":"
                    + errors.stream().map(err -> System.lineSeparator() + "  - " + err).collect(Collectors.joining()));
        }
        return new LoadedConfig(config, source, warnings);
    }

    /** Expands environment variable references in a single string. */
    public String interpolate(String value, String location, List<String> warnings) {
        Matcher matcher = VARIABLE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) != null) {
                replacement = matcher.group().substring(1);
            } else {
                String name = matcher.group(2);
                String defaultValue = matcher.group(3);
                String resolved = env.apply(name);
                if (resolved == null || (resolved.isEmpty() && defaultValue != null)) {
                    if (defaultValue != null) {
                        resolved = defaultValue;
                    } else {
                        warnings.add("Environment variable " + name + " is not set (used in " + location + ")");
                        resolved = "";
                    }
                }
                replacement = resolved;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private JsonNode interpolate(JsonNode node, String location, List<String> warnings) {
        if (node instanceof ObjectNode object) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(object.properties());
            for (Map.Entry<String, JsonNode> entry : entries) {
                String childLocation = location.isEmpty() ? entry.getKey() : location + "." + entry.getKey();
                object.set(entry.getKey(), interpolate(entry.getValue(), childLocation, warnings));
            }
            return object;
        }
        if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, interpolate(array.get(i), location + "[" + i + "]", warnings));
            }
            return array;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(interpolate(node.textValue(), location, warnings));
        }
        return node;
    }

    private static Path requireExisting(Path path, String origin) {
        if (!Files.isRegularFile(path)) {
            throw new ConfigException("Config file not found: " + path + " (from " + origin + ")");
        }
        return path;
    }

    private static String describe(JsonProcessingException e) {
        String location = e.getLocation() == null ? "" : " (line " + e.getLocation().getLineNr() + ")";
        if (e instanceof JsonMappingException mapping) {
            String path = mapping.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining("."))
                    .replace(".[", "[");
            String where = path.isEmpty() ? "" : " at '" + path + "'";
            if (e instanceof UnrecognizedPropertyException unknown) {
                return "unknown property '" + unknown.getPropertyName() + "'" + where + location;
            }
            if (e instanceof InvalidTypeIdException) {
                return "missing or unsupported storage type" + where
                        + " (supported: local, s3, gcs, azure)" + location;
            }
            Throwable root = rootCause(e);
            String message = root instanceof IllegalArgumentException ? root.getMessage() : mapping.getOriginalMessage();
            return firstLine(message) + where + location;
        }
        return firstLine(e.getOriginalMessage()) + location;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}

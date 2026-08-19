package com.autospec.service;

import com.autospec.entity.Artifact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class GeneratedBundleVerificationService {
    private static final Set<String> REQUIRED_FILES = Set.of(
            "backend/pom.xml",
            "backend/src/main/java/com/generated/Application.java",
            "backend/src/main/java/com/generated/GeneratedContractController.java",
            "backend/src/test/java/com/generated/GeneratedContractSmokeTest.java",
            "backend/src/test/resources/autospec-acceptance-tests.json",
            "backend/src/main/resources/schema.sql",
            "frontend/package.json",
            "frontend/src/main.tsx",
            "frontend/src/App.tsx",
            "ACCEPTANCE_TESTS.json",
            "AUTOSPEC_MANIFEST.json"
    );

    private final ObjectMapper objectMapper;

    public GeneratedBundleVerificationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public VerificationReport verify(byte[] zipBytes, List<Artifact> artifacts) {
        Map<String, String> files = unzip(zipBytes);
        List<Check> checks = new ArrayList<>();
        Set<String> missing = new java.util.TreeSet<>(REQUIRED_FILES);
        missing.removeAll(files.keySet());
        checks.add(new Check("REQUIRED_FILES", missing.isEmpty(),
                missing.isEmpty() ? "All required bundle files are present" : "Missing: " + String.join(", ", missing)));

        String manifest = files.getOrDefault("AUTOSPEC_MANIFEST.json", "{}");
        checks.add(validateManifest(manifest, artifacts));
        checks.add(validateAcceptanceTests(files.get("ACCEPTANCE_TESTS.json")));
        checks.add(validateFrontend(files, artifacts));
        checks.add(validateSecrets(files));
        checks.add(compileAndSmokeTestBackend(files));

        boolean passed = checks.stream().allMatch(Check::passed);
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("gate", "BUILD_DELIVERY_GATE");
        evidence.put("status", passed ? "PASSED" : "BLOCKED");
        evidence.put("bundle_sha256", hash(zipBytes));
        evidence.put("manifest_sha256", hash(manifest.getBytes(StandardCharsets.UTF_8)));
        ArrayNode checkValues = evidence.putArray("checks");
        for (Check check : checks) {
            ObjectNode value = checkValues.addObject();
            value.put("name", check.name());
            value.put("passed", check.passed());
            value.put("evidence", check.evidence());
        }
        return new VerificationReport(
                passed,
                hash(manifest.getBytes(StandardCharsets.UTF_8)),
                evidence.toString()
        );
    }

    private Check validateManifest(String content, List<Artifact> artifacts) {
        try {
            JsonNode manifest = objectMapper.readTree(content);
            JsonNode manifestArtifacts = manifest.path("artifacts");
            long selectedArtifactCount = artifacts.stream()
                    .map(Artifact::getType)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .count();
            boolean hasHashes = manifestArtifacts.isArray()
                    && manifestArtifacts.size() == selectedArtifactCount
                    && java.util.stream.StreamSupport.stream(manifestArtifacts.spliterator(), false)
                    .allMatch(value -> value.path("artifact_id").canConvertToLong()
                            && value.path("content_hash").asText("").length() == 64);
            return new Check(
                    "MANIFEST_INTEGRITY",
                    hasHashes,
                    hasHashes ? "Every selected artifact is pinned by id and SHA-256" : "Artifact ids or hashes are incomplete"
            );
        } catch (Exception exception) {
            return new Check("MANIFEST_INTEGRITY", false, "Manifest JSON is invalid");
        }
    }

    private Check validateAcceptanceTests(String content) {
        if (content == null) {
            return new Check("ACCEPTANCE_COVERAGE", false, "Acceptance test manifest is missing");
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode requirements = root.path("requirements");
            boolean complete = requirements.isArray()
                    && requirements.size() > 0
                    && java.util.stream.StreamSupport.stream(requirements.spliterator(), false)
                    .filter(value -> "MUST".equals(value.path("priority").asText()))
                    .allMatch(value -> value.path("acceptance_ids").isArray()
                            && value.path("acceptance_ids").size() > 0
                            && value.path("api_ids").isArray()
                            && value.path("api_ids").size() > 0
                            && value.path("ui_ids").isArray()
                            && value.path("ui_ids").size() > 0);
            return new Check(
                    "ACCEPTANCE_COVERAGE",
                    complete,
                    complete ? "Every MUST requirement has acceptance, API, and UI evidence" : "MUST trace evidence is incomplete"
            );
        } catch (Exception exception) {
            return new Check("ACCEPTANCE_COVERAGE", false, "Acceptance test JSON is invalid");
        }
    }

    private Check validateFrontend(Map<String, String> files, List<Artifact> artifacts) {
        try {
            JsonNode packageJson = objectMapper.readTree(files.getOrDefault("frontend/package.json", "{}"));
            String app = files.getOrDefault("frontend/src/App.tsx", "");
            boolean buildScript = packageJson.path("scripts").path("build").asText("").contains("tsc");
            Artifact frontend = latest(artifacts, "FRONTEND_SKELETON");
            boolean componentsPresent = frontend != null;
            if (frontend != null) {
                JsonNode content = objectMapper.readTree(frontend.getContent());
                for (JsonNode page : content.path("pages")) {
                    componentsPresent &= app.contains(page.path("name").asText());
                }
            }
            boolean passed = buildScript && componentsPresent && app.contains("export default function App");
            return new Check(
                    "FRONTEND_CONTRACT_TEST",
                    passed,
                    passed ? "TypeScript build script and all declared pages are represented" : "Frontend contract projection is incomplete"
            );
        } catch (Exception exception) {
            return new Check("FRONTEND_CONTRACT_TEST", false, "Frontend files are invalid");
        }
    }

    private Check validateSecrets(Map<String, String> files) {
        List<String> unsafe = files.entrySet().stream()
                .filter(entry -> containsConcreteSecret(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        return new Check(
                "SECURITY_SCAN",
                unsafe.isEmpty(),
                unsafe.isEmpty() ? "No concrete secret-like values detected" : "Unsafe values in: " + String.join(", ", unsafe)
        );
    }

    private Check compileAndSmokeTestBackend(Map<String, String> files) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new Check("BACKEND_BUILD_AND_SMOKE_TEST", false, "A JDK compiler is required for delivery verification");
        }
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("autospec-bundle-verify-").toAbsolutePath().normalize();
            Path sourceRoot = workspace.resolve("src");
            Path classes = workspace.resolve("classes");
            Files.createDirectories(sourceRoot.resolve("com/generated"));
            Files.createDirectories(classes);
            List<Path> sources = new ArrayList<>();
            for (String entry : List.of(
                    "backend/src/main/java/com/generated/Application.java",
                    "backend/src/main/java/com/generated/GeneratedContractController.java"
            )) {
                Path target = sourceRoot.resolve("com/generated").resolve(Path.of(entry).getFileName()).normalize();
                if (!target.startsWith(sourceRoot)) {
                    throw new IllegalStateException("Generated source path escaped verification workspace");
                }
                Files.writeString(target, files.getOrDefault(entry, ""), StandardCharsets.UTF_8);
                sources.add(target);
            }
            try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
                boolean compiled = Boolean.TRUE.equals(compiler.getTask(
                        null,
                        manager,
                        null,
                        List.of(
                                "--release", "17",
                                "-classpath", System.getProperty("java.class.path"),
                                "-d", classes.toString()
                        ),
                        null,
                        manager.getJavaFileObjectsFromPaths(sources)
                ).call());
                if (!compiled) {
                    return new Check("BACKEND_BUILD_AND_SMOKE_TEST", false, "Generated Java sources did not compile");
                }
            }
            try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
                Class<?> controllerType = loader.loadClass("com.generated.GeneratedContractController");
                Object controller = controllerType.getConstructor().newInstance();
                int operationCount = 0;
                for (Method method : controllerType.getDeclaredMethods()) {
                    if (!method.getName().startsWith("operation")) {
                        continue;
                    }
                    operationCount++;
                    Object result = method.invoke(controller);
                    if (!(result instanceof Map<?, ?> map) || !"READY".equals(map.get("status"))) {
                        return new Check("BACKEND_BUILD_AND_SMOKE_TEST", false,
                                "Generated operation did not return an executable mock response: " + method.getName());
                    }
                }
                return new Check(
                        "BACKEND_BUILD_AND_SMOKE_TEST",
                        operationCount > 0,
                        operationCount + " generated API operations compiled and passed smoke tests"
                );
            }
        } catch (Exception exception) {
            return new Check("BACKEND_BUILD_AND_SMOKE_TEST", false,
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        } finally {
            deleteWorkspace(workspace);
        }
    }

    private Map<String, String> unzip(byte[] bytes) {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) {
                    throw new IllegalArgumentException("Unsafe ZIP entry: " + name);
                }
                files.put(name, new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
            return files;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid generated ZIP", exception);
        }
    }

    private Artifact latest(List<Artifact> artifacts, String type) {
        return artifacts.stream()
                .filter(artifact -> type.equals(artifact.getType()))
                .max(Comparator.comparingInt((Artifact artifact) ->
                                artifact.getVersion() == null ? 0 : artifact.getVersion())
                        .thenComparing(Artifact::getId, Comparator.nullsFirst(Long::compareTo)))
                .orElse(null);
    }

    private boolean containsConcreteSecret(String content) {
        String lower = content.toLowerCase();
        return lower.contains("sk-live-")
                || lower.matches("(?s).*password\s*[:=]\s*(?!\\$\\{|<|your-|changeme)[^\\s]+.*")
                || lower.matches("(?s).*api[_-]?key\s*[:=]\s*(?!\\$\\{|<|your-|changeme)[^\\s]+.*");
    }

    private String hash(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void deleteWorkspace(Path workspace) {
        if (workspace == null || !Files.exists(workspace)) {
            return;
        }
        try (var paths = Files.walk(workspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    if (path.toAbsolutePath().normalize().startsWith(workspace)) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException ignored) {
                    // A stale temporary verification directory is safe to clean up later.
                }
            });
        } catch (IOException ignored) {
            // Verification result is already determined; cleanup failure must not rewrite it.
        }
    }

    public record VerificationReport(boolean passed, String manifestHash, String evidenceJson) {
    }

    private record Check(String name, boolean passed, String evidence) {
    }
}

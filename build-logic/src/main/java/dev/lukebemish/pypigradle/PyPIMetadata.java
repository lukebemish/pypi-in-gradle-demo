package dev.lukebemish.pypigradle;

import com.squareup.moshi.Json;
import com.squareup.moshi.Moshi;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.nativeplatform.MachineArchitecture;
import org.gradle.nativeplatform.OperatingSystemFamily;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

public record PyPIMetadata(
        Info info,
        List<UrlInfo> urls
) {
    public List<UrlInfo> parsedUrlInfo(ModuleVersionIdentifier id) {
        return urls.stream().filter(p -> p.isUnderstood(id)).toList();
    }
    
    public record Info(
            @Json(name = "requires_dist") @Nullable List<String> requiresDist
    ) {
        public List<DistRequirement> parsedRequirements() {
            return (requiresDist == null ? Stream.<String>of() : requiresDist.stream()).map(PyPIMetadata::parse).filter(Objects::nonNull).filter(DistRequirement::isUnderstood).toList();
        }
    }
    
    public record UrlInfo(
            @Json(name = "filename") String name,
            @Json(name = "packagetype") String packageType,
            @Json(name = "url") String url
    ) {
        public static final class UnknownEnvironmentException extends RuntimeException {}
        
        public @Nullable String machineArchitecture(ModuleVersionIdentifier id) {
            if (packageType.equals("sdist")) {
                return null;
            }
            var rest = name;
            if (rest.startsWith(id.getModule() + "-")) {
                rest = rest.substring(id.getName().length()+1);
            }
            if (rest.startsWith(id.getVersion() + "-")) {
                rest = rest.substring(id.getVersion().length()+1);
            }
            rest = rest.toLowerCase(Locale.ROOT);
            if (rest.contains("_x86_64.") || rest.contains("_amd64.") || rest.contains("_x64.")) {
                return MachineArchitecture.X86_64;
            } else if (rest.contains("_aarch64.") || rest.contains("_arm64.")) {
                return MachineArchitecture.ARM64;
            } else if (rest.contains("_i386.") || rest.contains("_i686.") || rest.contains("_x86.")) {
                return MachineArchitecture.X86;
            } else if (rest.contains("-any.")) {
                return null;
            } else if (rest.contains("-win32")) {
                return MachineArchitecture.X86;
            } else {
                throw new UnknownEnvironmentException();
            }
        }
        
        public @Nullable String operatingSystemFamily(ModuleVersionIdentifier id) {
            if (packageType.equals("sdist")) {
                return null;
            }
            var rest = name;
            if (rest.startsWith(id.getModule() + "-")) {
                rest = rest.substring(id.getName().length()+1);
            }
            if (rest.startsWith(id.getVersion() + "-")) {
                rest = rest.substring(id.getVersion().length()+1);
            }
            rest = rest.toLowerCase(Locale.ROOT);
            if (rest.contains("-linux") || rest.contains("-manylinux")) {
                return OperatingSystemFamily.LINUX;
            } else if (rest.contains("-macosx")) {
                return OperatingSystemFamily.MACOS;
            } else if (rest.contains("-win")) {
                return OperatingSystemFamily.WINDOWS;
            } else if (rest.contains("-any.")) {
                return null;
            } else {
                throw new UnknownEnvironmentException();
            }
        }
        
        public boolean isUnderstood(ModuleVersionIdentifier id) {
            try {
                machineArchitecture(id);
                operatingSystemFamily(id);
            } catch (UnknownEnvironmentException e) {
                return false;
            }
            return true;
        }
    }
    
    public record DistRequirement(
            String name,
            @Nullable VersionConstraint versionSpec,
            @Nullable String sysPlatform,
            @Nullable String platformMachine
    ) {
        public boolean isUnderstood() {
            return switch (operatingSystemFamily()) {
                case OperatingSystemFamily.LINUX, OperatingSystemFamily.MACOS, OperatingSystemFamily.WINDOWS -> true;
                case null -> true;
                default -> false;
            } && switch (machineArchitecture()) {
                case MachineArchitecture.X86, MachineArchitecture.ARM64, MachineArchitecture.X86_64 -> true;
                case null -> true;
                default -> false;
            };
        }
        
        public @Nullable String operatingSystemFamily() {
            return switch (sysPlatform) {
                case "linux", "linux2" -> OperatingSystemFamily.LINUX;
                case "darwin" -> OperatingSystemFamily.MACOS;
                case "win32" -> OperatingSystemFamily.WINDOWS;
                case null -> null;
                default -> sysPlatform;
            };
        }
        
        public @Nullable String machineArchitecture() {
            if (platformMachine == null) {
                return null;
            }
            return switch (platformMachine.toLowerCase(Locale.ROOT)) {
                case "x86_64", "amd64", "x64" -> MachineArchitecture.X86_64;
                case "aarch64", "arm64" -> MachineArchitecture.ARM64;
                case "i386", "i686", "x86" -> MachineArchitecture.X86;
                default -> platformMachine;
            };
        }
    }
    
    private static boolean isIdentifier(char c) {
        return Character.isAlphabetic(c) || Character.isDigit(c) || c == '_' || c == '-' || c == '.';
    }

    private static @Nullable DistRequirement parse(String requirement) {
        requirement = requirement.trim();
        var firstNonAlphaNum = 0;
        while (firstNonAlphaNum < requirement.length() && isIdentifier(requirement.charAt(firstNonAlphaNum))) {
            firstNonAlphaNum++;
        }
        var name = requirement.substring(0, firstNonAlphaNum);
        var rest = requirement.substring(firstNonAlphaNum).trim();
        VersionConstraint versionSpec = null;
        String sysPlatform = null;
        String platformMachine = null;
        var parts = rest.split(";");
        var versionSpecString = parts[0].trim();
        if (versionSpecString.startsWith("(")) {
            var end = versionSpecString.indexOf(')');
            if (end == -1) {
                throw new IllegalArgumentException("Unclosed version spec in requirement: " + requirement);
            }
            versionSpecString = versionSpecString.substring(1, end).trim();
        }
        if (!versionSpecString.isEmpty()) {
            versionSpec = new VersionConstraint(versionSpecString);
        }
        if (parts.length > 1) {
            var markers = parts[1].trim().split(" and ");
            for (var marker : markers) {
                marker = marker.trim();
                if (marker.startsWith("sys_platform")) {
                    var markerRest = marker.substring("sys_platform".length()).trim();
                    var quoteStart = markerRest.indexOf('\"');
                    if (quoteStart != -1) {
                        sysPlatform = markerRest.substring(quoteStart + 1, markerRest.length() - 1).trim();
                    }
                } else if (marker.startsWith("platform_machine")) {
                    var markerRest = marker.substring("platform_machine".length()).trim();
                    var quoteStart = markerRest.indexOf('\"');
                    if (quoteStart != -1) {
                        platformMachine = markerRest.substring(quoteStart + 1, markerRest.length() - 1).trim();
                    }
                } else if (marker.startsWith("extra")) {
                    return null;
                }
            }
        }
        return new DistRequirement(name, versionSpec, sysPlatform, platformMachine);
    }
    
    public static @Nullable PyPIMetadata fromJson(InputStream input) {
        var moshi = new Moshi.Builder().build();
        var adapter = moshi.adapter(PyPIMetadata.class);
        try {
            var json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return adapter.fromJson(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

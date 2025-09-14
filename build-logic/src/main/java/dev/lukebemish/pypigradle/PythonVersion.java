package dev.lukebemish.pypigradle;

import org.gradle.api.Named;
import org.jspecify.annotations.Nullable;

import javax.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class PythonVersion implements Named, Comparable<PythonVersion> {
    private final int epoch;
    private final List<Integer> release;
    private final @Nullable PreRelease preRelease;
    private final OptionalInt post;
    private final OptionalInt dev;
    private final String name;
    
    static final Pattern REGEX = Pattern.compile(
            "v?(?:(?:(?<epoch>[0-9]+)!)?(?<release>[0-9]+(?:\\.[0-9]+)*)(?<pre>[-_.]?(?<preL>alpha|a|beta|b|preview|pre|c|rc)[-_.]?(?<preN>[0-9]+)?)?(?<post>(?:-(?<postN1>[0-9]+))|(?:[-_.]?(?<postL>post|rev|r)[-_.]?(?<postN2>[0-9]+)?))?(?<dev>[-_.]?(?<devL>dev)[-_.]?(?<devN>[0-9]+)?)?)"
    );
    
    private PythonVersion(int epoch, List<Integer> release, @Nullable PreRelease preRelease, OptionalInt post, OptionalInt dev) {
        this.epoch = epoch;
        this.release = release;
        this.preRelease = preRelease;
        this.post = post;
        this.dev = dev;
        this.name = canonicalName(epoch, release, preRelease, post, dev);
    }
    
    PythonVersion upperForWildcard(VersionConstraint.EndsAt endsAt, boolean dropLast) {
        return switch (endsAt) {
            case AFTER_RELEASE -> {
                if (dropLast && release.size() == 1) {
                    throw new IllegalArgumentException("Cannot drop last part of release when only one part exists");
                }
                var lastOfRelease = release.get(release.size() - (dropLast ? 2 : 1)) + 1;
                var releaseNew = Stream.concat(this.release.stream().limit(this.release.size() - (dropLast ? 2 : 1)), Stream.of(lastOfRelease, 0)).toList();
                yield new PythonVersion(epoch, releaseNew, null, OptionalInt.empty(), OptionalInt.of(0));
            }
            case AFTER_PRE_RELEASE -> dropLast ? upperForWildcard(VersionConstraint.EndsAt.AFTER_RELEASE, false) : new PythonVersion(
                    epoch,
                    release,
                    new PreRelease(preRelease.type, preRelease.version + 1),
                    OptionalInt.empty(),
                    OptionalInt.of(0)
            );
            case AFTER_POST -> dropLast ? upperForWildcard(preRelease == null ? VersionConstraint.EndsAt.AFTER_RELEASE : VersionConstraint.EndsAt.AFTER_PRE_RELEASE, false) : new PythonVersion(
                    epoch,
                    release,
                    preRelease,
                    OptionalInt.of(post.orElseThrow() + 1),
                    OptionalInt.of(0)
            );
        };
    }
    
    @Inject
    public PythonVersion(String name) {
        this.name = name;
        var match = REGEX.matcher(name);
        if (!match.matches()) {
            throw new IllegalArgumentException("Invalid version: " + name);
        }
        this.epoch = match.group("epoch") == null ? 0 : Integer.parseInt(match.group("epoch"));
        this.release = match.group("release") == null ? List.of() : 
                Stream.of(match.group("release").split("\\.")).map(Integer::parseInt).toList();
        if (match.group("preL") != null) {
            PreReleaseType type = switch (match.group("preL")) {
                case "alpha", "a" -> PreReleaseType.ALPHA;
                case "beta", "b" -> PreReleaseType.BETA;
                case "preview", "pre", "c", "rc" -> PreReleaseType.RC;
                default -> throw new IllegalStateException("Unexpected value: " + match.group("preL"));
            };
            int version = match.group("preN") == null ? 0 : Integer.parseInt(match.group("preN"));
            this.preRelease = new PreRelease(type, version);
        } else {
            this.preRelease = null;
        }
        if (match.group("postN1") != null) {
            this.post = OptionalInt.of(Integer.parseInt(match.group("postN1")));
        } else if (match.group("postL") != null) {
            this.post = OptionalInt.of(match.group("postN2") == null ? 0 : Integer.parseInt(match.group("postN2")));
        } else {
            this.post = OptionalInt.empty();
        }
        if (match.group("devL") != null) {
            this.dev = OptionalInt.of(match.group("devN") == null ? 0 : Integer.parseInt(match.group("devN")));
        } else {
            this.dev = OptionalInt.empty();
        }
    }

    @Override
    public int compareTo(PythonVersion other) {
        if (this.epoch != other.epoch) {
            return Integer.compare(this.epoch, other.epoch);
        }
        var thisSize = this.release.size();
        var otherSize = other.release.size();
        for (int i = 0; i < Math.max(thisSize, otherSize); i++) {
            var thisPart = i < thisSize ? this.release.get(i) : 0;
            var otherPart = i < otherSize ? other.release.get(i) : 0;
            if (thisPart != otherPart) {
                return Integer.compare(thisPart, otherPart);
            }
        }
        if (this.preRelease == null && other.preRelease != null) {
            if (this.dev.isPresent()) {
                return -1; // Dev-release is less than pre-release
            }
            return 1; // No pre-release is greater than any pre-release
        } else if (this.preRelease != null) {
            if (other.preRelease == null) {
                if (other.dev.isPresent()) {
                    return 1;
                }
                return -1;
            } else {
                var typeCompare = this.preRelease.type().compareTo(other.preRelease.type());
                if (typeCompare != 0) {
                    return typeCompare;
                }
                if (this.preRelease.version() != other.preRelease.version()) {
                    return Integer.compare(this.preRelease.version(), other.preRelease.version());
                }
            }
        }
        if (this.post.isPresent() && other.post.isEmpty()) {
            return 1; // Post-release is greater than no post-release
        } else if (other.post.isPresent()) {
            if (this.post.isEmpty()) {
                return -1;
            } else {
                if (this.post.getAsInt() != other.post.getAsInt()) {
                    return Integer.compare(this.post.getAsInt(), other.post.getAsInt());
                }
            }
        }
        if (this.dev.isPresent() && other.dev.isEmpty()) {
            return -1; // Dev-release is less than no dev-release
        } else if (other.dev.isPresent()) {
            if (this.dev.isEmpty()) {
                return 1;
            } else {
                if (this.dev.getAsInt() != other.dev.getAsInt()) {
                    return Integer.compare(this.dev.getAsInt(), other.dev.getAsInt());
                }
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PythonVersion that)) return false;
        return epoch == that.epoch && Objects.equals(release, that.release) && Objects.equals(preRelease, that.preRelease) && Objects.equals(post, that.post) && Objects.equals(dev, that.dev);
    }

    @Override
    public int hashCode() {
        return Objects.hash(epoch, release, preRelease, post, dev);
    }

    public record PreRelease(PreReleaseType type, int version) {}
    
    public enum PreReleaseType {
        ALPHA("a"),
        BETA("b"),
        RC("rc");
        
        private final String identifier;

        PreReleaseType(String identifier) {
            this.identifier = identifier;
        }
    }
    
    @Override
    public String getName() {
        return this.name;
    }
    
    private static String canonicalName(int epoch, List<Integer> release, @Nullable PreRelease preRelease, OptionalInt post, OptionalInt dev) {
        var sb = new StringBuilder();
        if (epoch != 0) {
            sb.append(epoch).append("!");
        }
        sb.append(release.get(0));
        for (int i = 1; i < release.size(); i++) {
            sb.append(".").append(release.get(i));
        }
        if (preRelease != null) {
            sb.append(preRelease.type().identifier).append(preRelease.version);
        }
        if (post.isPresent()) {
            sb.append(".post").append(post.getAsInt());
        }
        if (dev.isPresent()) {
            sb.append(".dev").append(dev.getAsInt());
        }
        return sb.toString();
    }
    
    public String getCanonicalName() {
        return canonicalName(epoch, release, preRelease, post, dev);
    }
}

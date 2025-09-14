package dev.lukebemish.pypigradle;

import org.gradle.api.Named;
import org.gradle.api.artifacts.MutableVersionConstraint;
import org.jspecify.annotations.Nullable;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class VersionConstraint implements Named {
    private final String name;
    private final Constraints constraint;

    public Constraints constraints() {
        return constraint;
    }
    
    @Inject
    public VersionConstraint(String name) {
        this.name = name;
        this.constraint = parseConstraints(name);
    }

    enum EndsAt {
        AFTER_RELEASE, AFTER_PRE_RELEASE, AFTER_POST
    }
    
    private static Constraints parseConstraints(String string) {
        var parts = string.split(",");
        var ranges = Stream.of(parts).map(part -> {
            part = part.trim();
            if (part.startsWith("===")) {
                var rest = part.substring(3).trim();
                var lower = new PythonVersion(rest);
                return new RangeOrOr.Range(new VersionRange(lower, true, lower, true));
            } else if (part.startsWith("==")) {
                var rest = part.substring(2).trim();
                boolean isWildcard = rest.endsWith(".*");
                if (isWildcard) {
                    rest = rest.substring(0, rest.length() - 2).trim();
                }
                EndsAt endsAt = findEndsAt(rest);
                var lower = new PythonVersion(rest);
                var upper = isWildcard ? lower.upperForWildcard(endsAt, false) : lower;
                return new RangeOrOr.Range(new VersionRange(lower, true, upper, !isWildcard));
            } else if (part.startsWith("~=")) {
                var rest = part.substring(2).trim();
                EndsAt endsAt = findEndsAt(rest);
                var lower = new PythonVersion(rest);
                var upper = lower.upperForWildcard(endsAt, true);
                return new RangeOrOr.Range(new VersionRange(lower, true, upper, false));
            } else if (part.startsWith(">=")) {
                var rest = part.substring(2).trim();
                var lower = new PythonVersion(rest);
                return new RangeOrOr.Range(new VersionRange(lower, true, null, false));
            } else if (part.startsWith("<=")) {
                var rest = part.substring(2).trim();
                var upper = new PythonVersion(rest);
                return new RangeOrOr.Range(new VersionRange(null, false, upper, true));
            } else if (part.startsWith(">")) {
                var rest = part.substring(1).trim();
                var lower = new PythonVersion(rest);
                return new RangeOrOr.Range(new VersionRange(lower, false, null, false));
            } else if (part.startsWith("<")) {
                var rest = part.substring(1).trim();
                var upper = new PythonVersion(rest);
                return new RangeOrOr.Range(new VersionRange(null, false, upper, false));
            } else if (part.startsWith("!=")) {
                var rest = part.substring(2).trim();
                boolean isWildcard = rest.endsWith(".*");
                if (isWildcard) {
                    rest = rest.substring(0, rest.length() - 2).trim();
                }
                EndsAt endsAt = findEndsAt(rest);
                var lower = new PythonVersion(rest);
                var upper = isWildcard ? lower.upperForWildcard(endsAt, false) : lower;
                // TODO: this needs to be a weird sort of constraint, not a range
                return new RangeOrOr.Or(List.of(new VersionRange(null, false, lower, false), new VersionRange(upper, isWildcard, null, false)));
            } else {
                throw new IllegalArgumentException("Invalid constraint: " + part);
            }
        }).toList();

        return processConstraints(ranges);
    }

    private static Constraints processConstraints(List<? extends RangeOrOr> ranges) {
        if (ranges.isEmpty()) {
            return new Constraints(List.of(new VersionRange(null, false, null, false)));
        }
        List<VersionRange> rangeList = new ArrayList<>();
        switch (ranges.getFirst()) {
            case RangeOrOr.Range r -> rangeList.add(r.range);
            case RangeOrOr.Or o -> rangeList.addAll(o.options);
        }
        for (int i = 1; i < ranges.size(); i++) {
            var r = ranges.get(i);
            List<VersionRange> newRangeList = new ArrayList<>();
            switch (r) {
                case RangeOrOr.Range range -> {
                    for (var existing : rangeList) {
                        var combined = existing.and(range.range);
                        if (combined != null) {
                            newRangeList.add(combined);
                        }
                    }
                }
                case RangeOrOr.Or o -> {
                    for (var option : o.options) {
                        for (var existing : rangeList) {
                            var combined = existing.and(option);
                            if (combined != null) {
                                newRangeList.add(combined);
                            }
                        }
                    }
                }
            }
            rangeList = newRangeList;
            if (rangeList.isEmpty()) {
                break;
            }
        }

        return new Constraints(rangeList);
    }

    private static EndsAt findEndsAt(String rest) {
        EndsAt endsAt = EndsAt.AFTER_RELEASE;

        var match = PythonVersion.REGEX.matcher(rest);
        if (!match.matches()) {
            throw new IllegalArgumentException("Invalid version: " + rest);
        }
        if (match.group("preL") != null) {
            endsAt = EndsAt.AFTER_PRE_RELEASE;
        }
        if (match.group("postN1") != null || match.group("postL") != null) {
            endsAt = EndsAt.AFTER_POST;
        }
        if (match.group("devL") != null) {
            throw new IllegalArgumentException("Cannot use dev releases with wildcard constraints: " + rest);
        }
        return endsAt;
    }
    
    private sealed interface RangeOrOr {
        record Range(VersionRange range) implements RangeOrOr {}
        record Or(List<VersionRange> options) implements RangeOrOr {}
    } 

    public record Constraints(List<VersionRange> ranges) implements Comparable<Constraints> {
        public void apply(MutableVersionConstraint version) {
            var complement = complement();
            if (complement.ranges.isEmpty()) {
                version.strictly("+");
                return;
            }
            PythonVersion lower = null;
            boolean lowerInclusive = false;
            PythonVersion upper = null;
            boolean upperInclusive = false;
            List<String> reject = new ArrayList<>();
            for (var range : complement.ranges) {
                if (range.lower == null) {
                    if (range.upper == null) {
                        version.rejectAll();
                        return;
                    }
                    lower = range.upper;
                    lowerInclusive = !range.upperInclusive;
                } else if (range.upper == null) {
                    upper = range.lower;
                    upperInclusive = !range.lowerInclusive;
                } else {
                    reject.add(range.toGradleString());
                }
            }
            var range = new VersionRange(lower, lowerInclusive, upper, upperInclusive);
            version.strictly(range.toGradleString());
            version.reject(reject.toArray(String[]::new));
        }
        
        public boolean overlaps(Constraints other) {
            for (var range : ranges) {
                for (var otherRange : other.ranges) {
                    if (range.and(otherRange) != null) {
                        return true;
                    }
                }
            }
            return false;
        }
        
        public Constraints complement() {
            if (ranges.isEmpty()) {
                return new Constraints(List.of(new VersionRange(null, false, null, false)));
            }
            var ranges = new ArrayList<RangeOrOr>();
            for (var range : this.ranges) {
                ranges.add(range.complement());
            }
            return processConstraints(ranges);
        }
        
        @Override
        public int compareTo(Constraints o) {
            if (ranges.isEmpty()) {
                if (o.ranges.isEmpty()) {
                    return 0;
                } else {
                    return -1;
                }
            } else if (o.ranges.isEmpty()) {
                return 1;
            }
            
            var sorted = ranges.stream().sorted().toList();
            var otherSorted = o.ranges.stream().sorted().toList();
            int len = Math.min(sorted.size(), otherSorted.size());
            for (int i = 0; i < len; i++) {
                int cmp = sorted.get(i).compareTo(otherSorted.get(i));
                if (cmp != 0) {
                    return cmp;
                }
            }
            return Integer.compare(sorted.size(), otherSorted.size());
        }
    }
    public record VersionRange(@Nullable PythonVersion lower, boolean lowerInclusive, @Nullable PythonVersion upper, boolean upperInclusive) implements Comparable<VersionRange> {
        RangeOrOr complement() {
            if (lower == null) {
                if (upper == null) {
                    return new RangeOrOr.Or(List.of());
                }
                return new RangeOrOr.Range(new VersionRange(upper, !upperInclusive, null, false));
            } else if (upper == null) {
                return new RangeOrOr.Range(new VersionRange(null, false, lower, !lowerInclusive));
            } else {
                return new RangeOrOr.Or(List.of(
                        new VersionRange(null, false, lower, !lowerInclusive),
                        new VersionRange(upper, !upperInclusive, null, false)
                ));
            }
        }
        
        public @Nullable VersionRange and(VersionRange other) {
            PythonVersion lower;
            boolean lowerInclusive;
            PythonVersion upper;
            boolean upperInclusive;
            
            if (this.lower == null) {
                lower = other.lower;
                lowerInclusive = other.lowerInclusive;
            } else if (other.lower == null) {
                lower = this.lower;
                lowerInclusive = this.lowerInclusive;
            } else {
                int cmp = this.lower.compareTo(other.lower);
                if (cmp < 0) {
                    lower = other.lower;
                    lowerInclusive = other.lowerInclusive;
                } else if (cmp > 0) {
                    lower = this.lower;
                    lowerInclusive = this.lowerInclusive;
                } else {
                    lower = this.lower;
                    lowerInclusive = this.lowerInclusive && other.lowerInclusive;
                }
            }
            
            if (this.upper == null) {
                upper = other.upper;
                upperInclusive = other.upperInclusive;
            } else if (other.upper == null) {
                upper = this.upper;
                upperInclusive = this.upperInclusive;
            } else {
                int cmp = this.upper.compareTo(other.upper);
                if (cmp < 0) {
                    upper = this.upper;
                    upperInclusive = this.upperInclusive;
                } else if (cmp > 0) {
                    upper = other.upper;
                    upperInclusive = other.upperInclusive;
                } else {
                    upper = this.upper;
                    upperInclusive = this.upperInclusive && other.upperInclusive;
                }
            }
            
            if (lower != null && upper != null) {
                int cmp = lower.compareTo(upper);
                if (cmp > 0 || (cmp == 0 && (!lowerInclusive || !upperInclusive))) {
                    return null; // No intersection
                }
            }
            return new VersionRange(lower, lowerInclusive, upper, upperInclusive);
        }
        
        @Override
        public int compareTo(VersionRange o) {
            if (this.lower != null && o.lower != null) {
                int cmp = this.lower.compareTo(o.lower);
                if (cmp != 0) {
                    return cmp;
                }
                if (this.lowerInclusive != o.lowerInclusive) {
                    return this.lowerInclusive ? -1 : 1;
                }
            } else if (this.lower != null) {
                return 1;
            } else if (o.lower != null) {
                return -1;
            }
            if (this.upper != null && o.upper != null) {
                int cmp = this.upper.compareTo(o.upper);
                if (cmp != 0) {
                    return cmp;
                }
                if (this.upperInclusive != o.upperInclusive) {
                    return this.upperInclusive ? 1 : -1;
                }
            } else if (this.upper != null) {
                return -1;
            } else if (o.upper != null) {
                return 1;
            }
            return 0;
        }

        public String toGradleString() {
            StringBuilder sb = new StringBuilder();
            if (lower == null || !lowerInclusive) {
                sb.append('(');
            } else {
                sb.append('[');
            }
            if (lower != null) {
                sb.append(lower.getName());
            }
            sb.append(",");
            if (upper != null) {
                sb.append(upper.getName());
            }
            if (upper == null || !upperInclusive) {
                sb.append(')');
            } else {
                sb.append(']');
            }
            return sb.toString();
        }
    }

    @Override
    public String getName() {
        return "";
    }
}

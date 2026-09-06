package net.neoforged.neoform.runtime.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

final class ParchmentData {
    private final Root root;

    private ParchmentData(List<ClassEntry> classes) {
        root = new Root("1.1.0", List.copyOf(classes));
    }

    static Builder builder() {
        return new Builder();
    }

    String content() {
        return NfrtFixtureSupport.toJson(root);
    }

    private record Root(String version, List<ClassEntry> classes) {
    }

    private record ClassEntry(String name, List<String> javadoc, List<FieldEntry> fields) {
    }

    private record FieldEntry(String name, String descriptor, List<String> javadoc) {
    }

    static final class Builder {
        private final LinkedHashMap<String, MutableClassEntry> classes = new LinkedHashMap<>();

        private Builder() {
        }

        Builder classJavadoc(String className, String... javadoc) {
            classEntry(className).javadoc.addAll(copyJavadoc(javadoc));
            return this;
        }

        Builder fieldJavadoc(String className,
                             String fieldName,
                             String descriptor,
                             String... javadoc) {
            classEntry(className).fields.add(new FieldEntry(
                    Objects.requireNonNull(fieldName),
                    Objects.requireNonNull(descriptor),
                    copyJavadoc(javadoc)
            ));
            return this;
        }

        ParchmentData build() {
            if (classes.isEmpty()) {
                throw new IllegalStateException("At least one class must be documented");
            }
            return new ParchmentData(classes.values().stream().map(MutableClassEntry::build).toList());
        }

        private MutableClassEntry classEntry(String className) {
            Objects.requireNonNull(className);
            return classes.computeIfAbsent(className, MutableClassEntry::new);
        }

        private static List<String> copyJavadoc(String[] javadoc) {
            if (javadoc.length == 0) {
                throw new IllegalArgumentException("At least one Javadoc line must be added");
            }
            return List.of(javadoc);
        }
    }

    private static final class MutableClassEntry {
        private final String name;
        private final List<String> javadoc = new ArrayList<>();
        private final List<FieldEntry> fields = new ArrayList<>();

        private MutableClassEntry(String name) {
            this.name = name;
        }

        private ClassEntry build() {
            return new ClassEntry(
                    name,
                    javadoc.isEmpty() ? null : List.copyOf(javadoc),
                    fields.isEmpty() ? null : List.copyOf(fields)
            );
        }
    }
}

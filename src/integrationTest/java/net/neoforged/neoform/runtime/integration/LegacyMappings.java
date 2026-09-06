package net.neoforged.neoform.runtime.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class LegacyMappings {
    private final String officialMappings;
    private final String intermediaryMappings;

    private LegacyMappings(List<ClassMapping> classes) {
        var official = new StringBuilder();
        var intermediary = new StringBuilder();
        for (var classMapping : classes) {
            classMapping.appendOfficialTo(official);
            classMapping.appendIntermediaryTo(intermediary);
        }
        officialMappings = official.toString();
        intermediaryMappings = intermediary.toString();
    }

    static Builder builder() {
        return new Builder();
    }

    String officialMappings() {
        return officialMappings;
    }

    String intermediaryMappings() {
        return intermediaryMappings;
    }

    private static final class ClassMapping {
        private final String officialName;
        private final String obfuscatedName;
        private final List<FieldMapping> fields = new ArrayList<>();

        private ClassMapping(String officialName, String obfuscatedName) {
            this.officialName = officialName;
            this.obfuscatedName = obfuscatedName;
        }

        private void addField(FieldMapping field) {
            fields.add(field);
        }

        private void appendOfficialTo(StringBuilder result) {
            result.append(officialName)
                    .append(" -> ")
                    .append(obfuscatedName)
                    .append(":\n");
            for (var field : fields) {
                field.appendOfficialTo(result);
            }
        }

        private void appendIntermediaryTo(StringBuilder result) {
            result.append(obfuscatedName)
                    .append(' ')
                    .append(officialName)
                    .append('\n');
            for (var field : fields) {
                field.appendIntermediaryTo(result);
            }
        }
    }

    private record FieldMapping(String type,
                                String officialName,
                                String obfuscatedName,
                                String intermediaryName) {
        private void appendOfficialTo(StringBuilder result) {
            result.append("    ")
                    .append(type)
                    .append(' ')
                    .append(officialName)
                    .append(" -> ")
                    .append(obfuscatedName)
                    .append('\n');
        }

        private void appendIntermediaryTo(StringBuilder result) {
            result.append('\t')
                    .append(obfuscatedName)
                    .append(' ')
                    .append(intermediaryName)
                    .append('\n');
        }
    }

    static final class Builder {
        private final List<ClassMapping> classes = new ArrayList<>();
        private ClassMapping currentClass;

        private Builder() {
        }

        Builder classMapping(String officialName, String obfuscatedName) {
            officialName = Objects.requireNonNull(officialName);
            obfuscatedName = Objects.requireNonNull(obfuscatedName);
            if (officialName.equals(obfuscatedName)) {
                throw new IllegalArgumentException("Obfuscated class names must differ from official class names");
            }
            currentClass = new ClassMapping(officialName, obfuscatedName);
            classes.add(currentClass);
            return this;
        }

        Builder fieldMapping(String type,
                             String officialName,
                             String obfuscatedName,
                             String intermediaryName) {
            if (currentClass == null) {
                throw new IllegalStateException("A class mapping must be added before its fields");
            }
            officialName = Objects.requireNonNull(officialName);
            obfuscatedName = Objects.requireNonNull(obfuscatedName);
            intermediaryName = Objects.requireNonNull(intermediaryName);
            requireDistinctNames(officialName, obfuscatedName, intermediaryName);
            currentClass.addField(new FieldMapping(
                    Objects.requireNonNull(type),
                    officialName,
                    obfuscatedName,
                    intermediaryName
            ));
            return this;
        }

        private static void requireDistinctNames(String officialName,
                                                 String obfuscatedName,
                                                 String intermediaryName) {
            if (Objects.equals(officialName, obfuscatedName)
                    || Objects.equals(officialName, intermediaryName)
                    || Objects.equals(obfuscatedName, intermediaryName)) {
                throw new IllegalArgumentException("Field mapping names must be distinct");
            }
        }

        LegacyMappings build() {
            if (classes.isEmpty()) {
                throw new IllegalStateException("At least one class mapping must be added");
            }
            return new LegacyMappings(classes);
        }
    }
}

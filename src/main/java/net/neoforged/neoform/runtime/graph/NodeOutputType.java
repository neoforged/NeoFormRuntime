package net.neoforged.neoform.runtime.graph;

public enum NodeOutputType {
    JAR(".jar"),
    TXT(".txt"),
    ZIP(".zip"),
    JSON(".json"),
    TSRG(".tsrg"),
    SRG(".srg");

    private final String extension;

    NodeOutputType(String extension) {
        this.extension = extension;
    }

    public String getExtension() {
        return extension;
    }
}

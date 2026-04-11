package net.neoforged.neoform.runtime.actions;

/**
 * Various defaults for JST or binary enum extension
 */
final class EnumExtensionDefaults {
    private EnumExtensionDefaults() {}
    
    static final String REQUIRED_INTERFACE = "net/neoforged/fml/common/asm/enumextension/IExtensibleEnum";
    static final String INDEXED_ENUM = "net/neoforged/fml/common/asm/enumextension/IndexedEnum";
    static final String MARKER_ANNOTATION = "net/neoforged/fml/common/asm/enumextension/ExtensionEnumEntry";
    static final String RESERVED_CONSTRUCTOR = "net/neoforged/fml/common/asm/enumextension/ReservedConstructor";
}

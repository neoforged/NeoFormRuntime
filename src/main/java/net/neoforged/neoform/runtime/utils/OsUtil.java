package net.neoforged.neoform.runtime.utils;

public final class OsUtil {
    private static OsType TYPE;

    static {
        var osName = System.getProperty("os.name");
        // The following matches the logic in Apache Commons Lang 3 SystemUtils
        if (osName.startsWith("Linux") || osName.startsWith("LINUX")) {
            TYPE = OsType.LINUX;
        } else if (osName.startsWith("Mac OS X")) {
            TYPE = OsType.MAC;
        } else if (osName.startsWith("Windows")) {
            TYPE = OsType.WINDOWS;
        } else {
            TYPE = OsType.UNKNOWN;
        }
    }

    private OsUtil() {
    }

    public static boolean isWindows() {
        return TYPE == OsType.WINDOWS;
    }

    public static boolean isLinux() {
        return TYPE == OsType.LINUX;
    }

    public static boolean isMac() {
        return TYPE == OsType.MAC;
    }

    enum OsType {
        WINDOWS,
        LINUX,
        MAC,
        UNKNOWN
    }
}

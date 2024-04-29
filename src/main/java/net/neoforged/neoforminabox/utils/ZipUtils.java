package net.neoforged.neoforminabox.utils;

import java.util.TimeZone;
import java.util.zip.ZipEntry;

public final class ZipUtils {
    /**
     * The constant time of a zip entry in milliseconds.
     */
    private static final long ZIPTIME = 628041600000L;

    /**
     * The GMT time zone.
     */
    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    private ZipUtils() {
    }

    /**
     * Creates a stable timed zip entry, with the default time.
     *
     * @param name The relative name of the entry
     * @return The zip entry
     */
    public static ZipEntry getStableEntry(String name) {
        return getStableEntry(name, ZIPTIME);
    }

    /**
     * Creates a stable timed zip entry.
     *
     * @param name The relative name of the entry
     * @param time The time of the entry
     * @return The zip entry
     */
    public static ZipEntry getStableEntry(String name, long time) {
        TimeZone _default = TimeZone.getDefault();
        TimeZone.setDefault(GMT);
        ZipEntry ret = new ZipEntry(name);
        ret.setTime(time);
        TimeZone.setDefault(_default);
        return ret;
    }

}

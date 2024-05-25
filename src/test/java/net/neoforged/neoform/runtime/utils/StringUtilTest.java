package net.neoforged.neoform.runtime.utils;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringUtilTest {
    @ParameterizedTest
    @CsvSource(textBlock = """
            0,0 B
            999,999 B
            1000,0.9 KiB
            1023,0.9 KiB
            1024,1 KiB
            1025,1 KiB
            1130,1.1 KiB
            11241,10 KiB
            1048575,0.9 MiB
            1048576,1 MiB
            1048571357,999 MiB
            1073741823,0.9 GiB
            1073741824,1 GiB
            """)
    void testFormatSize(long size, String expected) {
        assertEquals(expected, StringUtil.formatBytes(size));
    }
}
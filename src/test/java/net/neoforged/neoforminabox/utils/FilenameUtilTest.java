package net.neoforged.neoforminabox.utils;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FilenameUtilTest {

    @ParameterizedTest
    @CsvSource(textBlock = """
            /blah/blah/filename,''
            /blah/blah/filename.txt,.txt
            /blah/blah/filename.tar.gz,.tar.gz
            /blah/blah/filename.stuff.tar.gz,.tar.gz
            /blah/blah/filename.stuff.gz,.gz
            filename.stuff.tar.gz,.tar.gz
            filename.tar.gz,.tar.gz
            \\file/x.tar.gz,.tar.gz
            file.,.
            """)
    public void testGetExtension(String input, String expected) {
        assertEquals(expected, FilenameUtil.getExtension(input));
    }

}
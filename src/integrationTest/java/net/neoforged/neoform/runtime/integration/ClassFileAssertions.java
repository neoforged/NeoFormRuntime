package net.neoforged.neoform.runtime.integration;

import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;

import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

final class ClassFileAssertions {
    private ClassFileAssertions() {
    }

    /**
     * Asserts that a class file targets the requested Java release, such as Java 17.
     *
     * @see <a href="https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-4.1">JVMS §4.1</a>
     */
    static void assertTargetsJava(byte[] classFile, int expectedJavaVersion) {
        var majorVersion = (int) (readClassFile(classFile).getVersion() >>> 16);
        var actualJavaVersion = majorVersion - ClassFileConstants.MAJOR_VERSION_0;
        assertThat(actualJavaVersion)
                .as("Java version targeted by class file")
                .isEqualTo(expectedJavaVersion);
    }

    /**
     * Asserts that a class file directly implements the interface with the given JVM internal name.
     *
     * @see <a href="https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-4.1">JVMS §4.1</a>
     */
    static void assertImplementsInterface(byte[] classFile, String expectedInterface) {
        var parsedClassFile = readClassFile(classFile);
        var interfaces = Arrays.stream(parsedClassFile.getInterfaceNames())
                .map(String::new)
                .toList();
        assertThat(interfaces)
                .as("Interfaces directly implemented by %s", new String(parsedClassFile.getName()))
                .contains(expectedInterface);
    }

    private static ClassFileReader readClassFile(byte[] classFile) {
        try {
            return ClassFileReader.read(classFile, "result.class");
        } catch (ClassFormatException | IOException e) {
            throw new AssertionError("Invalid class file", e);
        }
    }
}

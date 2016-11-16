package org.jak_linux.dns66.vpn;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Set;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;

public class HostFileParserTest {

    @Test
    public void aHostCanBeReadFromASingleLineHostFile() {
        String hostString = "0.0.0.0 somehost";

        assertThat(parseHosts(hostString), hasItem("somehost"));
    }

    @Test
    public void commentsAreIgnored() {
        String hostString = "0.0.0.0 somehost # This is a comment";

        assertThat(parseHosts(hostString), hasItem("somehost"));
    }

    @Test
    public void multipleLinesCanBeParsed() {
        String hostString = "0.0.0.0 somehost # This is a comment\r\n\r\n127.0.0.1 someotherhost";

        assertThat(parseHosts(hostString), hasItems("somehost", "someotherhost"));
    }

    @Test
    public void commentedLinesAreIgnored() {
        String hostString = "0.0.0.0 somehost # This is a comment\r\n\r\n#127.0.0.1 someotherhost";

        assertThat(parseHosts(hostString), hasItems("somehost"));
    }

    @Test
    public void hostsCanBeSeparatedByMultipleWhitespaceCharacters() {
        String hostString = "0.0.0.0\t \t\t somehost";

        assertThat(parseHosts(hostString), hasItems("somehost"));
    }

    @Test
    public void hostsWithoutAnAssociatedIpCanBeAdded() {
        String hostString = "somehost";

        assertThat(parseHosts(hostString), hasItems("somehost"));
    }

    private Set<String> parseHosts(String hostString) {
        return new HostFileParser().parse(new ByteArrayInputStream(hostString.getBytes()));
    }
}

package net.neoforged.neoforminabox.cli;

import java.net.URI;

record SimpleDownloadSpec(URI uri) implements DownloadSpec {
}

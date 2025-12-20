package net.neoforged.neoform.runtime.artifacts;

import net.neoforged.neoform.runtime.downloads.DownloadManager;
import net.neoforged.neoform.runtime.utils.Logger;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Support class for querying maven metadata from a remote repository.
 * The format is documented here: https://maven.apache.org/repositories/metadata.html
 * We only deal with A-level metadata since we're interested in listing the versions of
 * a specific artifact.
 */
final class MavenMetadata {
    private static final Logger LOG = Logger.create();

    private MavenMetadata() {
    }

    static List<AvailableVersion> gatherVersions(DownloadManager downloadManager,
                                                 List<URI> repositoryBaseUrls,
                                                 String groupId,
                                                 String artifactId) throws IOException {
        var versions = new ArrayList<AvailableVersion>();
        for (var repositoryBaseUrl : repositoryBaseUrls) {
            versions.addAll(gatherVersions(downloadManager, repositoryBaseUrl, groupId, artifactId));
        }
        return versions;
    }

    static List<AvailableVersion> gatherVersions(DownloadManager downloadManager,
                                                 URI repositoryBaseUrl,
                                                 String groupId,
                                                 String artifactId) throws IOException {
        var metadataUri = repositoryBaseUrl.toString();
        if (!metadataUri.endsWith("/")) {
            metadataUri += "/";
        }
        metadataUri += groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml";

        byte[] metadataContent;

        var tempFile = Files.createTempFile("maven-metadata", ".xml");
        try {
            Files.deleteIfExists(tempFile); // The downloader should assume it does not exist yet
            downloadManager.download(URI.create(metadataUri), tempFile);
            metadataContent = Files.readAllBytes(tempFile);
        } catch (FileNotFoundException fnf) {
            return List.of(); // Repository doesn't have artifact
        } finally {
            Files.deleteIfExists(tempFile);
        }

        try (var in = new ByteArrayInputStream(metadataContent)) {
            var result = new ArrayList<AvailableVersion>();
            var documentBuilder = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder();
            var document = documentBuilder.parse(in).getDocumentElement();
            var nodes = document.getChildNodes();
            for (var i = 0; i < nodes.getLength(); i++) {
                if (nodes.item(i) instanceof Element versioningEl && "versioning".equals(versioningEl.getTagName())) {
                    for (var versions = versioningEl.getFirstChild(); versions != null; versions = versions.getNextSibling()) {
                        if (versions instanceof Element versionsEl && "versions".equals(versionsEl.getTagName())) {
                            for (var child = versionsEl.getFirstChild(); child != null; child = child.getNextSibling()) {
                                if (child instanceof Element childEl && "version".equals(childEl.getTagName())) {
                                    result.add(new AvailableVersion(
                                            repositoryBaseUrl,
                                            childEl.getTextContent().trim()
                                    ));
                                }
                            }
                        }
                    }
                }
            }
            return result;
        } catch (Exception e) {
            LOG.println("Failed to parse Maven metadata from " + metadataUri + ": " + e);
            throw new RuntimeException(e);
        }
    }

    record AvailableVersion(URI repositoryUrl, String version) {
    }
}
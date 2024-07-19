package net.neoforged.neoform.runtime.downloads;

import com.google.gson.Gson;
import net.neoforged.neoform.runtime.artifacts.Artifact;
import net.neoforged.neoform.runtime.artifacts.ArtifactManager;
import net.neoforged.neoform.runtime.cache.CacheManager;
import net.neoforged.neoform.runtime.cache.LauncherInstallations;
import net.neoforged.neoform.runtime.manifests.AssetIndex;
import net.neoforged.neoform.runtime.manifests.AssetIndexReference;
import net.neoforged.neoform.runtime.manifests.AssetObject;
import net.neoforged.neoform.runtime.manifests.MinecraftVersionManifest;
import net.neoforged.neoform.runtime.utils.HashingUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
class AssetDownloaderTest {
    // A fake MC version we generate a version manifest for, which points to our mocked asset index
    private static final String MC_VERSION = "1.2.3";
    // The asset index id we use for the generated MC version manifest
    private static final String ASSET_INDEX_ID = "1234";
    private static final String BASE_URI = "http://assets.fake/assets/";

    @TempDir
    Path tempDir;
    @TempDir
    Path nfrtAssetsDir;
    @Mock
    ArtifactManager artifactManager;
    @Mock
    DownloadManager downloadManager;
    @Mock
    CacheManager cacheManager;
    @Mock
    LauncherInstallations launcherInstallations;

    @InjectMocks
    AssetDownloader downloader;

    private int assetCounter;

    // Relative URI -> Content
    Map<String, byte[]> downloadableContent = new HashMap<>();

    // Actual downloads
    List<String> downloadedRelativePaths = new ArrayList<>();
    private Path versionManifestPath;

    @BeforeEach
    void setUp() throws IOException {
        nfrtAssetsDir = tempDir.resolve("assets");
        when(cacheManager.getAssetsDir()).thenReturn(nfrtAssetsDir);

        versionManifestPath = tempDir.resolve("minecraft_version.json");

        // Mock the download service
        doAnswer(invocation -> {
            var spec = invocation.getArgument(0, DownloadSpec.class);
            assertThat(spec.uri().toString()).startsWith(BASE_URI);
            var relative = spec.uri().toString().substring(BASE_URI.length());
            var content = downloadableContent.get(relative);
            if (content == null) {
                throw new RuntimeException("Unknown URI: " + relative);
            }
            var destination = invocation.getArgument(1, Path.class);
            Files.write(destination, content);
            downloadedRelativePaths.add(relative);
            return null;
        }).when(downloadManager).download(any(DownloadSpec.class), any(Path.class));
        doAnswer(invocation -> {
            var spec = invocation.getArgument(0, DownloadSpec.class);
            assertThat(spec.uri().toString()).startsWith(BASE_URI);
            var relative = spec.uri().toString().substring(BASE_URI.length());
            var content = downloadableContent.get(relative);
            if (content == null) {
                throw new RuntimeException("Unknown URI: " + relative);
            }
            var destination = invocation.getArgument(1, Path.class);
            Files.write(destination, content);
            downloadedRelativePaths.add(relative);
            return false;
        }).when(downloadManager).download(any(DownloadSpec.class), any(Path.class), anyBoolean());

        when(artifactManager.getVersionManifest(MC_VERSION))
                .thenAnswer(invoc -> Artifact.ofPath(versionManifestPath));
    }

    @Test
    void testDownloadWithoutLauncherInstallation() throws Exception {
        setAssetIndex(new AssetIndex(Map.of()));
        downloader.downloadAssets(
                MC_VERSION,
                URI.create(BASE_URI),
                false,
                false,
                2
        );
    }

    @Test
    void inAbsenceOfLaunchersAssetDirPointsToNfrtAssetDir() throws Exception {
        setAssetIndex(new AssetIndex(Map.of()));
        var result = downloader.downloadAssets(
                MC_VERSION,
                URI.create(BASE_URI),
                false,
                false,
                2
        );
        assertEquals(nfrtAssetsDir, result.assetRoot());
    }

    @Test
    void resultAssetIndexExists() throws Exception {
        setAssetIndex(new AssetIndex(Map.of()));
        var result = downloader.downloadAssets(
                MC_VERSION,
                URI.create(BASE_URI),
                false,
                false,
                2
        );
        assertThat(result.assetRoot().resolve("indexes/" + result.assetIndexId() + ".json")).isRegularFile();
    }

    @Test
    void testDownloadOfMultipleAssets() throws Exception {
        var assetPaths = List.of(
                "rootasset",
                "rootasset.ext",
                "first_level/asset",
                "first_level/sub_level/asset",
                "direct/third/level/asset"
        );
        var assetIndex = generateAssetIndex(assetPaths);
        var result = downloader.downloadAssets(
                MC_VERSION,
                URI.create(BASE_URI),
                false,
                false,
                2
        );

        for (var asset : assetIndex.objects().values()) {
            var targetPath = result.assetRoot().resolve("objects").resolve(asset.getRelativePath());
            assertThat(targetPath)
                    .isRegularFile()
                    .hasDigest("SHA-1", asset.hash());
        }
    }

    @Test
    void testDownloadFailuresAreCollectedAndThrownAtEndWhenDownloadingConcurrently() throws Exception {
        generateAssetIndex(List.of(
                "asset1",
                "asset2"
        ));
        doThrow(new RuntimeException("exc1"), new RuntimeException("exc2")).when(downloadManager)
                .download(any(), any(), anyBoolean());

        var e = assertThrows(DownloadsFailedException.class, () -> downloader.downloadAssets(
                MC_VERSION,
                URI.create(BASE_URI),
                false,
                false,
                2
        ));
        assertThat(e.getErrors()).hasSize(2);
    }

    @Test
    void testCorruptRemoteFileIsValidated() throws Exception {
        generateAssetIndex(List.of(
                "asset1",
                "asset2"
        ));
        doThrow(new RuntimeException("exc1"), new RuntimeException("exc2")).when(downloadManager)
                .download(any(), any(), anyBoolean());

        var e = assertThrows(DownloadsFailedException.class, () -> downloader.downloadAssets(
                MC_VERSION,
                URI.create(BASE_URI),
                false,
                false,
                2
        ));
        assertThat(e.getErrors()).hasSize(2);
    }

    @Nested
    class ReuseOfAssetsFromLaunchers {
        private AssetIndex assetIndex;
        private Path fakeLauncher1;
        private Path fakeLauncher2;
        private AssetObject asset1;
        private AssetObject asset2;

        @BeforeEach
        void setUp() throws IOException {
            var assetPaths = List.of("a/b/asset", "b/c/asset");
            assetIndex = generateAssetIndex(assetPaths);
            asset1 = assetIndex.objects().get("a/b/asset");
            asset2 = assetIndex.objects().get("b/c/asset");

            fakeLauncher1 = tempDir.resolve("fakeLauncher1");
            fakeLauncher2 = tempDir.resolve("fakeLauncher2");
            when(launcherInstallations.getAssetRoots()).thenReturn(List.of(fakeLauncher1, fakeLauncher2));

            // The first asset is present in launcher1, but is corrupted
            writeFile(fakeLauncher1.resolve("objects/" + asset1.getRelativePath()), "THIS IS NOT THE RIGHT CONTENT".getBytes());
            // The first asset is also present in launcher2, but with the right content
            writeFile(fakeLauncher2.resolve("objects/" + asset1.getRelativePath()), downloadableContent.get(asset1.getRelativePath()));
            // asset2 is present in neither launcher
        }

        @Test
        void testCopyingFromLauncherIsDisabled() throws Exception {
            var result = runDownloadAndValidateResult(false, false);

            // The returned root should be the NFRT root
            assertEquals(nfrtAssetsDir, result.assetRoot());

            // It should have downloaded both assets via the download manager
            assertThat(downloadedRelativePaths).containsExactlyInAnyOrder(
                    "asset_index.json", asset1.getRelativePath(), asset2.getRelativePath()
            );
        }

        @Test
        void testCopyingAsset1FromLauncherDir2() throws Exception {
            var result = runDownloadAndValidateResult(false, true);

            // The returned root should be the NFRT root
            assertEquals(nfrtAssetsDir, result.assetRoot());

            // It should have downloaded both assets via the download manager
            assertThat(downloadedRelativePaths).containsExactlyInAnyOrder(
                    "asset_index.json", asset2.getRelativePath()
            );
        }

        @Test
        void testReusingFirstAvailableLauncherAssetRoot() throws Exception {
            var result = runDownloadAndValidateResult(true, false);

            assertEquals(fakeLauncher1, result.assetRoot());

            // It should have downloaded both assets via the download manager
            // because while asset 1 is present in launcher1 asset folder, it has an unexpected size
            assertThat(downloadedRelativePaths).containsExactlyInAnyOrder(
                    "asset_index.json",
                    asset1.getRelativePath(),
                    asset2.getRelativePath()
            );
        }

        @Test
        void testReusingLauncherAssetRootThatAlreadyHasTheAssetIndex() throws Exception {
            when(launcherInstallations.getAssetDirectoryForIndex(ASSET_INDEX_ID)).thenReturn(fakeLauncher2);

            var result = runDownloadAndValidateResult(true, false);

            assertEquals(fakeLauncher2, result.assetRoot());

            // In the asset root of launcher 2, asset 1 exists and is already valid
            assertThat(downloadedRelativePaths).containsExactlyInAnyOrder(
                    "asset_index.json",
                    asset2.getRelativePath()
            );
        }

        private AssetDownloadResult runDownloadAndValidateResult(boolean useLauncherRoot, boolean copyFromLauncher) throws Exception {
            var result = downloader.downloadAssets(
                    MC_VERSION,
                    URI.create(BASE_URI),
                    useLauncherRoot,
                    copyFromLauncher,
                    1
            );
            validateAssetDownloadResult(result, assetIndex);
            return result;
        }
    }

    private AssetIndex generateAssetIndex(List<String> assetPaths) throws IOException {
        var objects = new HashMap<String, AssetObject>();
        for (String assetPath : assetPaths) {
            objects.put(assetPath, generateAsset());
        }
        var assetIndex = new AssetIndex(objects);
        setAssetIndex(assetIndex);
        return assetIndex;
    }

    private void setAssetIndex(AssetIndex assetIndex) throws IOException {
        var json = new Gson().toJson(assetIndex);
        downloadableContent.put("asset_index.json", json.getBytes(StandardCharsets.UTF_8));

        var assetIndexRef = new AssetIndexReference(
                ASSET_INDEX_ID,
                HashingUtil.sha1(json),
                json.length(),
                0,
                URI.create(BASE_URI + "asset_index.json")
        );
        var manifest = new MinecraftVersionManifest(
                MC_VERSION,
                Map.of(),
                List.of(),
                assetIndexRef,
                "",
                null,
                null,
                null
        );
        Files.writeString(versionManifestPath, new Gson().toJson(manifest));
    }

    private AssetObject generateAsset() {
        var assetId = ++assetCounter;
        var r = new Random(123);
        for (int i = 0; i < assetId; i++) {
            r.nextLong();
        }
        var assetR = new Random(r.nextLong());
        var size = assetR.nextInt(65535);
        var content = new byte[size];
        r.nextBytes(content);

        var sha1 = HashingUtil.sha1(content);
        var assetObject = new AssetObject(sha1, content.length);
        downloadableContent.put(assetObject.getRelativePath(), content);
        return assetObject;
    }

    private static void validateAssetDownloadResult(AssetDownloadResult result, AssetIndex expectedIndex) {
        // Just validates that both assets were downloaded correctly
        for (var entry : expectedIndex.objects().entrySet()) {
            var asset = entry.getValue();
            var targetPath = result.assetRoot().resolve("objects").resolve(asset.getRelativePath());
            assertThat(targetPath)
                    .describedAs("Original path: %s", entry.getKey())
                    .isRegularFile()
                    .hasDigest("SHA-1", asset.hash());
        }
    }

    private static void writeFile(Path p, byte[] content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, content);
    }

}

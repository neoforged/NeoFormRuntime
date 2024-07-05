# NeoForm Runtime (NFRT)

This project implements a standalone commandline interface to create artifacts used to compile mods against Minecraft.
It is usually used as part of a Gradle plugin.

It uses data from the [NeoForm project](https://github.com/neoforged/NeoForm) to deobfuscate, merge and patch the
sources and finally recompile them.

Since it is used as part of the NeoForge toolchain, it extends NeoForm by adding direct support to
apply [NeoForge](https://github.com/neoforged/NeoForge) patches and produces the necessary artifacts to compile against
the NeoForge APIs.

You'll find the [latest releases](https://projects.neoforged.net/neoforged/neoformruntime) on the NeoForged Project Listing.

## Usage

### run: Creating Minecraft Artifacts

This is the primary use of the NeoForm Runtime. For a given NeoForge or NeoForm version, it will build
an execution graph and allows the caller to retrieve various resulting artifacts from it.

Examples

```
# Produce NeoForge jars for 1.20.6

> nfrt run --dist joined --neoforge net.neoforged:neoforge:20.6.72-beta:userdev
No results requested. Available results: [compiled, clientResources, sources, serverResources]

> nfrt run --neoforge net.neoforged:neoforge:20.6.72-beta:userdev \
    --dist joined \
    --write-result=compiled:minecraft.jar \
    --write-result=clientResources:client-extra.jar \
    --write-result=sources:minecraft-sources.jar 

This produces the NeoForge userdev artifacts in build/

> nfrt run --neoform net.neoforged:neoform:1.20.6-20240429.153634@zip \
    --dist joined \
    --write-result=compiled:minecraft.jar \
    --write-result=clientResources:client-extra.jar \
    --write-result=sources:minecraft-sources.jar 

This produces the Vanilla artifacts in build/

```

| Option                              | Description                                                                                                                                                                                                                                             |
|-------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `--dist` [required]                 | Which distribution type to generate artifacts for. NeoForm defines these and usually `client`, `server` and `joined` are available.                                                                                                                     |
| `--neoforge=<gav>`                  | Pass the NeoForge artifact to use as `net.neoforged:neoforge:<version>`. When passing this, the NeoForm version is implied. It can still be overridden by passing `--neoform` as well.                                                                  |
| `--neoform=<gav>`                   | Pass the NeoForm artifact to use as `net.neoforged:neoform:<version>@zip`.                                                                                                                                                                              |
| `--write-result=<id>:<path>`        | This option can be passed multiple times. It tells NFRT to write a result of the execution graph to the given path, such as the recompiled Minecraft jar-file, or the sources. If you pass no such option, NFRT will print which results are available. |
| `--access-transformer=<path>`       | Adds access transformers which will be applied to the source before recompiling it.                                                                                                                                                                     |
| `--interface-injection-data=<path>` | Adds [interface injection data](https://github.com/neoforged/JavaSourceTransformer?tab=readme-ov-file#interface-injection) which will be applied to the source before recompiling it.                                                                   |
| `--repository=<uri>`                | Adds additional repositories that NFRT will use when it downloads artifacts. By default, the NeoForge repository and local Maven are used.                                                                                                              |
| `--launcher-meta-uri=<url>`         | Specifies a different URL to download the Launcher manifest from. The default is `https://launchermeta.mojang.com/mc/game/version_manifest_v2.json`                                                                                                     |                                                                                             |
| `--disable-cache`                   | Disables use of the intermediate result cache.                                                                                                                                                                                                          |
| `--print-graph`                     | Prints information about the execution graph used to create the artifacts.                                                                                                                                                                              |
| `--use-eclipse-compiler`            | When recompiling Minecraft sources, use the Eclipse compiler rather than javac. The Eclipse compiler is able to compile in parallel, while javac is single-threaded.                                                                                    |
| `--verbose`                         | Enables verbose output                                                                                                                                                                                                                                  |
| `--compile-classpath`               | Specify a classpath as you would with `-cp` for java, which is used to compile the sources. Without specifying this option, NFRT will automatically download the libraries used by Minecraft and NeoForm and use those as the compile classpath.        |

### download-assets: Download Minecraft Assets

The game needs a particular directory layout of its assets to run properly.
NFRT helps with this by downloading the assets required to run a particular version of the game.

```
# Download Assets for a specific version of Minecraft
nfrt download-assets --minecraft-version 1.20.6 --output-properties-to assets.properties

# Download Assets for the Minecraft version used by the given NeoForm version
nfrt download-assets --neoform net.neoforged:neoform:1.20.6-20240429.153634@zip --output-properties-to assets.properties

# Download Assets for the Minecraft version used by the given NeoForge version
nfrt download-assets --neoforge net.neoforged:neoforge:20.6.72-beta:userdev --output-properties-to assets.properties

# In all three cases, a properties file will be written to assets.properties containing the following,
# which can be used to pass the required command line arguments for starting a Minecraft client.
asset_index=16
assets_root=...path to assets...
```

While it may seem odd that NFRT supports passing NeoForm or NeoForge versions to this command, this is in service
of potential Gradle plugins never having to actually read and parse the NeoForm configuration file.

## Caches

NFRT has to store various files to speed up later runs. It does this in several cache
directories.

### Cache Directories

On Linux, NFRT will store its caches by default at `$XDG_CACHE_HOME/neoformruntime`. If that variable is not set or not an
absolute path, it falls back to `~/.cache/neoformruntime`.

For other operating systems (Windows, Mac), it defaults to `.neoformruntime` in your home directory.

Please note that Gradle plugins using this runtime may set different cache directories.

### Reusing Gradle Artifacts

To prevent NFRT from re-downloading all the libraries and artifacts **again** when it is being used through Gradle,
it supports passing an "artifact manifest". This property file maps from Maven coordinates to the full path of
those files on disk.

The path to this manifest is passed to NFRT via the `--artifact-manifest` command-line option.

Example:

```properties
net.neoforged.fancymodloader\:loader\:3.0.53-pr-54-junit=C\:\\Gradle Home\\caches\\modules-2\\files-2.1\\net.neoforged.fancymodloader\\loader\\3.0.53-pr-54-junit\\eacd6fc41449ff1dc84b1a4593c7e6c96599374f\\loader-3.0.53-pr-54-junit.jar
[...]
```

The Gradle plugin can prepare such a file to make NFRT use a local build of certain artifacts too in case includeBuild is used on the 
containing project.


## Example Execution Graphs

### NeoForge, Parchment, ATs

```mermaid
%%{init: {"flowchart": {"htmlLabels": false}} }%%
flowchart LR
  downloadManifest[[downloadManifest]]
  downloadJson[[downloadJson]]
  downloadServer[[downloadServer]]
  downloadJson-->|versionManifest|downloadServer
  listLibraries[[listLibraries]]
  downloadJson-->|versionManifest|listLibraries
  downloadClient[[downloadClient]]
  downloadJson-->|versionManifest|downloadClient
  downloadClientMappings[[downloadClientMappings]]
  downloadJson-->|versionManifest|downloadClientMappings
  extractServer[[extractServer]]
  downloadServer-->|downloadServerOutput|extractServer
  downloadServer-->|input|extractServer
  stripClient[[stripClient]]
  downloadClient-->|input|stripClient
  mergeMappings[[mergeMappings]]
  downloadClientMappings-->|downloadClientMappingsOutput|mergeMappings
  downloadClientMappings-->|official|mergeMappings
  stripServer[[stripServer]]
  extractServer-->|input|stripServer
  merge[[merge]]
  stripServer-->|server|merge
  stripServer-->|stripServerOutput|merge
  stripClient-->|client|merge
  stripClient-->|stripClientOutput|merge
  rename[[rename]]
  merge-->|mergeOutput|rename
  merge-->|input|rename
  listLibraries-->|listLibrariesOutput|rename
  mergeMappings-->|mappings|rename
  listLibraries-->|libraries|rename
  mergeMappings-->|mergeMappingsOutput|rename
  decompile[[decompile]]
  listLibraries-->|libraries|decompile
  rename-->|input|decompile
  listLibraries-->|listLibrariesOutput|decompile
  rename-->|renameOutput|decompile
  inject[[inject]]
  decompile-->|input|inject
  patch[[patch]]
  inject-->|input|patch
  applyNeoforgePatches[[applyNeoforgePatches]]
  patch-->|input|applyNeoforgePatches
  transformSources[[transformSources]]
  listLibraries-->|libraries|transformSources
  applyNeoforgePatches-->|input|transformSources
  recompile[[recompile]]
  transformSources-->|sources|recompile
  downloadJson-->|versionManifest|recompile
  sourcesWithNeoForge[[sourcesWithNeoForge]]
  transformSources-->|input|sourcesWithNeoForge
  mergeWithSources[[mergeWithSources]]
  transformSources-->|sources|mergeWithSources
  recompile-->|classes|mergeWithSources
  compiledWithNeoForge[[compiledWithNeoForge]]
  recompile-->|input|compiledWithNeoForge
  sourcesAndCompiledWithNeoForge[[sourcesAndCompiledWithNeoForge]]
  sourcesWithNeoForge-->|sources|sourcesAndCompiledWithNeoForge
  compiledWithNeoForge-->|classes|sourcesAndCompiledWithNeoForge
  result-compiled("`**Result**
compiled`")
  recompile --o result-compiled
  result-clientResources("`**Result**
clientResources`")
  stripClient --o result-clientResources
  result-sources("`**Result**
sources`")
  transformSources --o result-sources
  result-compiledWithNeoForge("`**Result**
compiledWithNeoForge`")
  compiledWithNeoForge --o result-compiledWithNeoForge
  result-compiledWithSources("`**Result**
compiledWithSources`")
  mergeWithSources --o result-compiledWithSources
  result-serverResources("`**Result**
serverResources`")
  stripServer --o result-serverResources
  result-sourcesAndCompiledWithNeoForge("`**Result**
sourcesAndCompiledWithNeoForge`")
  sourcesAndCompiledWithNeoForge --o result-sourcesAndCompiledWithNeoForge
  result-sourcesWithNeoForge("`**Result**
sourcesWithNeoForge`")
  sourcesWithNeoForge --o result-sourcesWithNeoForge
```
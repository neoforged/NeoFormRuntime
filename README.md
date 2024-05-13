# NeoForm Runtime

This project implements a standalone commandline interface to create artifacts used to compile mods against Minecraft.
It is usually used as part of a Gradle plugin.

It uses data from the [NeoForm project](https://github.com/neoforged/NeoForm) to deobfuscate, merge and patch the
sources and finally recompile them.

Since it is used as part of the NeoForge toolchain, it extends NeoForm by adding direct support to
apply [NeoForge](https://github.com/neoforged/NeoForge) patches and produce the necessary artifacts to compile against
the NeoForge APIs.

## Examples

```
--use-eclipse-compiler --neoform "net.neoforged:neoform:1.20.6-20240429.153634@zip" --dist joined
```

## Usage

```
Usage: neoform [-hV] [--print-graph] [--recompile] [--recompile-ecj]
               [--cache-dir=<cacheDir>] --dist=<dist>
               [--launcher-meta-uri=<launcherManifestUrl>] [--repository
               [=<repositories>...]]... ([--neoform=<neoform>]
               [--neoforge=<neoforge>])
      --cache-dir=<cacheDir>

      --dist=<dist>
  -h, --help                Show this help message and exit.
      --launcher-meta-uri=<launcherManifestUrl>

      --neoforge=<neoforge>
      --neoform=<neoform>
      --print-graph
      --recompile
      --recompile-ecj
      --repository[=<repositories>...]

  -V, --version             Print version information and exit.
```

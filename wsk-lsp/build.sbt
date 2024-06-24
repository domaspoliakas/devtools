import bindgen.interface.Platform.OS.*
import bindgen.interface.Platform
import bindgen.interface.Binding
import bindgen.interface.LogLevel
import java.nio.file.Paths

Global / onChangedBuildSource := ReloadOnSourceChanges

resolvers ++= Resolver.sonatypeOssRepos("snapshots")

lazy val `wsk-lsp` =
  project
    .in(file("."))
    .enablePlugins(ScalaNativePlugin, BindgenPlugin)
    .settings(
      name := "WSK LSP",
      scalaVersion := "3.2.0",
      // Generate bindings to Tree Sitter's main API
      libraryDependencies ++= Seq(
        "org.typelevel" %%% "cats-core" % "2.8.0",
        "co.fs2" %%% "fs2-io" % "3.3.0",
        "tech.neander" %%% "jsonrpclib-fs2" % "0.0.4",
        "tech.neander" %%% "langoustine-lsp" % "0.0.17",
        "tech.neander" %%% "langoustine-app" % "0.0.17",
        "org.typelevel" %%% "keypool" % "0.4.8"
        /* "com.lihaoyi" %%% "os-lib" % "0.8.1" */
      ),
      bindgenBindings += {
        Binding(
          baseDirectory.value / "tree-sitter" / "lib" / "include" / "tree_sitter" / "api.h",
          "treesitter",
          cImports = List("tree_sitter/api.h"),
          clangFlags = List("-std=gnu99")
        )
      },
      // Copy generated Json parser
      Compile / resourceGenerators += Def.task {
        val jsonParserLocation =
          baseDirectory.value / "tree-sitter-json" / "src"

        val resourcesFolder = (Compile / resourceManaged).value / "scala-native"

        val fileNames = List(
          "parser.c"
          // "scanner.c"
        )

        fileNames.foreach { fileName =>
          IO.copyFile(jsonParserLocation / fileName, resourcesFolder / fileName)
        }

        fileNames.map(fileName => resourcesFolder / fileName)
      },
      nativeConfig := {
        val base = baseDirectory.value / "tree-sitter"
        val conf = nativeConfig.value
        val staticLib = base / "libtree-sitter.a"

        conf
          .withLinkingOptions(
            conf.linkingOptions ++ List(
              staticLib.toString
            )
          )
          .withCompileOptions(
            conf.compileOptions ++ List(s"-I${base / "lib" / "include"}")
          )
      }
    )

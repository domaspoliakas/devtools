package dpol

import java.io.*

import langoustine.lsp.LSPBuilder

import cats.syntax.all.*
import cats.Show

import langoustine.lsp.all.*
// import dpol.treesitter.Treesitter
import cats.effect.*
import jsonrpclib.fs2.*
import fs2.*
import fs2.io.file.Files
import fs2.io.file.Path
import scala.scalanative.unsafe.Zone
import dpol.treesitter.Treesitter
import langoustine.lsp.app.LangoustineApp

object Main extends LangoustineApp:
  override def server(args: List[String]): Resource[IO, LSPBuilder[IO]] = 
    Treesitter[IO].flatMap(LSP.server(_))

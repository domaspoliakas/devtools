package dpol

import cats.syntax.all.*
import cats.effect.*
import cats.effect.syntax.all.*
import cats.effect.std.Console
import langoustine.lsp.requests.*
import langoustine.lsp.aliases.*
import langoustine.lsp.structures.*
import langoustine.lsp.enumerations.*
import langoustine.lsp.runtime.*
import langoustine.lsp.LSPBuilder
import langoustine.lsp.Invocation
import jsonrpclib.fs2.*
import dpol.{treesitter as ts}
import scala.scalanative.unsafe.Zone
import cats.Functor
import cats.Applicative
import cats.Monad
import cats.MonadThrow
import cats.effect.std.Hotswap
import scala.collection.mutable.StringBuilder.apply
import scala.scalanative.unsigned.*
import org.typelevel.keypool.*
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import java.util.UUID

object LSP:
  def server[F[_]: Console](
      treesitter: ts.Treesitter[F]
  )(using F: Async[F]): Resource[F, LSPBuilder[F]] =

    val configSchemaQueryResource = Resource
      .eval(
        ts.Query
          .create[F](TestData.configSchemaDefinitionQuery)
          .leftMap(new Exception(_))
          .liftTo[F]
      )
      .flatten

    val idAndNameRequestQuery = Resource
      .eval(
        ts.Query
          .create[F](TestData.idAndNameRequestQuery)
          .leftMap(new Exception(_))
          .liftTo[F]
      )
      .flatten

    val nameAndIdRequestQuery = Resource
      .eval(
        ts.Query
          .create[F](TestData.nameAndIdRequestQuery)
          .leftMap(new Exception(_))
          .liftTo[F]
      )
      .flatten

    val errorQuery = Resource
      .eval(
        ts.Query
          .create[F](TestData.errorQuery)
          .leftMap(new Exception(_))
          .liftTo[F]
      )
      .flatten

    val statePool = KeyPool
      .Builder((_: DocumentUri) => DocumentState.create[F])
      .withIdleTimeAllowedInPool(FiniteDuration(292 * 364, TimeUnit.DAYS))
      .withMaxPerKey(_ => 1)
      .withMaxIdle(Int.MaxValue)
      .withMaxTotal(Int.MaxValue)
      .build

    (
      configSchemaQueryResource,
      idAndNameRequestQuery,
      nameAndIdRequestQuery,
      errorQuery,
      statePool
    ).mapN {
      (
          configSchemaQuery,
          idAndNameQuery,
          nameAndIdQuery,
          errorQuery,
          documentStatePool
      ) =>
          // format: off
          LSPBuilder
            .create[F]
            .handleRequest(initialize)(initializeHandler)
            .handleRequest(textDocument.documentSymbol)(documentSymbolHandler(documentStatePool, configSchemaQuery, idAndNameQuery, nameAndIdQuery))
            .handleRequest(textDocument.hover)(hoverHandler(documentStatePool)) 
            .handleRequest(textDocument.definition)(definitionHandler(documentStatePool, idAndNameQuery, nameAndIdQuery)) 
            .handleNotification(textDocument.didOpen)(didOpenHandler(treesitter, documentStatePool))
            .handleNotification(textDocument.didChange)(didChangeHandler(documentStatePool, errorQuery, treesitter))
            .handleNotification(textDocument.didClose)(didCloseHandler(documentStatePool))
          // format: on

    }

  def initializeHandler[F[_]: Functor]
      : Invocation[InitializeParams, F] => F[InitializeResult] =
    in =>
      in.toClient
        .notification(
          window.showMessage,
          ShowMessageParams(
            message = "WSK LSP initialized",
            `type` = MessageType.Info
          )
        )
        .map { _ =>
          InitializeResult(
            ServerCapabilities(
              hoverProvider = Opt(true),
              documentSymbolProvider = Opt(true),
              definitionProvider = Opt(true),
              textDocumentSync = Opt(
                TextDocumentSyncOptions(
                  openClose = Opt(true),
                  change = Opt(TextDocumentSyncKind.Full)
                )
              ),
              diagnosticProvider = Opt(
                DiagnosticRegistrationOptions(
                  documentSelector = Opt.empty,
                  identifier = Opt("wsk-lsp"),
                  interFileDependencies = false,
                  workspaceDiagnostics = false
                )
              )
            ),
            Opt(
              InitializeResult.ServerInfo(
                name = "WSK LSP",
                version = Opt("0.0.1")
              )
            )
          )
        }

  def hoverHandler[F[_]: Concurrent](
      documentState: KeyPool[F, DocumentUri, DocumentState[F]]
  ): Invocation[HoverParams, F] => F[Opt[Hover]] = in => {
    documentState
      .take(in.params.textDocument.uri)
      .use(_.value.treeAndText.flatMap { n =>

        val line = in.params.position.line
        val col = in.params.position.character

        val p = ts.Point(line.value, col.value)

        n.fold(Applicative[F].pure("Nothing here")) { (tree, fullText) =>
          tree.rootNode
            .flatMap(
              _.in(ts.Range(p, p))
                .use(nodeOnCursor =>
                  Applicative[F].pure(nodeOnCursor.text(fullText))
                )
            )
        }.map { string =>
          Opt(
            Hover(
              contents = MarkupContent(
                kind = MarkupKind.Markdown,
                value = string
              )
            )
          )
        }

      })
  }

  def didChangeHandler[F[_]: Console](
      documentStatePool: KeyPool[F, DocumentUri, DocumentState[F]],
      errorQuery: ts.Query[F],
      treesitter: ts.Treesitter[F]
  )(using
      F: Concurrent[F]
  ): Invocation[DidChangeTextDocumentParams, F] => F[Unit] =
    in => {
      documentStatePool.take(in.params.textDocument.uri).use { managed =>
        in.params.contentChanges
          .collect {
            case langoustine.lsp.aliases.TextDocumentContentChangeEvent.S1(
                  fullText
                ) =>
              fullText
          }
          .lastOption
          .traverse_(newFullText =>
            managed.value.update(
              treesitter.parse(newFullText).tupleRight(newFullText)
            )
          ) >> managed.value.treeAndText.flatMap {
          case None => F.unit
          case Some((tree, text)) =>
            tree.rootNode.flatMap(rootNode =>
              errorQuery
                .matchesIn(rootNode, text)
                .map { case ts.QueryMatch(captures) =>
                  captures.get("error").map { error =>
                    Diagnostic(
                      range = error.range.toLspRange,
                      message = "Syntax error",
                      severity = Opt(DiagnosticSeverity.Error)
                    )
                  }
                }
                .unNone
                .compile
                .toVector
                .flatMap { diag =>
                  in.toClient.notification(
                    textDocument.publishDiagnostics,
                    PublishDiagnosticsParams(
                      in.params.textDocument.uri,
                      diagnostics = diag
                    )
                  )
                }
            )

        }

      }
    }

  def didOpenHandler[F[_]](
      treesitter: ts.Treesitter[F],
      statePool: KeyPool[F, DocumentUri, DocumentState[F]]
  )(using
      F: Concurrent[F]
  ): Invocation[DidOpenTextDocumentParams, F] => F[Unit] = in => {
    val text = in.params.textDocument.text
    val uri = in.params.textDocument.uri
    val managedState = statePool.take(uri)
    managedState.use { managed =>
      val state = managed.value
      managed.canBeReused.set(Reusable.Reuse) >>
        state.update(treesitter.parse(text).tupleRight(text))
    }
  }

  def documentSymbolHandler[F[_]](
      statePool: KeyPool[F, DocumentUri, DocumentState[F]],
      configSchema: ts.Query[F],
      idAndNameRequestQuery: ts.Query[F],
      nameAndIdRequestQuery: ts.Query[F]
  )(using F: Concurrent[F]): Invocation[DocumentSymbolParams, F] => F[Opt[
    (Vector[SymbolInformation] | Vector[DocumentSymbol])
  ]] = in => {
    statePool.take(in.params.textDocument.uri).use { state =>
      state.value.treeAndText.flatMap {
        case None => F.pure(Opt(Vector.empty))
        case Some((tree, fullText)) =>
          tree.rootNode
            .flatMap { rootNode =>
              (
                (idAndNameRequestQuery.matchesIn(
                  rootNode,
                  fullText
                ) ++ nameAndIdRequestQuery.matchesIn(rootNode, fullText)).map {
                  case ts.QueryMatch(captures) =>
                    (
                      captures.get("request"),
                      captures.get("name_value")
                    ).mapN { (requestNode, idNode) =>
                      val id = idNode.text(fullText)
                      DocumentSymbol(
                        name = id,
                        kind = SymbolKind.Class,
                        range = requestNode.range.toLspRange,
                        selectionRange = idNode.range.toLspRange
                      )
                    }
                }.unNone ++
                  configSchema
                    .matchesIn(rootNode, fullText)
                    .map { case ts.QueryMatch(captures) =>
                      (
                        captures.get("config_schema_variable"),
                        captures.get("field_description")
                      )
                        .mapN { (variableNameNode, fieldDescriptionNode) =>
                          val name = variableNameNode.text(fullText)

                          DocumentSymbol(
                            name = name,
                            detail = Opt(fieldDescriptionNode.text(fullText)),
                            kind = SymbolKind.Variable,
                            range = variableNameNode.range.toLspRange,
                            selectionRange = variableNameNode.range.toLspRange
                          )

                        }
                    }
                    .unNone
              ).compile.toVector
                .map(Opt(_))
            }

      }
    }
  }

  def didCloseHandler[F[_]: MonadCancelThrow](
      statePool: KeyPool[F, DocumentUri, DocumentState[F]]
  ): Invocation[DidCloseTextDocumentParams, F] => F[Unit] = in => {
    statePool
      .take(in.params.textDocument.uri)
      .use(_.canBeReused.set(Reusable.DontReuse))
  }

  def definitionHandler[F[_]](
      statePool: KeyPool[F, DocumentUri, DocumentState[F]],
      idAndNameRequestQuery: ts.Query[F],
      nameAndIdRequestQuery: ts.Query[F]
  )(using F: Concurrent[F]): Invocation[DefinitionParams, F] => F[
    Opt[(Definition | Vector[DefinitionLink])]
  ] = in => {
    statePool
      .take(in.params.textDocument.uri)
      .use(_.value.treeAndText.flatMap { n =>

        val line = in.params.position.line
        val col = in.params.position.character

        val p = ts.Point(line.value, col.value)

        n.fold(Applicative[F].pure(Opt.empty)) { (tree, fullText) =>
          tree.rootNode
            .flatMap(rootNode =>
              rootNode
                .in(ts.Range(p, p))
                .use(nodeOnCursor =>
                  Either.catchOnly[java.lang.IllegalArgumentException](
                    UUID.fromString(nodeOnCursor.text(fullText))
                  ) match {
                    case Left(err) =>
                      F.pure(Opt.empty)
                    case Right(uuid) =>
                      (idAndNameRequestQuery.matchesIn(
                        rootNode,
                        fullText
                      ) ++ nameAndIdRequestQuery.matchesIn(rootNode, fullText))
                        .collectFirst(Function.unlift { queryMatch =>
                          queryMatch.captures
                            .get("id_value")
                            .filter(idNode =>
                              Either
                                .catchOnly[java.lang.IllegalArgumentException](
                                  UUID.fromString(idNode.text(fullText))
                                )
                                .toOption
                                .contains(uuid)
                            )
                            .map { idNode =>
                              Definition(
                                Location(
                                  in.params.textDocument.uri,
                                  idNode.range.toLspRange
                                )
                              )
                            }

                        })
                        .take(1)
                        .compile
                        .last
                        .map {
                          case None    => Opt.empty
                          case Some(l) => Opt(l)
                        }

                  }
                )
            )
        }

      })

  }

  extension (r: ts.Range)
    def toLspRange: Range = {
      Range(
        start = Position(r.start.row, r.start.col),
        end = Position(r.end.row, r.end.col)
      )
    }

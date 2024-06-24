package dpol.treesitter

import treesitter.types.*
import treesitter.functions.*
import scala.scalanative.unsafe.*
import scala.scalanative.libc.string.strlen
import cats.syntax.all.*
import cats.effect.*
import dpol.ZoneResource

private[treesitter] def tree_sitter_json(): Ptr[TSLanguage] = extern

trait Treesitter[F[_]]:
  def parse(in: String): Resource[F, Tree[F]]

  /**
    * Treesitter rules dictate that `oldTree` needs to have been updated
    * using the [[Tree.edit]] function to take account of the changes, 
    * otherwise this won't work as expected.
    *
    * @param oldTree
    * @param newText
    * @return
    */
  def reparse(oldTree: Tree[F], newFullText: String): Resource[F, Tree[F]]

object Treesitter:

  case class ParsingError(message: String)

  def apply[F[_]](using F: Sync[F]): Resource[F, Treesitter[F]] =
    ZoneResource[F].flatMap(zone =>

      for {
        parser <- Resource.make(F.delay(ts_parser_new()))(parser => F.delay(ts_parser_delete(parser)))
        jsonParser <- Resource.eval(F.delay(tree_sitter_json()))
        _ <- Resource.eval(F.delay(ts_parser_set_language(parser, jsonParser)))
      } yield new:
        def parse(in: String): Resource[F, Tree[F]] =

          val tree =
            F.delay {
              Zone { localZone =>
                val inC = toCString(in)(localZone)
                ts_parser_parse_string(
                  parser,
                  null,
                  inC,
                  strlen(inC).toUInt
                )
              }
            }

          Resource
            .make(tree)(tree => F.delay(ts_tree_delete(tree)))
            .flatMap(tree => Tree(tree))

        def reparse(oldTree: Tree[F], newFullText: String): Resource[F, Tree[F]] = 
          val tree = F.delay {
            Zone { implicit localZone => 
              val inC = toCString(newFullText)
              ts_parser_parse_string(parser, oldTree match { case Tree.Impl(tree) => tree }, inC, strlen(inC).toUInt)
            }
          }

          Resource.make(tree)(tree => F.delay(ts_tree_delete(tree))).flatMap(Tree(_))
    )


    

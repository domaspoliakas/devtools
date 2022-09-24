package dpol.treesitter

import treesitter.types.*
import cats.Show
import scala.scalanative.unsafe.fromCString
import scala.scalanative.unsigned.*
import scala.scalanative.unsafe.Zone
import treesitter.functions.*
import cats.effect.Resource
import dpol.ZoneResource
import cats.effect.Sync
import cats.syntax.all.*

sealed trait Node[F[_]]:
  def in(range: Range): Resource[F, Node[F]]
  def text(fullText: String): String
  def range: Range

object Node:
  def apply[F[_]: Sync](node: TSNode): Node[F] =
    Impl[F](node)

  private[treesitter] case class Impl[F[_]](node: TSNode)(using F: Sync[F])
      extends Node[F]:
    def in(range: Range): Resource[F, Node[F]] =
      ZoneResource[F].evalMap { nodeZone =>
        ZoneResource[F].use { tempZone =>

          val start = {
            given Zone = tempZone
            TSPoint(range.start.row.toUInt, range.start.col.toUInt)
          }
          val end = {
            given Zone = tempZone
            TSPoint(range.end.row.toUInt, range.end.col.toUInt)
          }

          {
            given Zone = nodeZone
            F.delay(ts_node_descendant_for_point_range(node, !start, !end))
              .map(apply[F](_))
          }

        }
      }

    def text(fullText: String): String = 
      Zone { implicit zone => 
        val startByte = ts_node_start_byte(node)
        val endByte = ts_node_end_byte(node)
        new String(fullText.getBytes().slice(startByte.toInt, endByte.toInt))
      }

    def range: Range = 
      Zone { implicit zone => 
        val startPoint = ts_node_start_point(node)
        val endPoint = ts_node_end_point(node)

        Range.fromTSPoints(startPoint, endPoint)

      }


  given [F[_]]: Show[Node[F]] = Show { case Impl(node) =>
    showChildren(node)
  }

  private def showChildren(start: TSNode): String =
    Zone { implicit z =>
      def go(node: TSNode, level: Int): String =
        val nodeType = fromCString(ts_node_type(node))
        val startPoint = ts_node_start_point(node)
        val endPoint = ts_node_end_point(node)
        var out = s"${" " * level}$nodeType [${startPoint.row.toInt},${startPoint.column.toInt}] - [${endPoint.row.toInt},${endPoint.column.toInt}]\n"

        // asInstanceOf is a bug in codegen
        val childrenCount = ts_node_child_count(node)
        if childrenCount != 0.toUInt then
          for childId <- 0 until childrenCount.toInt do
            val childNode =
              ts_node_child(node, childId.toUInt)
            out = s"$out${go(childNode, level + 1)}"
        out
      end go

      go(start, 0)
    }

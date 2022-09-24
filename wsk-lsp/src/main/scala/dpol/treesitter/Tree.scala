package dpol.treesitter

import treesitter.types.TSTree
import treesitter.functions.*
import cats.effect.Sync
import cats.syntax.all.*
import scala.scalanative.unsafe.Zone
import scala.scalanative.unsafe.Ptr
import scala.scalanative.unsigned.*
import treesitter.types.TSInputEdit
import dpol.ZoneResource

sealed trait Tree[F[_]]:
  def rootNode: F[Node[F]]
  def edit(startByte: Int, oldEndByte: Int, newEndByte: Int, startPoint: Point, oldEndPoint: Point, newEndPoint: Point): F[Unit]

object Tree:
  // we use the zone of the parent tree
  private[treesitter] case class Impl[F[_]](tree: Ptr[TSTree])(using F: Sync[F], zone: Zone) extends Tree[F]:
    def rootNode: F[Node[F]] = 
      F.delay(Node(ts_tree_root_node(tree)))
    def edit(startByte: Int, oldEndByte: Int, newEndByte: Int, startPoint: Point, oldEndPoint: Point, newEndPoint: Point): F[Unit] =
      F.delay {
        Zone{ implicit z => 
          val inputEdit = TSInputEdit(
            startByte.toUInt,
            oldEndByte.toUInt, 
            newEndByte.toUInt, 
            !(startPoint.toTSPoint), 
            !(oldEndPoint.toTSPoint), 
            !(newEndPoint.toTSPoint)
          )
          ts_tree_edit(tree, inputEdit)
        }
      }

  private[treesitter] def apply[F[_]: Sync](tree: Ptr[TSTree]) = 
    ZoneResource[F].map(implicit z => Tree.Impl(tree))


  

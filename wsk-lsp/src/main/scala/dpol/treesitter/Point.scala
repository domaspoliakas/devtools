package dpol.treesitter

import treesitter.types.TSPoint
import scala.scalanative.unsafe.Zone
import scala.scalanative.unsafe.Ptr
import scala.scalanative.unsigned.*

final case class Point(row: Int, col: Int):
  private[treesitter] def toTSPoint(using Zone): Ptr[TSPoint] =
    TSPoint(row.toUInt, col.toUInt)

object Point:
  private[treesitter] def fromTSPoint(tsPoint: TSPoint): Point = 
    Point(tsPoint.row.toInt, tsPoint.column.toInt)

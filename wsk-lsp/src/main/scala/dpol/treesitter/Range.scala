package dpol.treesitter

import treesitter.types.TSPoint

final case class Range(start: Point, end: Point)

object Range:
  private[treesitter] def fromTSPoints(start: TSPoint, end: TSPoint): Range = 
    Range(
      Point.fromTSPoint(start),
      Point.fromTSPoint(end)
    )
  

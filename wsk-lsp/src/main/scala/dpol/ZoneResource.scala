package dpol

import cats.effect.Resource
import cats.effect.Sync
import scala.scalanative.unsafe.Zone

object ZoneResource:
  def apply[F[_]](using F: Sync[F]): Resource[F, Zone] =
    Resource.make(F.delay(Zone.open()))(zone => F.delay(zone.close()))

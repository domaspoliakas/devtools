package dpol

import dpol.treesitter.Tree
import cats.effect.Resource
import cats.effect.std.Hotswap
import cats.effect.std.Semaphore
import cats.effect.Async
import cats.syntax.all.*
import cats.data.OptionT

trait DocumentState[F[_]]:
  def tree: F[Option[Tree[F]]]
  def fullText: F[Option[String]]
  def treeAndText: F[Option[(Tree[F], String)]]
  def update(f: Resource[F, (Tree[F], String)]): F[Unit]

object DocumentState:
  def create[F[_]](using F: Async[F]): Resource[F, DocumentState[F]] = 
    (
      Hotswap.create[F, (Tree[F], String)],
      Resource.eval(Semaphore[F](1)),
    ).mapN((hotswap, semaphore) => 
      new DocumentState[F]:
        var treeVar: Option[Tree[F]] = None
        var textVar: Option[String] = None

        def tree: F[Option[Tree[F]]] =
          semaphore.permit.use { _ => 
            F.delay(treeVar)
          }

        def fullText: F[Option[String]] = 
          semaphore.permit.use { _ => 
            F.delay(textVar)
          }

        def treeAndText: F[Option[(Tree[F], String)]] = 
          (OptionT(tree), OptionT(fullText)).tupled.value


        def update(f: Resource[F, (Tree[F], String)]): F[Unit] =
          semaphore.permit.use { _ => 
            hotswap.swap(f).flatMap { case (tree, text) => 
              F.delay {
                treeVar = Some(tree)
                textVar = Some(text)
              }
            }
          }
      )


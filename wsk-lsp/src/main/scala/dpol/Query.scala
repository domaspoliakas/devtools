package dpol.treesitter

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.scalanative.libc.string.strlen
import scala.scalanative.libc.stdio

import cats.syntax.all.*
import treesitter.functions.*
import treesitter.types.*
import cats.effect.*
import fs2.*
import scala.annotation.nowarn
import dpol.ZoneResource

case class QueryMatch[F[_]](captures: Map[String, Node[F]])

trait Query[F[_]]:
  def matchesIn(node: Node[F], source: String): Stream[F, QueryMatch[F]]

object Query:

  def create[F[_]](queryString: String)(using
      F: Sync[F]
  ): Either[String, Resource[F, Query[F]]] =
    Zone { implicit localZone =>

      val queryStringC = toCString(queryString)

      // where the error is
      val err: Ptr[uint32_t] = alloc[uint32_t](1)

      // the error
      val errType: Ptr[TSQueryError] = alloc[TSQueryError](1)

      val query = ts_query_new(
        tree_sitter_json(),
        queryStringC,
        strlen(queryStringC).toUInt,
        err,
        errType
      )

      if !err == 0.toUInt && !errType == 0.toUInt then

        val q: Either[String, QueryState[F]] = captureQueryState(query)

        q match {
          case Left(error) => {
            ts_query_delete(query)
            Left(error)
          }
          case Right(queryState) =>
            Right(
              Resource.make(F.pure(queryState))(_ =>
                F.delay(ts_query_delete(query))
              )
            )
        }
      else Left("Could not parse query")
    }

  private case class QueryState[F[_]: MonadCancelThrow](
      query: Ptr[TSQuery],
      captureNames: List[CString],
      captureQuantifiers: List[List[TSQuantifier]],
      stringValues: List[CString],
      patternPredicates: List[List[Predicate]]
  )(using F: Sync[F])
      extends Query[F] {
    def matchesIn(node: Node[F], fullSource: String): Stream[F, QueryMatch[F]] = {
      val tsnode = node match { case Node.Impl(tsnode) => tsnode }

      val cursorR = Resource.make(F.delay(ts_query_cursor_new()))(cursor =>
        F.delay(ts_query_cursor_delete(cursor))
      )

      val zoneR = ZoneResource[F]

      Stream
        .resource(
          (cursorR, zoneR).mapN { (queryCursor, zone) =>
            {
              {
                given Zone = zone
                ts_query_cursor_exec(queryCursor, query, tsnode)
              }

              val queryMatchPtr: Ptr[TSQueryMatch] = {
                given Zone = zone
                TSQueryMatch.apply()
              }

              (!queryMatchPtr).pattern_index

              Stream
                .repeatEval(
                  F.delay(
                    ts_query_cursor_next_match(queryCursor, queryMatchPtr)
                  )
                )
                .takeWhile(identity)
                .filter(_ => checkPredicates(queryMatchPtr, fullSource))
                .map { _ =>
                  val queryMatch = !queryMatchPtr

                  val queryMatchCaptureCount = queryMatch.capture_count
                  val queryMatchCaptures = queryMatch.captures

                  val l = for (i <- 0 until queryMatchCaptureCount.toInt) yield 
                    val capture = (!(queryMatchCaptures + i))
                    val captureNode = Node(capture.node)
                    val captureName = fromCString(captureNames(capture.index.toInt))
                    captureName -> captureNode

                  QueryMatch(l.toMap)
                }
            }
          }
        )
        .flatten

    }

    private def checkPredicates(
        queryMatchPtr: Ptr[TSQueryMatch],
        fullSource: String
    ): Boolean = {
      val queryMatch = !queryMatchPtr
      val index = queryMatch.pattern_index

      val queryMatchCaptureCount = queryMatch.capture_count
      val queryMatchCaptures = queryMatch.captures

      def findNodeForCapture(index: uint32_t): Option[TSNode] = {
        val captureCountInt = queryMatchCaptureCount.toInt
        def go(cur: Int): Option[TSNode] =
          if (cur >= captureCountInt) None
          else {
            val currentCapturePtr = (queryMatchCaptures + cur)
            val currentCapture = (!currentCapturePtr)
            if (currentCapture.index == index) {
              Some(currentCapture.node)
            } else {
              go(cur + 1)
            }
          }
        go(0)
      }

      patternPredicates(index.toInt).forall {
        case Predicate.CaptureEqCapture(oneId, twoId, pos) =>
          (findNodeForCapture(oneId), findNodeForCapture(twoId))
            .mapN { (one, two) =>
              (Node(one).text(fullSource) == Node(two).text(fullSource)) == pos
            }
            .getOrElse(true)
        case Predicate.CaptureEqString(id, string, pos) =>
          findNodeForCapture(id)
            .map { one =>
              (Node(one).text(fullSource) == string) == pos
            }
            .getOrElse(true)
      }

    }
  }

  private def captureQueryState[F[_]: Sync](query: Ptr[TSQuery])(using
      Zone
  ): Either[String, QueryState[F]] = {
    val stringCount = ts_query_string_count(query)
    val captureCount = ts_query_capture_count(query)
    val patternCount = ts_query_pattern_count(query)

    val captureNames = for (i <- 0 until captureCount.toInt) yield {
      val length = alloc[uint32_t](1)
      val name =
        ts_query_capture_name_for_id(query, i.toUInt, length)
      name
    }

    // pattern - capture
    val captureQuantifiers =
      for (patternIndex <- 0 until patternCount.toInt) yield {
        for (captureIndex <- 0 until captureCount.toInt) yield {
          ts_query_capture_quantifier_for_id(
            query,
            patternIndex.toUInt,
            captureIndex.toUInt
          )
        }
      }

    val stringValues = (for (i <- 0 until stringCount.toInt) yield {
      val length = alloc[uint32_t](1)
      ts_query_string_value_for_id(query, i.toUInt, length)
    }).toList

    val patternPredicates =
      for (i <- 0 until patternCount.toInt) yield {

        val length = alloc[uint32_t](1)
        val predicateStepsRaw =
          ts_query_predicates_for_pattern(query, i.toUInt, length)

        val typeDone =
          TSQueryPredicateStepType.TSQueryPredicateStepTypeDone
        val typeCapture =
          TSQueryPredicateStepType.TSQueryPredicateStepTypeCapture
        val typeString =
          TSQueryPredicateStepType.TSQueryPredicateStepTypeString

        val predicateSteps =
          for (i <- 0 until (!length).toInt)
            yield predicateStepsRaw + i

        @nowarn
        val predicateChains = predicateSteps.toList.foldLeft(
          List(Nil): List[List[Ptr[TSQueryPredicateStep]]]
        ) { case (h :: t, next) =>
          val nextDer = (!next)
          if nextDer.`type` == typeDone then Nil :: (next :: h).reverse :: t
          else (next :: h) :: t
          end if
        }

        val predicates = predicateChains
          .collect(Function.unlift {
            case (h :: t) =>
              if ((!h).`type` != typeString) {
                throw new Exception("Bad start on predicate")
              }

              val operatorNameC = stringValues((!h).value_id.toInt)

              val pred = fromCString(operatorNameC) match {
                case op @ ("eq?" | "not-eq?") =>
                  t match {
                    case capture :: captureOrString :: _ :: Nil =>
                      if ((!capture).`type` != typeCapture) {
                        Left(
                          s"First argument to #$op predicate must be a capture name. Got literal ${fromCString(stringValues((!capture).value_id.toInt))}"
                        )
                      } else {
                        val isPositive = op == "eq?"

                        if (!captureOrString).`type` == typeCapture
                        then
                          Right(
                            Predicate.CaptureEqCapture(
                              (!capture).value_id,
                              (!captureOrString).value_id,
                              isPositive
                            )
                          )
                        else
                          Right(
                            Predicate.CaptureEqString(
                              (!capture).value_id,
                              fromCString(
                                stringValues(
                                  (!captureOrString).value_id.toInt
                                )
                              ),
                              isPositive
                            )
                          )
                        end if

                      }

                    case other =>
                      Left(
                        s"Wrong number of arguments to #$op predicate. Expected 2, got ${t.size}"
                      )
                  }

                case other =>
                  Left(s"dunno what to do with $other")
              }

              Some(pred)

            case Nil =>
              None

          })
          .sequence

        predicates

      }

    patternPredicates.toList.sequence.map(p =>
      QueryState(
        query,
        captureNames.toList,
        captureQuantifiers.toList.map(_.toList),
        stringValues,
        p
      )
    )
  }

end Query

private[treesitter] enum Predicate:
  case CaptureEqCapture(
      captureOneId: UInt,
      captureTwoId: UInt,
      isPositive: Boolean
  )
  case CaptureEqString(captureId: UInt, string: String, isPositive: Boolean)

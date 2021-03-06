/*
 * Copyright 2014–2017 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.mongodb.planner

import slamdata.Predef._
import quasar._, Planner._, Type.{Const => _, _}
import quasar.contrib.matryoshka._
import quasar.contrib.pathy.AFile
import quasar.ejson.implicits._
import quasar.fp._
import quasar.fs.{FileSystemError, MonadFsErr}, FileSystemError.qscriptPlanningFailed
import quasar.physical.mongodb.planner.common._
import quasar.qscript._

import matryoshka.{Hole => _, _}
import matryoshka.data._
import matryoshka.implicits._
import matryoshka.patterns._
import scalaz._, Scalaz.{ToIdOps => _, _}

object assumeReadType {

  object SRT {
    def unapply[T[_[_]], A](qt: QScriptTotal[T, A]): Option[ShiftedRead[AFile]] =
      Inject[Const[ShiftedRead[AFile], ?], QScriptTotal[T, ?]].prj(qt).map(_.getConst)
  }

  object FreeQS {
    def unapply[T[_[_]]](fq: FreeQS[T]): Option[QScriptTotal[T, FreeQS[T]]] = fq match {
      case Embed(CoEnv(\/-(q))) => q.some
      case _ => none
    }
  }

  def isRewrite[T[_[_]]: BirecursiveT: EqualT, F[_]: Functor, G[_]: Functor, A](GtoF: PrismNT[G, F], qs: G[A])(implicit
    QC: QScriptCore[T, ?] :<: F,
    SR: Const[ShiftedRead[AFile], ?] :<: F,
    ev: Recursive.Aux[A, G])
      : Boolean = qs match {
    case GtoF(QC(Filter(Embed(GtoF(SR(Const(ShiftedRead(_, ExcludeId))))), _)))
       | GtoF(QC(Sort(Embed(GtoF(SR(Const(ShiftedRead(_, ExcludeId))))), _, _)))
       | GtoF(QC(Sort(Embed(GtoF(QC(Filter(Embed(GtoF(SR(Const(ShiftedRead(_, ExcludeId))))), _)))), _ , _)))
       | GtoF(QC(Subset(_, FreeQS(SRT(ShiftedRead(_, ExcludeId))), _ , _)))
       | GtoF(SR(Const(ShiftedRead(_, ExcludeId)))) => true
    case _ => false
  }

  def isRewriteFree[T[_[_]]](fq: FreeQS[T]): Boolean = fq match {
    case FreeQS(SRT(ShiftedRead(_, ExcludeId))) => true
    case _ => false
  }

  def elideMoreGeneralGuards[T[_[_]], M[_]: Applicative: MonadFsErr, A: Eq]
    (hole: A, subType: Type)
      : CoEnvMapA[T, A, FreeMapA[T, A]] => M[CoEnvMapA[T, A, FreeMapA[T, A]]] = {
    def f: CoEnvMapA[T, A, FreeMapA[T, A]] => M[CoEnvMapA[T, A, FreeMapA[T, A]]] = {
      case free @ CoEnv(\/-(MFC(MapFuncsCore.Guard(Embed(CoEnv(-\/(h))), typ, cont, fb)))) if (h ≟ hole) =>
        if (typ.contains(subType)) cont.project.point[M]
        // TODO: Error if there is no overlap between the types.
        else {
          val union = subType ⨯ typ
          if (union ≟ Type.Bottom)
            raiseErr(qscriptPlanningFailed(InternalError.fromMsg(s"can only contain ${subType.shows}, but a(n) ${typ.shows} is expected")))
          else {
            CoEnv[A, MapFunc[T, ?], FreeMapA[T, A]](MFC(MapFuncsCore.Guard[T, FreeMapA[T, A]](Free.point[MapFunc[T, ?], A](h), union, cont, fb)).right).point[M]
          }
        }
      case x => x.point[M]
    }
    f
  }

def apply[T[_[_]]: BirecursiveT: EqualT, F[_]: Functor, M[_]: Monad: MonadFsErr]
  (typ: Type)
  (implicit
    QC: QScriptCore[T, ?] :<: F,
    EJ: EquiJoin[T, ?] :<: F,
    SR: Const[ShiftedRead[AFile], ?] :<: F)
    : Trans[F, M] =
  new Trans[F, M] {

    def elide(fm: FreeMap[T]): M[FreeMap[T]] =
      fm.transCataM(elideMoreGeneralGuards[T, M, Hole](SrcHole, typ))

    def elideJoinFunc(isRewrite: Boolean, joinSide: JoinSide, fm: JoinFunc[T]): M[JoinFunc[T]] =
      if (isRewrite) fm.transCataM(elideMoreGeneralGuards[T, M, JoinSide](joinSide, typ))
      else fm.point[M]

    def elideLeftJoinKey(isRewrite: Boolean, key: List[(FreeMap[T], FreeMap[T])])
        : M[List[(FreeMap[T], FreeMap[T])]] =
      if (isRewrite) key.traverse(t => elide(t._1).map(x => (x, t._2)))
      else key.point[M]

    def elideRightJoinKey(isRewrite: Boolean, key: List[(FreeMap[T], FreeMap[T])])
        : M[List[(FreeMap[T], FreeMap[T])]] =
      if (isRewrite) key.traverse(t => elide(t._2).map(x => (t._1, x)))
      else key.point[M]

    override def trans[A, G[_]: Functor]
      (GtoF: PrismNT[G, F])
      (implicit TC: Corecursive.Aux[A, G], TR: Recursive.Aux[A, G])
        : F[A] => M[G[A]] = {
        case f @ QC(Filter(src, cond))
          if (isRewrite[T, F, G, A](GtoF, src.project)) =>
            ((MapFuncCore.flattenAnd(cond))
              .traverse(elide))
              .map(_.toList.filter {
                case MapFuncsCore.BoolLit(true) => false
                case _ => true
              } match {
                case Nil => src.project
                case h :: t => GtoF.reverseGet(
                  QC(Filter(src, t.foldLeft[FreeMap[T]](h)((acc, e) => Free.roll(MFC(MapFuncsCore.And(acc, e)))))))
              })
        case ls @ QC(LeftShift(src, struct, id, repair))
          if (isRewrite[T, F, G, A](GtoF, src.project)) =>
            elide(struct) ∘
            (s => GtoF.reverseGet(QC(LeftShift(src, s, id, repair))))
        case m @ QC(qscript.Map(src, mf))
          if (isRewrite[T, F, G, A](GtoF, src.project)) =>
            elide(mf) ∘
            (mf0 => GtoF.reverseGet(QC(qscript.Map(src, mf0))))
        case r @ QC(Reduce(src, b, red, rep))
          if (isRewrite[T, F, G, A](GtoF, src.project)) =>
            (b.traverse(elide) ⊛
              red.traverse(_.traverse(elide)))(
              (b0, red0) => GtoF.reverseGet(QC(Reduce(src, b0, red0, rep))))
        case s @ QC(Sort(src, b, order))
          if (isRewrite[T, F, G, A](GtoF, src.project)) =>
            (b.traverse(elide) ⊛
              order.traverse(t => elide(t._1).map(x => (x, t._2))))(
              (b0, order0) => GtoF.reverseGet(QC(Sort(src, b0, order0))))
        case ej @ EJ(EquiJoin(src, lBranch, rBranch, key, f, combine)) =>
          val isRewriteL = isRewriteFree(lBranch)
          val isRewriteR = isRewriteFree(rBranch)

          ((elideLeftJoinKey(isRewriteL, key) >>=
            (k => elideRightJoinKey(isRewriteR, k))) ⊛
            (elideJoinFunc(isRewriteL, LeftSide, combine) >>=
              (c => elideJoinFunc(isRewriteR, RightSide, c))))(
              (k0, c0) => GtoF.reverseGet(EJ(EquiJoin(src, lBranch, rBranch, k0, f, c0))))
        case qc =>
          GtoF.reverseGet(qc).point[M]
      }
  }
}

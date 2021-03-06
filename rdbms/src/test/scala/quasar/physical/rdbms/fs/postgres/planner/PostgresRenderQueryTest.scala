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

package quasar.physical.rdbms.fs.postgres.planner

import slamdata.Predef._
import quasar.{Data, Qspec, qscript}
import quasar.physical.rdbms.planner.{Planner, SqlExprSupport}
import quasar.qscript._
import quasar.contrib.pathy.AFile
import matryoshka.data.Fix
import pathy.Path._
import quasar.fp.ski.κ
import quasar.physical.rdbms.planner.sql.SqlExpr
import quasar.physical.rdbms.planner.sql.SqlExpr._

import matryoshka._
import matryoshka.data._
import matryoshka.implicits._
import matryoshka.patterns._
import scalaz._
import Scalaz._
import scalaz.concurrent.Task

class PostgresRenderQueryTest extends Qspec with SqlExprSupport with QScriptHelpers {

  val func = construction.Func[Fix]
  import Scalaz.Id

  def sr: Planner[Fix, Id, Const[ShiftedRead[AFile], ?]] =
    Planner.constShiftedReadFilePlanner[Fix, Id]

  def core: Planner[Fix, Task, qscript.MapFunc[Fix, ?]] =
    Planner.mapFuncPlanner[Fix, Task]

  def str(s: String): Fix[SqlExpr] = Constant[Fix[SqlExpr]](Data.Str(s)).embed

  "PostgresJsonRenderQuery" should {
    "render shifted read with ExcludeId" in {

      val afile = rootDir </> dir("db") </> file("foo")
      val repr = sr.plan(Const(ShiftedRead(afile, ExcludeId)))

      PostgresRenderQuery.asString(repr) must
        beRightDisjunction("(select row_to_json(_0) _0 from db.foo _0)")
    }

    "render shifted read with IncludeId" in {

      val afile = rootDir </> dir("db") </> file("foo")
      val repr = sr.plan(Const(ShiftedRead(afile, IncludeId)))

      PostgresRenderQuery.asString(repr) must
        beRightDisjunction(
          "(select (row_number() over(), row_to_json(_0)) _0 from db.foo _0)")
    }

    "render shifted read ids only" in {

      val afile = rootDir </> dir("db") </> file("foo")
      val repr = sr.plan(Const(ShiftedRead(afile, IdOnly)))

      PostgresRenderQuery.asString(repr) must
        beRightDisjunction("(select row_number() over() _0 from db.foo _0)")
    }

    def pKey(name: String) = func.ProjectKeyS(func.Hole, name)

    def aliasToHole(aliasStr: String) = {
      val id: SqlExpr[Fix[SqlExpr]] = SqlExpr.Id(aliasStr)
      κ(id.embed.η[Task])
    }

    def qsToRepr(m: qscript.FreeMap[Fix]) =
      m.cataM(interpretM(aliasToHole("d"), core.plan)).unsafePerformSync

    def MultiplyR[A](left: FreeMapA[A], right: FreeMapA[A]):
    FreeMapA[A] = {
      Free.roll(MFC(MapFuncsCore.Multiply(left, right)))
    }

    def DivideR[A](left: FreeMapA[A], right: FreeMapA[A]):
    FreeMapA[A] = {
      Free.roll(MFC(MapFuncsCore.Divide(left, right)))
    }

    def ModR[A](left: FreeMapA[A], right: FreeMapA[A]):
    FreeMapA[A] = {
      Free.roll(MFC(MapFuncsCore.Modulo(left, right)))
    }

    def PowR[A](left: FreeMapA[A], right: FreeMapA[A]):
    FreeMapA[A] = {
      Free.roll(MFC(MapFuncsCore.Power(left, right)))
    }

    def SubtractR[A](left: FreeMapA[A], right: FreeMapA[A]):
    FreeMapA[A] = {
      Free.roll(MFC(MapFuncsCore.Subtract(left, right)))
    }

    def AddR[A](left: FreeMapA[A], right: FreeMapA[A]):
    FreeMapA[A] = {
      Free.roll(MFC(MapFuncsCore.Add(left, right)))
    }

    def DateR[A](expr: FreeMapA[A]):
    FreeMapA[A] = {
      Free.roll(MFC(MapFuncsCore.Date(expr)))
    }

    def IntervalR[A](expr: FreeMapA[A]):
    FreeMapA[A] = {
      Free.roll(MFC(MapFuncsCore.Interval(expr)))
    }

    "render addition" in {
      val qs = AddR(pKey("a"), pKey("b"))

      PostgresRenderQuery.asString(qsToRepr(qs)) must
        beRightDisjunction("((d->>'a')::numeric + (d->>'b')::numeric)")
    }

    "render multiplication" in {
      val qs = MultiplyR(pKey("m1"), pKey("m2"))

      PostgresRenderQuery.asString(qsToRepr(qs)) must
        beRightDisjunction("((d->>'m1')::numeric * (d->>'m2')::numeric)")
    }

    "render division" in {
      val qs = DivideR(pKey("d1"), pKey("d2"))

      PostgresRenderQuery.asString(qsToRepr(qs)) must
        beRightDisjunction("((d->>'d1')::numeric / (d->>'d2')::numeric)")
    }

    "render subtraction" in {
      val qs = SubtractR(pKey("sub1"), pKey("sub2"))

      PostgresRenderQuery.asString(qsToRepr(qs)) must
        beRightDisjunction("((d->>'sub1')::numeric - (d->>'sub2')::numeric)")
    }

    "render composite numeric operation" in {
      val qs = MultiplyR(pKey("m1"), SubtractR(pKey("a"), pKey("b")))

      PostgresRenderQuery.asString(qsToRepr(qs)) must
        beRightDisjunction("((d->>'m1')::numeric * (((d->>'a')::numeric - (d->>'b')::numeric))::numeric)")
    }

    "render modulo" in {
      val qs = ModR(pKey("mod1"), func.Constant(json.int(33)))

      PostgresRenderQuery.asString(qsToRepr(qs)) must
        beRightDisjunction("mod((d->>'mod1')::numeric, (33)::numeric)")
    }

    "render power" in {
      val qs = PowR(pKey("powKey"), func.Constant(json.int(4)))

      PostgresRenderQuery.asString(qsToRepr(qs)) must
        beRightDisjunction("power((d->>'powKey')::numeric, (4)::numeric)")
    }

    "render date" in {
      val qs = DateR(pKey("d1"))

      PostgresRenderQuery.asString(qsToRepr(qs)) must
        beRightDisjunction("(case when (d->'d1'->>'$date' notnull) then d->>'d1' when (d->>'d1' ~ " +
          "'(?:\\d{4}-\\d{2}-\\d{2}|\\d{8})') then json_build_object('$date', d->>'d1')#>>'{}' else null end)")
    }

    "render interval" in {
      val qs = IntervalR(pKey("int"))

      PostgresRenderQuery.asString(qsToRepr(qs)) must
        beRightDisjunction("(case when (d->'int'->>'$interval' notnull) then d->>'int' else null end)")
    }

    "render composite key projection" in {
      val qs = func.ProjectKeyS(func.ProjectKeyS(func.ProjectKeyS(func.Hole, "a"), "b"), "c")

      PostgresRenderQuery.asString(qsToRepr(qs)) must
        beRightDisjunction("d->'a'->'b'->>'c'")
    }
  }
}

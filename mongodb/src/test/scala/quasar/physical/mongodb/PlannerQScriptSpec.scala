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

package quasar.physical.mongodb

import quasar._
import quasar.common.{Map => _, _}
import quasar.contrib.pathy._
import quasar.contrib.specs2.PendingWithActualTracking
import quasar.javascript._
import quasar.ejson.{EJson, Fixed}
import quasar.physical.mongodb.expression._
import quasar.physical.mongodb.planner._
import quasar.physical.mongodb.workflow._
import quasar.sql.JoinDir
import slamdata.Predef._

import eu.timepit.refined.auto._
import matryoshka.data.Fix
import pathy.Path._
import scalaz._

class PlannerQScriptSpec extends
    PlannerHelpers with
    PendingWithActualTracking {

  import fixExprOp._
  import PlannerHelpers._
  import expr3_2Fp._
  import jscore._
  import CollectionUtil._
  import Reshape.reshape

  val dsl =
    quasar.qscript.construction.mkDefaults[Fix, fs.MongoQScript[Fix, ?]]
  import dsl._

  val json = Fixed[Fix[EJson]]

  val simpleInnerEquiJoinWithExpression =
    fix.EquiJoin(
      fix.Unreferenced,
      free.Filter(
        free.ShiftedRead[AFile](rootDir </> dir("db") </> file("zips"), qscript.ExcludeId),
        func.Guard(
          func.Guard(func.Hole, Type.AnyObject, func.ProjectKeyS(func.Hole, "_id"), func.Undefined),
          Type.Str,
          func.Constant(json.bool(true)),
          func.Constant(json.bool(false)))),
      free.Filter(
        free.ShiftedRead[AFile](rootDir </> dir("db") </> file("smallZips"), qscript.ExcludeId),
        func.Guard(func.Hole, Type.AnyObject, func.Constant(json.bool(true)), func.Constant(json.bool(false)))),
      List(
        (
          func.Guard(
            func.Hole,
            Type.AnyObject,
            func.Lower(
              func.ProjectKeyS(func.Hole, "_id")),
            func.Undefined),
          func.ProjectKeyS(func.Hole, "_id"))),
      JoinType.Inner,
      func.ConcatMaps(
        func.MakeMap(
          func.Constant(json.str("city")),
          // qscript is generated with 3 guards here:
          // func.Guard(
          //   func.Guard(func.LeftSide, Type.AnyObject, func.LeftSide, func.Undefined),
          //   Type.AnyObject,
          //   func.Guard(func.LeftSide, Type.AnyObject, func.ProjectKeyS(func.LeftSide, "city"), func.Undefined),
          //   func.Undefined)),
          func.Guard(func.LeftSide, Type.AnyObject, func.ProjectKeyS(func.LeftSide, "city"), func.Undefined)),
        func.MakeMap(
          func.Constant(json.str("state")),
          func.Guard(func.RightSide, Type.AnyObject, func.ProjectKeyS(func.RightSide, "state"), func.Undefined))))

  val simpleInnerEquiJoinWithPrefiltering =
    fix.EquiJoin(
      fix.Unreferenced,
      free.Filter(
        free.ShiftedRead[AFile](rootDir </> dir("db") </> file("zips"), qscript.ExcludeId),
        func.Guard(func.Hole, Type.AnyObject, func.Constant(json.bool(true)), func.Constant(json.bool(false)))),
      free.Filter(
        free.ShiftedRead[AFile](rootDir </> dir("test") </> file("smallZips"), qscript.ExcludeId),
        func.Guard(
          func.Guard(func.Hole, Type.AnyObject,
            func.ProjectKeyS(func.Hole, "pop"),
            func.Undefined),
          Type.Coproduct(Type.Coproduct(Type.Coproduct(Type.Coproduct(Type.Coproduct(Type.Int, Type.Dec), Type.Interval), Type.Str), Type.Coproduct(Type.Coproduct(Type.Timestamp, Type.Date), Type.Time)), Type.Bool),
          func.Guard(func.Hole, Type.AnyObject,
            func.Gte(
              func.ProjectKeyS(func.Hole, "pop"),
              func.Constant(json.int(10000))),
            func.Undefined),
          func.Undefined)),
      List(
        (
          func.ProjectKeyS(func.Hole, "_id"),
          func.Guard(func.Hole, Type.AnyObject,
            func.ProjectKeyS(func.Hole, "_id"),
            func.Undefined))),
      JoinType.Inner,
      func.ConcatMaps(
        func.MakeMap(
          func.Constant(json.str("city")),
          func.Guard(
            func.LeftSide,
            Type.AnyObject,
            func.ProjectKeyS(func.LeftSide, "city"),
            func.Undefined)),
        func.MakeMap(
          func.Constant(json.str("state")),
          func.Guard(
            func.Guard(
              func.RightSide,
              Type.AnyObject,
              func.RightSide,
              func.Undefined),
            Type.AnyObject,
            func.Guard(
              func.RightSide,
              Type.AnyObject,
              func.ProjectKeyS(func.RightSide, "state"),
              func.Undefined),
            func.Undefined))))

  val threeWayEquiJoin =
    fix.EquiJoin(
      fix.Unreferenced,
      free.Filter(
        free.EquiJoin(
          free.Unreferenced,
          free.Filter(
            free.ShiftedRead[AFile](
              rootDir </> dir("db") </> file("extraSmallZips"),
              qscript.ExcludeId),
            func.Guard(
              func.Hole,
              Type.AnyObject,
              func.Constant(json.bool(true)),
              func.Constant(json.bool(false)))),
          free.Filter(
            free.ShiftedRead[AFile](
              rootDir </> dir("db") </> file("smallZips"),
              qscript.ExcludeId),
            func.Guard(
              func.Hole,
              Type.AnyObject,
              func.Constant(json.bool(true)),
              func.Constant(json.bool(false)))),
          List(
            (
              func.ProjectKeyS(func.Hole, "_id"),
              func.ProjectKeyS(func.Hole, "_id"))),
          JoinType.Inner,
          func.ConcatArrays(
            func.MakeArray(func.LeftSide),
            func.MakeArray(func.RightSide))),
        func.Guard(
          func.ProjectIndexI(func.Hole, 1),
          Type.AnyObject,
          func.Constant(json.bool(true)),
          func.Constant(json.bool(false)))),
      free.Filter(
        free.ShiftedRead[AFile](
          rootDir </> dir("db") </> file("zips"),
          qscript.ExcludeId),
        func.Guard(
          func.Hole,
          Type.AnyObject,
          func.Constant(json.bool(true)),
          func.Constant(json.bool(false)))),
      List(
        (
          func.ProjectKeyS(func.ProjectIndexI(func.Hole, 1), "_id"),
          func.ProjectKeyS(func.Hole, "_id"))),
      JoinType.Inner,
      func.ConcatMaps(
        func.ConcatMaps(
          func.MakeMap(
            func.Constant(json.str("city")),
            func.Guard(
              func.ConcatMaps(
                func.MakeMap(
                  func.Constant(json.str("left")),
                  func.ProjectIndexI(func.LeftSide, 0)),
                func.MakeMap(
                  func.Constant(json.str("right")),
                  func.ProjectIndexI(func.LeftSide, 1))),
              Type.AnyObject,
              func.Guard(
                func.ProjectIndexI(func.LeftSide, 0),
                Type.AnyObject,
                func.ProjectKeyS(func.ProjectIndexI(func.LeftSide, 0), "city"),
                func.Undefined),
              func.Undefined)),
          func.MakeMap(
            func.Constant(json.str("state")),
            func.Guard(
              func.ConcatMaps(
                func.MakeMap(func.Constant(json.str("left")),
                  func.ProjectIndexI(func.LeftSide, 0)),
                func.MakeMap(
                  func.Constant(json.str("right")),
                  func.ProjectIndexI(func.LeftSide, 1))),
              Type.AnyObject,
              func.Guard(
                func.ProjectIndexI(func.LeftSide, 1),
                Type.AnyObject,
                func.ProjectKeyS(func.ProjectIndexI(func.LeftSide, 1), "state"),
                func.Undefined),
              func.Undefined))),
        func.MakeMap(
          func.Constant(json.str("pop")),
          func.Guard(
            func.RightSide,
            Type.AnyObject,
            func.ProjectKeyS(func.RightSide, "pop"),
            func.Undefined))))

  "plan from qscript" should {

    "plan simple inner equi-join with expression ($lookup)" in {
      qplan(simpleInnerEquiJoinWithExpression) must beWorkflow0(chain[Workflow](
        $read(collection("db", "zips")),
        $project(reshape(
          JoinDir.Left.name -> $$ROOT,
          "__tmp0" -> $toLower($field("_id"))),
          IgnoreId),
        $lookup(
          CollectionName("smallZips"),
          BsonField.Name("_id"),
          BsonField.Name("__tmp0"),
          JoinHandler.RightName),
        $project(reshape(
          JoinDir.Left.name -> $field(JoinDir.Left.name),
          JoinDir.Right.name -> $field(JoinDir.Right.name))),
        $unwind(DocField(JoinHandler.RightName)),
        $project(reshape(
          "city" ->
            $cond(
              $and(
                $lte($literal(Bson.Doc()), $field(JoinDir.Left.name)),
                $lt($field(JoinDir.Left.name), $literal(Bson.Arr()))),
              $field(JoinDir.Left.name, "city"),
              $literal(Bson.Undefined)),
          "state" ->
            $cond(
              $and(
                $lte($literal(Bson.Doc()), $field(JoinDir.Right.name)),
                $lt($field(JoinDir.Right.name), $literal(Bson.Arr()))),
              $field(JoinDir.Right.name, "state"),
              $literal(Bson.Undefined))),
          IgnoreId)))
          // There are unnecessary stages and there's a $match that is a NOP
          // but at least it's on agg
    }.pendingWithActual(notOnPar, qtestFile("plan simple inner equi-join with expression ($lookup)"))

    "plan simple inner equi-join with pre-filtering ($lookup)" in {
      qplan(simpleInnerEquiJoinWithPrefiltering) must beWorkflow0(chain[Workflow](
        $read(collection("db", "smallZips")),
        $match(
          Selector.And(
            isNumeric(BsonField.Name("pop")),
            Selector.Doc(
              BsonField.Name("pop") -> Selector.Gte(Bson.Int32(10000))))),
        $project(reshape(
          JoinDir.Right.name -> $$ROOT,
          "__tmp2" -> $field("_id")),
          ExcludeId),
        $lookup(
          CollectionName("zips"),
          BsonField.Name("__tmp2"),
          BsonField.Name("_id"),
          JoinHandler.LeftName),
        $project(reshape(
          JoinDir.Right.name -> $field(JoinDir.Right.name),
          JoinDir.Left.name -> $field(JoinDir.Left.name))),
        $unwind(DocField(JoinHandler.LeftName)),
        $project(reshape(
          "city" ->
            $cond(
              $and(
                $lte($literal(Bson.Doc()), $field(JoinDir.Left.name)),
                $lt($field(JoinDir.Left.name), $literal(Bson.Arr()))),
              $field(JoinDir.Left.name, "city"),
              $literal(Bson.Undefined)),
          "state" ->
            $cond(
              $and(
                $lte($literal(Bson.Doc()), $field(JoinDir.Right.name)),
                $lt($field(JoinDir.Right.name), $literal(Bson.Arr()))),
              $field(JoinDir.Right.name, "state"),
              $literal(Bson.Undefined))),
          IgnoreId)))
          // Not on agg
    }.pendingWithActual(notOnPar, qtestFile("plan simple inner equi-join with pre-filtering ($lookup)"))

    "plan 3-way equi-join ($lookup)" in {
      qplan(simpleInnerEquiJoinWithPrefiltering) must beWorkflow0(chain[Workflow](
        $read(collection("db", "extraSmallZips")),
        $match(Selector.Doc(
          BsonField.Name("_id") -> Selector.Exists(true))),
        $project(reshape(JoinDir.Left.name -> $$ROOT)),
        $lookup(
          CollectionName("smallZips"),
          JoinHandler.LeftName \ BsonField.Name("_id"),
          BsonField.Name("_id"),
          JoinHandler.RightName),
        $unwind(DocField(JoinHandler.RightName)),
        $match(Selector.Doc(
          JoinHandler.RightName \ BsonField.Name("_id") -> Selector.Exists(true))),
        $project(reshape(JoinDir.Left.name -> $$ROOT)),
        $lookup(
          CollectionName("zips"),
          JoinHandler.LeftName \ JoinHandler.RightName \ BsonField.Name("_id"),
          BsonField.Name("_id"),
          JoinHandler.RightName),
        $unwind(DocField(JoinHandler.RightName)),
        $project(reshape(
          "city" ->
            $cond(
              $and(
                $lte($literal(Bson.Doc()), $field(JoinDir.Left.name)),
                $lt($field(JoinDir.Left.name), $literal(Bson.Arr()))),
              $cond(
                $and(
                  $lte($literal(Bson.Doc()), $field(JoinDir.Left.name, JoinDir.Left.name)),
                  $lt($field(JoinDir.Left.name, JoinDir.Left.name), $literal(Bson.Arr()))),
                $field(JoinDir.Left.name, JoinDir.Left.name, "city"),
                $literal(Bson.Undefined)),
              $literal(Bson.Undefined)),
          "state" ->
            $cond(
              $and(
                $lte($literal(Bson.Doc()), $field(JoinDir.Left.name)),
                $lt($field(JoinDir.Left.name), $literal(Bson.Arr()))),
              $cond(
                $and(
                  $lte($literal(Bson.Doc()), $field(JoinDir.Left.name, JoinDir.Right.name)),
                  $lt($field(JoinDir.Left.name, JoinDir.Right.name), $literal(Bson.Arr()))),
                $field(JoinDir.Left.name, JoinDir.Right.name, "state"),
                $literal(Bson.Undefined)),
              $literal(Bson.Undefined)),
          "pop" ->
            $cond(
              $and(
                $lte($literal(Bson.Doc()), $field(JoinDir.Right.name)),
                $lt($field(JoinDir.Right.name), $literal(Bson.Arr()))),
              $field(JoinDir.Right.name, "pop"),
              $literal(Bson.Undefined))),
          IgnoreId)))
          // Not on agg
    }.pendingWithActual(notOnPar, qtestFile("plan 3-way equi-join ($lookup)"))

    "plan typechecks with JS when unable to extract ExprOp" in {
      import fix.{Filter, ShiftedRead}, qscript.IncludeId
      import func.{Guard, Hole, ProjectKeyS, ProjectIndexI, Constant}

      qplan(
        Filter(
          ShiftedRead[AFile](rootDir </> dir("db") </> file("zips"), IncludeId),
          Guard(
            ProjectKeyS(ProjectIndexI(Hole, 1), "parentid"),
            Type.Str,
            Constant(json.bool(false)),
            Constant(json.bool(true))))) must beWorkflow0(
        chain[Workflow](
          $read(collection("db", "zips")),
          $project(reshape("0" -> $arrayLit(List($field("_id"), $$ROOT)))),
          $simpleMap(
            NonEmptyList(MapExpr(JsFn(Name("x"), obj(
              "0" ->
                If(Call(ident("isString"),
                  List(
                    Select(Access(
                      Access(
                        ident("x"),
                        jscore.Literal(Js.Str("0"))),
                      jscore.Literal(Js.Num(1, false))), "parentid"))),
                  jscore.Literal(Js.Bool(false)),
                  jscore.Literal(Js.Bool(true))),
              "src" -> Access(ident("x"), jscore.Literal(Js.Str("0")))
            )))),
            ListMap()),
          $match(Selector.Doc(
            BsonField.Name("0") -> Selector.Eq(Bson.Bool(true))
          )),
          $project(reshape(sigil.Quasar -> $field("src")))))
    }

  }
}

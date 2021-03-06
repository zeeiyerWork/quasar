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

package quasar.qscript.qsu

import quasar.fp._
import quasar.qscript.ReduceFuncs
import quasar.qscript.qsu.{QScriptUniform => QSU}

import matryoshka.BirecursiveT
import scalaz.syntax.equal._

final class RecognizeDistinct[T[_[_]]: BirecursiveT] private () extends QSUTTypes[T] {
  import QSUGraph.Extractors._

  // pattern from compileDistinct in compiler.scala
  // TODO this is dumb; we shouldn't be replicating patterns like this
  def apply(qgraph: QSUGraph): QSUGraph = qgraph rewrite {
    case qgraph @ LPReduce(GroupBy(orig1, orig2), ReduceFuncs.Arbitrary(()))
        if orig1.root === orig2.root =>
      qgraph.overwriteAtRoot(QSU.Distinct(orig1.root))
  }
}

object RecognizeDistinct {
  def apply[T[_[_]]: BirecursiveT]: RecognizeDistinct[T] = new RecognizeDistinct[T]
}

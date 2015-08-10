package io.getquill.sql

import scala.reflect.macros.whitebox.Context
import QueryShow.sqlQueryShow
import io.getquill.impl.Queryable
import io.getquill.norm.NormalizationMacro
import io.getquill.util.Messages._
import io.getquill.util.Show.Shower
import io.getquill.source.Encoder
import io.getquill.util.InferImplicitValueWithFallback
import io.getquill.source.Encoding

class QueryMacro(val c: Context) extends NormalizationMacro {
  import c.universe._

  def run[R, S, T](q: Expr[Queryable[T]])(implicit r: WeakTypeTag[R], s: WeakTypeTag[S], t: WeakTypeTag[T]): Tree =
    run[R, S, T](q.tree, List(), List())

  def run1[P1, R: WeakTypeTag, S: WeakTypeTag, T: WeakTypeTag](q: Expr[P1 => Queryable[T]])(p1: Expr[P1]): Tree =
    runParametrized[R, S, T](q.tree, List(p1))

  def run2[P1, P2, R: WeakTypeTag, S: WeakTypeTag, T: WeakTypeTag](q: Expr[(P1, P2) => Queryable[T]])(p1: Expr[P1], p2: Expr[P2]): Tree =
    runParametrized[R, S, T](q.tree, List(p1, p2))

  private def runParametrized[R, S, T](q: Tree, bindings: List[Expr[Any]])(implicit r: WeakTypeTag[R], s: WeakTypeTag[S], t: WeakTypeTag[T]) =
    q match {
      case q"(..$params) => $body" =>
        run[R, S, T](body, params, bindings)
    }

  private def run[R, S, T](q: Tree, params: List[ValDef], bindings: List[Expr[Any]])(implicit r: WeakTypeTag[R], s: WeakTypeTag[S], t: WeakTypeTag[T]) = {
    val bindingMap =
      (for ((param, binding) <- params.zip(bindings)) yield {
        identExtractor(param) -> (param, binding)
      }).toMap
    val d = c.WeakTypeTag(c.prefix.tree.tpe)
    val NormalizedQuery(query, extractor) = normalize(q)(r, t)
    val (bindedQuery, bindingIdents) = BindVariables(query)(bindingMap.keys.toList)
    val sql = SqlQuery(bindedQuery).show
    c.info(sql)
    val applyEncoders =
      for ((ident, index) <- bindingIdents.zipWithIndex) yield {
        val (param, binding) = bindingMap(ident)
        val encoder =
          Encoding.inferEcoder(c)(param.tpt.tpe)(s)
            .getOrElse(c.fail(s"Source doesn't know how do encode $param: ${param.tpt}"))
        q"r = $encoder($index, $binding, r)"
      }
    q"""
      {
          def encode(row: $s) = {
            var r = row
            $applyEncoders
            r
          }
          ${c.prefix}.query[$t]($sql, encode _, $extractor)
      }  
    """
  }

  private def interpret[R, T](q: Tree)(implicit r: WeakTypeTag[R], t: WeakTypeTag[T]) = {
    val NormalizedQuery(query, extractor) = normalize(q)(r, t)
    (SqlQuery(query).show, extractor)
  }
}
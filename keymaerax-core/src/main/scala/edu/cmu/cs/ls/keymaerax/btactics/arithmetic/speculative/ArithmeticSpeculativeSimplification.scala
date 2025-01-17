/*
 * Copyright (c) Carnegie Mellon University.
 * See LICENSE.txt for the conditions of this license.
 */

package edu.cmu.cs.ls.keymaerax.btactics.arithmetic.speculative

import edu.cmu.cs.ls.keymaerax.bellerophon._
import edu.cmu.cs.ls.keymaerax.btactics.ArithmeticSimplification._
import edu.cmu.cs.ls.keymaerax.btactics.Augmentors._
import edu.cmu.cs.ls.keymaerax.btactics.ExpressionTraversal.{ExpressionTraversalFunction, StopTraversal}
import edu.cmu.cs.ls.keymaerax.btactics.{ExpressionTraversal, TactixLibrary}
import edu.cmu.cs.ls.keymaerax.btactics.TacticFactory._
import edu.cmu.cs.ls.keymaerax.btactics.TactixLibrary._
import edu.cmu.cs.ls.keymaerax.btactics.arithmetic.signanalysis.SignAnalysis
import edu.cmu.cs.ls.keymaerax.core._

import scala.collection.mutable.ListBuffer

/**
  * Tactics for simplifying arithmetic sub-goals.
  * @author Stefan Mitsch
  */
object ArithmeticSpeculativeSimplification {

  private val DEBUG = false

  /** @todo I'm not sure where the error function was defined prior to this. Maybe it was a Scala 2.11->2.12 thing? */
  class ErrorException(msg: String) extends Exception
  def error[T](message : String) : T = throw new ErrorException(message)


  /** Tries decreasingly aggressive strategies of hiding formulas before QE, until finally falling back to full QE if none
    * of the simplifications work out. */
  lazy val speculativeQE: BelleExpr = "smartQE" by ((sequent: Sequent) => {
    (debug("Trying abs...", DEBUG) & proveOrRefuteAbs & debug("...abs done", DEBUG)) | speculativeQENoAbs
  })

  /** QE without handling abs */
  private lazy val speculativeQENoAbs: BelleExpr = "QE" by ((sequent: Sequent) => {
    (debug("Trying orIntro and smart hiding...", DEBUG) & (orIntro((debug("Bound", DEBUG) & hideNonmatchingBounds & smartHide & QE() & TactixLibrary.done) | (debug("Non-Bound", DEBUG) & smartHide & QE() & TactixLibrary.done)) & debug("... orIntro and smart hiding successful", DEBUG))) |
    (debug("orIntro failed, trying smart hiding...", DEBUG) & ((hideNonmatchingBounds & smartHide & QE() & TactixLibrary.done) | (smartHide & QE()) & TactixLibrary.done) & debug("...smart hiding successful", DEBUG)) |
    (debug("All simplifications failed, falling back to ordinary QE", DEBUG) & QE() & TactixLibrary.done)
  })

  /** Uses the disjunction introduction proof rule to prove a disjunctions by proving any 1 of the disjuncts. */
  def orIntro(finish: BelleExpr): BelleExpr = "orIntro" by ((sequent: Sequent) => {
    def toSingleSucc(retain: Int): BelleExpr = {
      sequent.succ.indices.filter(_ != retain).sorted.reverse.map(i => hideR(i+1)).reduceLeft[BelleExpr](_&_)
    }

    if (sequent.succ.size > 1) {
      //@todo CounterExample might provide insight on which of the formulas are needed
      sequent.succ.indices.map(i => toSingleSucc(i) & finish).reduceLeft[BelleExpr](_|_) | finish
    } else finish
  })

  /** Assert that there is no counter example. skip if none, error if there is. */
  lazy val assertNoCex: BelleExpr = "assertNoCEX" by ((sequent: Sequent) => {
    TactixLibrary.findCounterExample(sequent.toFormula) match {
      case Some(cex) => error("Found counterexample " + cex)
      case None => skip
    }
  })

  /** Proves abs by trying to find contradictions; falls back to QE if contradictions fail */
  lazy val proveOrRefuteAbs: BelleExpr = "proveOrRefuteAbs" by ((sequent: Sequent) => {
    val symbols = (sequent.ante.flatMap(StaticSemantics.symbols) ++ sequent.succ.flatMap(StaticSemantics.symbols)).toSet
    if (symbols.exists(_.name == "abs")) exhaustiveAbsSplit & OnAll((SaturateTactic(hideR('R)) & assertNoCex & QE & TactixLibrary.done) | speculativeQENoAbs)
    else error("Sequent does not contain abs")
  })

  /** Splits absolute value functions to create more, but hopefully simpler, goals. */
  def exhaustiveAbsSplit: BelleExpr = "absSplit" by ((sequent: Sequent) => {
    def absPos(fml: Formula): List[PosInExpr] = {
      val result = ListBuffer[PosInExpr]()
      ExpressionTraversal.traverse(new ExpressionTraversalFunction() {
        override def preT(p: PosInExpr, e: Term): Either[Option[StopTraversal], Term] = e match {
          case FuncOf(Function("abs", _, _, _, true), _) => result += p; Left(None)
          case _ => Left(None)
        }
      }, fml)
      result.toList
    }

    val anteAbs = sequent.ante.zipWithIndex.
      filter{ case (f,_) => StaticSemantics.symbols(f).contains(Function("abs", None, Real, Real, interpreted=true))}.
      map{ case (f, i) => (f, AntePosition.base0(i)) }
    val succAbs = sequent.succ.zipWithIndex.
      filter{ case (f,_) => StaticSemantics.symbols(f).contains(Function("abs", None, Real, Real, interpreted=true))}.
      map{ case (f,i) => (f, SuccPosition.base0(i)) }

    val absTactic = (anteAbs++succAbs).
      //@note p+inExpr navigates to sub-expression since p are top
      map{ case (f,p) => (f, absPos(f).map(inExpr => p ++ inExpr)) }.
      map{ case (_,p) => p.map(pos => OnAll(abs(pos) & orL('Llast))).reduceLeft[BelleExpr](_&_) }.
      reduceLeft[BelleExpr](_&_)

    absTactic & OnAll(SaturateTactic(andL('_))) & OnAll(SaturateTactic(exhaustiveEqL2R(hide=true)('L)))
  })

  /** Hides formulas with non-matching bounds. */
  def hideNonmatchingBounds: BelleExpr = "hideNonmatchingBounds" by ((sequent: Sequent) => {
    SignAnalysis.boundHideCandidates(sequent).sortBy(_.getIndex).reverse.map(hide(_)).reduceLeftOption[BelleExpr](_&_).getOrElse(skip)
  })

  def hideInconsistentSigns: BelleExpr = "hideInconsistentBounds" by ((sequent: Sequent) => {
    SignAnalysis.signHideCandidates(sequent).sortBy(_.getIndex).reverse.map(hide(_)).reduceLeftOption[BelleExpr](_&_).getOrElse(skip)
  })

}
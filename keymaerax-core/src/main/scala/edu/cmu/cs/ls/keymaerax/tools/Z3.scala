/**
* Copyright (c) Carnegie Mellon University.
* See LICENSE.txt for the conditions of this license.
*/
/**
  * @note Code Review: 2016-08-02
  */
package edu.cmu.cs.ls.keymaerax.tools

import edu.cmu.cs.ls.keymaerax.core._

import scala.collection.immutable.Map

/**
 * Z3 quantifier elimination tool.
 *
 * Created by smitsch on 4/27/15.
 * @author Ran Ji
 * @author Stefan Mitsch
 */
class Z3 extends ToolBase("Z3") with QETool with AlgebraTool with ToolOperationManagement {
  private val z3 = new Z3Solver
  private val zAlgebra = new RingsAlgebraTool()

  override def init(config: Map[String,String]): Unit = {
    initialized = true
  }

  override def qeEvidence(formula: Formula): (Formula, Evidence) = {
    require(isInitialized, "Z3 needs to be initialized before use")
    z3.qeEvidence(formula)
  }

  override def quotientRemainder(term: Term, div: Term, x:Variable): (Term,Term) = zAlgebra.quotientRemainder(term,div,x)
  override def groebnerBasis(polynomials: List[Term]): List[Term] = zAlgebra.groebnerBasis(polynomials)
  override def polynomialReduce(polynomial: Term, GB: List[Term]): (List[Term], Term) = zAlgebra.polynomialReduce(polynomial, GB)

  override def restart(): Unit = { initialized = true }
  override def shutdown(): Unit = { initialized = false }
  override def cancel(): Boolean = z3.cancel()

  /** Sets a maximum duration of this tool's operations (e.g., QE). */
  override def setOperationTimeout(timeout: Int): Unit = z3.setOperationTimeout(timeout)

  /** Returns the timeout duration. */
  override def getOperationTimeout: Int = z3.getOperationTimeout

  override def getAvailableWorkers: Int = 1
}

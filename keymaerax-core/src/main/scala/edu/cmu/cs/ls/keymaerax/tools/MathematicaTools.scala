/**
* Copyright (c) Carnegie Mellon University.
* See LICENSE.txt for the conditions of this license.
*/

package edu.cmu.cs.ls.keymaerax.tools

import com.wolfram.jlink._
import edu.cmu.cs.ls.keymaerax.btactics.{ExpressionTraversal, FormulaTools}
import ExpressionTraversal.{ExpressionTraversalFunction, StopTraversal}
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.bellerophon.PosInExpr
import edu.cmu.cs.ls.keymaerax.btactics.helpers.DifferentialHelper
import edu.cmu.cs.ls.keymaerax.tools.KMComparator.hasHead
import edu.cmu.cs.ls.keymaerax.tools.MathematicaConversion.{KExpr, _}
import edu.cmu.cs.ls.keymaerax.tools.SimulationTool.{SimRun, SimState, Simulation}
import org.apache.logging.log4j.scala.Logging

import scala.collection.immutable

@deprecated
object ToolConversions {

  //@todo duplicate with [[FormulaTools.conjuncts]]
  def flattenConjunctions(f: Formula): List[Formula] = {
    var result: List[Formula] = Nil
    ExpressionTraversal.traverse(new ExpressionTraversalFunction {
      override def preF(p: PosInExpr, f: Formula): Either[Option[StopTraversal], Formula] = f match {
        case And(l, r) => result = result ++ flattenConjunctions(l) ++ flattenConjunctions(r); Left(Some(ExpressionTraversal.stop))
        case a => result = result :+ a; Left(Some(ExpressionTraversal.stop))
      }
    }, f)
    result
  }
}

object UncheckedBaseConverter {
  val CONST_FN_SUFFIX = "cnstfn_"
  val CONST_PRED_SUFFIX = "cnstpred_"
}

class UncheckedBaseM2KConverter extends MathematicaToKeYmaera {

  //@note unchecked, because ambiguous And==&& vs. And==Rules conversion
  override def k2m: K2MConverter[KExpr] = null
  override def apply(e: MExpr): KExpr = convert(e)

  /** Extends default converter with rule and rule list handling */
  override def convert(e: MExpr): KExpr = {
    if (isAborted(e)) throw new MathematicaComputationAbortedException(e.toString)
    else if (isFailed(e)) throw new MathematicaComputationFailedException(e.toString)
    else if (KMComparator.hasHead(e, MathematicaSymbols.RULE)) convertRule(e)
    else if (e.listQ() && e.args().forall(r => r.listQ() && r.args().forall(
      KMComparator.hasHead(_, MathematicaSymbols.RULE)))) convertRuleList(e)
    // Derivatives are of the form Derivative[1][x]
    //@todo Code Review: check e.head
    //@solution: additional checks for head + moved into non-soundness-critical converter
    //@note e.head() by itself is not meaningful -- it combines e.head.head == Derivative and e.head.args == degree
    else if (e.head.args().length == 1 && e.head().args.head.integerQ() && e.head().args.head.asInt() == 1 &&
      e.head.head.symbolQ() && e.head.head == MathematicaSymbols.DERIVATIVE) convertDerivative(e)
    else if (e.symbolQ() && hasHead(e, MathematicaSymbols.FALSE)) False
    else if (e.symbolQ() && hasHead(e, MathematicaSymbols.TRUE)) True
    else if (e.symbolQ() && MathematicaNameConversion.uncheckedUnmaskName(e.asString())._1.endsWith(UncheckedBaseConverter.CONST_FN_SUFFIX))
      MathematicaNameConversion.toKeYmaera(e) match {
        case BaseVariable(name, index, sort) => FuncOf(Function(name.substring(0, name.length - UncheckedBaseConverter.CONST_FN_SUFFIX.length), index, Unit, sort), Nothing)
      }
    else super.convert(e)
  }

  /** Converts rules and rule lists, not to be used in QE! */
  private def convertRule(e: MExpr): Formula = convert(e.args()(0)) match {
    case t: Term => Equal(t, convert(e.args()(1)).asInstanceOf[Term])
    case f: Formula => Equiv(f, convert(e.args()(1)).asInstanceOf[Formula])
  }

  private def convertRuleList(e: MExpr): Formula = {
    val convertedRules = e.args().map(_.args().map(r => convertRule(r)).reduceLeft(And))
    if (convertedRules.isEmpty) False
    else convertedRules.reduceLeft(Or)
  }

  private def convertDerivative(e: MExpr): KExpr = {
    require(e.args().length == 1, "Expected args size 1 (single differential symbol or single differential term)")
    require(e.head.args().length == 1 && e.head().args.head.integerQ() && e.head().args.head.asInt() == 1, "Expected 1 prime (e.g., v', not v'')")
    convert(e.args.head) match {
      case v: Variable => DifferentialSymbol(v)
      case t: Term => Differential(t)
    }
  }
}

class UncheckedBaseK2MConverter extends KeYmaeraToMathematica {

  //@note unchecked, because ambiguous And==&& vs. And==Rule in converse conversion
  override def m2k: M2KConverter[KExpr] = null
  override def apply(e: KExpr): MExpr = convert(e)

  override protected[tools] def convertTerm(t: Term): MExpr = t match {
    case FuncOf(Function(name, index, Unit, Real, false), Nothing) =>
      MathematicaNameConversion.toMathematica(Variable(name + UncheckedBaseConverter.CONST_FN_SUFFIX, index))
    case FuncOf(Function(name, index, Unit, Bool, false), Nothing) =>
      MathematicaNameConversion.toMathematica(Variable(name + UncheckedBaseConverter.CONST_PRED_SUFFIX, index))
    case _ => super.convertTerm(t)
  }
}

object CEXK2MConverter extends K2MConverter[Either[KExpr, NamedSymbol]] {

  private val baseConverter = new UncheckedBaseK2MConverter {
    override protected def convertFormula(f: Formula): MExpr = f match {
      case PredOf(fn@Function(_, _, Unit, Bool, false), Nothing) => CEXK2MConverter.this.convert(Right(fn))
      case _ => super.convertFormula(f)
    }

    override def convert(e: KExpr): MExpr = {
      //insist on less strict input: interpreted function symbols allowed here
      insist(StaticSemantics.symbols(e).forall({ case fn@Function(_, _, _, _, true) => interpretedSymbols.contains(fn) case _ => true }),
        "Interpreted functions must have expected domain and sort")
      insist(disjointNames(StaticSemantics.symbols(e)), "Disjoint names required for Mathematica conversion")
      e match {
        case t: Term => convertTerm(t)
        case f: Formula => convertFormula(f)
      }
    }

    override protected[tools] def convertTerm(t: Term): MExpr = t match {
      //@note no back conversion -> no need to distinguish Differential from DifferentialSymbol
      case Differential(c) =>
        new MExpr(new MExpr(MathematicaSymbols.DERIVATIVE, Array[MExpr](new MExpr(1))), Array[MExpr](convert(c)))
      case DifferentialSymbol(c) =>
        new MExpr(new MExpr(MathematicaSymbols.DERIVATIVE, Array[MExpr](new MExpr(1))), Array[MExpr](convert(c)))
      case FuncOf(fn@Function(_, _, Unit, Real, false), Nothing) => CEXK2MConverter.this.convert(Right(fn))
      case _ => super.convertTerm(t)
    }
  }

  private[tools] def convert(e: Either[KExpr, NamedSymbol]): MExpr = e match {
    case Left(expr) => baseConverter.convert(expr)
    case Right(v: Variable) => baseConverter.convert(v)
    case Right(Function(name, index, Unit, Real, false)) =>
      MathematicaNameConversion.toMathematica(Variable(name + UncheckedBaseConverter.CONST_FN_SUFFIX, index))
    case Right(Function(name, index, Unit, Bool, false)) =>
      MathematicaNameConversion.toMathematica(Variable(name + UncheckedBaseConverter.CONST_PRED_SUFFIX, index))
  }

  override def m2k: M2KConverter[Either[KExpr, NamedSymbol]] = null
  override def apply(e: Either[KExpr, NamedSymbol]): MExpr = convert(e)

}

object CEXM2KConverter extends M2KConverter[Either[KExpr,NamedSymbol]] {
  private val baseConverter = new UncheckedBaseM2KConverter {
    override def apply(e: MExpr): KExpr = convert(e)

    override protected def convertAtomicTerm(e: MExpr): KExpr = {
      MathematicaNameConversion.toKeYmaera(e) match {
        case BaseVariable(name, index, _) if name.endsWith(UncheckedBaseConverter.CONST_FN_SUFFIX) =>
          FuncOf(Function(name.substring(0, name.length - UncheckedBaseConverter.CONST_FN_SUFFIX.length), index, Unit, Real), Nothing)
        case BaseVariable(name, index, _) if name.endsWith(UncheckedBaseConverter.CONST_PRED_SUFFIX) =>
          PredOf(Function(name.substring(0, name.length - UncheckedBaseConverter.CONST_PRED_SUFFIX.length), index, Unit, Bool), Nothing)
        case _ => super.convertAtomicTerm(e)
      }
    }
  }

  override def k2m: K2MConverter[Either[KExpr, NamedSymbol]] = null
  override private[tools] def convert(e: MExpr): Either[KExpr,NamedSymbol] = Left(baseConverter.convert(e))
  override def apply(e: MExpr): Either[KExpr,NamedSymbol] = convert(e)
}

object PegasusM2KConverter extends UncheckedBaseM2KConverter {
  override def k2m: K2MConverter[KExpr] = null
  override def apply(e: MExpr): KExpr = convert(e)

  override def convert(e: MExpr): KExpr = {
    if (hasHead(e, MathematicaSymbols.TRUE))   True
    else if (hasHead(e, MathematicaSymbols.FALSE))  False
    else if (e.listQ()) convertFormulaTermList(e)
    else super.convert(e)
  }

  private val LIST_LENGTH = FuncOf(Function("length", None, Real, Real), Variable("list"))

  /** Decodes the formula generated by convertFormulaTermList back into a list of formulas with proof hints and invariant/candidate indicator. */
  def decodeFormulaList(listFml: Expression): (immutable.Seq[(Formula, String)], Formula) = {

    def decodeProofHint(f: Formula): (Formula, String) = f match {
      case And(Equal(lhs, Number(lsLength)), And(l, Equal(v: BaseVariable,proofhint: BaseVariable))) if lhs == LIST_LENGTH && lsLength == 2 && v.name=="ProofHint"=>
        (l, proofhint.name)
      case _ => throw new IllegalArgumentException("Unable to decode proof hint: " + f)
    }

    listFml match {
      case And(Equal(tupleLhs, Number(tupleLength)), invList) if tupleLhs == LIST_LENGTH =>
        //@note Pegasus returns lists of length 2:
        // - first entry is list of length 2 [Formula, dc chain], where dc chain is again a list
        // - second indicates invariant (true) vs. candidate (false)
        assert(tupleLength == 2, "Expected a list with two entries (invariant list and invariant/candidate indicator), but got " + tupleLength)
        invList match {
          case And(And(Equal(lhs, Number(invListLength)), And(_, And(Equal(dcChainLhs, Number(dcChainLength)), dcChainFml))), q) if lhs == LIST_LENGTH && dcChainLhs == LIST_LENGTH =>
            assert(invListLength == 2, "Expected a list with two entries (invariant formula and DC chain), but got " + invListLength)
            (FormulaTools.leftConjuncts(dcChainFml, dcChainLength.toInt).map(f => decodeProofHint(f)), q)
          case _ => throw new IllegalArgumentException("Expected a conjunction of invariant list (length and (Formula,List[Formula])) and an invariant/candidate indicator, but got " + invList.prettyString)
        }
      case fml: Formula => ((fml, "Unknown") :: Nil, True)
      case _ => throw ToolException("Expected a formula from Pegasus call but got a non-formula expression: " +
        listFml.prettyString)
    }
  }

  /** Decodes the term generated by convertFormulaTermList back into a list of terms. */
  def decodeTermList(listTrm: Expression): List[Term] = {
    listTrm match {
      case Pair(l,r) =>
        l :: decodeTermList(r)
      case Nothing => List()
      case _ =>
        throw ToolException("Expected a term list to decode but got a ill-formed expression: " + listTrm.prettyString)
    }
  }

  private def convertFormulaTermList(e: MExpr): Expression = {
    if (e.listQ) {
      val elements = e.args().map(convert)
      if (elements.nonEmpty && elements.forall(_.isInstanceOf[Formula])) {
        //@HACK first conjunct length(list)=... indicates length of list
        elements.map(_.asInstanceOf[Formula]).reduceOption(And) match {
          case Some(fmls) => And(Equal(LIST_LENGTH, Number(e.args.length)), fmls)
          case None => Equal(LIST_LENGTH, Number(e.args.length))
        }
      } else if (elements.forall(_.isInstanceOf[Term])) {
        (elements :+ Nothing).map(_.asInstanceOf[Term]).reduceRight(Pair)
      } else throw new ConversionException("Pegasus converter expected either list of formulas or list of terms")
    } else throw new ConversionException("Expected a list, but got " + e)
  }
}



/**
 * Mathematica counter example implementation.
  *
  * @author Nathan Fulton
 * @author Stefan Mitsch
 */
class MathematicaCEXTool(override val link: MathematicaLink) extends BaseKeYmaeraMathematicaBridge[Either[KExpr,NamedSymbol]](link, CEXK2MConverter, CEXM2KConverter) with CounterExampleTool with Logging {

  def findCounterExample(fml: Formula): Option[Map[NamedSymbol, Expression]] = {
    val cexVars = StaticSemantics.symbols(fml).filter({ case Function(_, _, _, _, interpreted) => !interpreted case _ => true})

    if (cexVars.nonEmpty) {
      val input = new MExpr(new MExpr(Expr.SYMBOL, "FindInstance"),
        Array(k2m.convert(Left(fml match { case Imply(a, b) => And(a, Not(b)) case _ => Not(fml) })),
          new MExpr(
            MathematicaSymbols.LIST,
            cexVars.toList.sorted.map(s => k2m.convert(Right(s))).toArray),
          new MExpr(Expr.SYMBOL, "Reals")))

      run(input) match {
        case (_, Left(cex: Formula)) => cex match {
          case False =>
            logger.debug("No counterexample, Mathematica returned: " + cex.prettyString)
            None
          case _ =>
            logger.debug("Counterexample " + cex.prettyString)
            Some(FormulaTools.conjuncts(cex).map({
              case Equal(name: DifferentialSymbol, value) => name -> value
              case Equal(name: Variable, value) => name -> value
              case Equal(FuncOf(fn, _), value) => fn -> value
              case Equiv(PredOf(fn, _), value) => fn -> value
            }).toMap)
        }
        case result =>
          logger.debug("No counterexample, Mathematica returned: " + result)
          None
      }
    } else {
      None
    }
  }
}

/**
  * A link to Mathematica using the JLink interface.
  *
  * @author Nathan Fulton
  * @author Stefan Mitsch
  */
class MathematicaODESolverTool(override val link: MathematicaLink) extends BaseKeYmaeraMathematicaBridge[KExpr](link, new UncheckedBaseK2MConverter, new UncheckedBaseM2KConverter) with ODESolverTool with DerivativeTool {

  def odeSolve(diffSys: DifferentialProgram, diffArg: Variable, iv: Map[Variable, Variable]): Option[Formula] =
    diffSol(diffArg, iv, toDiffSys(diffSys, diffArg):_*)

  /**
    * Converts a system of differential equations given as DifferentialProgram into list of x'=theta
    *
    * @param diffSys The system of differential equations
    * @param diffArg The name of the differential argument (dx/d diffArg = theta).
    * @return The differential equation system in list form.
    */
  private def toDiffSys(diffSys: DifferentialProgram, diffArg: Variable): List[(Variable, Term)] = {
    var result = List[(Variable, Term)]()
    ExpressionTraversal.traverse(new ExpressionTraversalFunction {
      override def preP(p: PosInExpr, e: Program): Either[Option[StopTraversal], Program] = e match {
        case AtomicODE(DifferentialSymbol(x), theta) if x != diffArg => result = result :+ (x, theta); Left(None)
        case AtomicODE(DifferentialSymbol(x), theta) if x == diffArg => Left(None)
        case ODESystem(_, _) => Left(None)
        case DifferentialProduct(_, _) => Left(None)
      }
    }, diffSys)
    result
  }

  /**
    * Computes the symbolic solution of a system of differential equations.
    *
    * @param diffArg The differential argument, i.e., d f(diffArg) / d diffArg.
    * @param diffSys The system of differential equations of the form x' = theta.
    * @return The solution if found; None otherwise
    */
  private def diffSol(diffArg: Variable, iv: Map[Variable, Variable], diffSys: (Variable, Term)*): Option[Formula] = {
    val primedVars = diffSys.map(_._1)
    val functionalizedTerms = diffSys.map{ case (x, theta) => ( x, functionalizeVars(theta, diffArg, primedVars:_*)) }
    val mathTerms = functionalizedTerms.map{case (x, theta) =>
      val diffSymbol = new MExpr(new MExpr(MathematicaSymbols.DERIVATIVE, Array[MExpr](new MExpr(1))), Array[MExpr](k2m(x)))
      (new MExpr(diffSymbol, Array[MExpr](k2m(diffArg))), k2m(theta))}
    val convertedDiffSys = mathTerms.map{case (x, theta) =>
      new MExpr(MathematicaSymbols.EQUALS, Array[MExpr](x, theta))}

    val functions = diffSys.map(t => k2m(functionalizeVars(t._1, diffArg)))

    val initialValues = diffSys.map(t => k2m(
      Equal(functionalizeVars(t._1, Number(BigDecimal(0)), primedVars:_*), iv(t._1))))

    val input = new MExpr(MathematicaSymbols.DSOLVE,
      Array[MExpr](
        new MExpr(Expr.SYM_LIST, (convertedDiffSys ++ initialValues).toArray),
        new MExpr(Expr.SYM_LIST, functions.toArray),
        k2m(diffArg)))
    val (_, result) = run(input)
    result match {
      case f: Formula => Some(defunctionalize(f, diffArg, primedVars.map(_.name):_*))
      case _ => None
    }
  }

  /**
    * Replaces all occurrences of variables vars in the specified term t with functions of argument arg.
    *
    * @param t The term.
    * @param arg The function argument.
    * @param vars The variables to functionalize.
    * @return The term with variables replaced by functions.
    */
  private def functionalizeVars(t: Term, arg: Term, vars: Variable*) = ExpressionTraversal.traverse(
    new ExpressionTraversalFunction {
      override def postT(p: PosInExpr, e: Term): Either[Option[StopTraversal], Term] = e match {
        case v@BaseVariable(name, idx, sort) if vars.isEmpty || vars.contains(v) =>
          Right(FuncOf(Function(name, idx, arg.sort, sort), arg))
        case _ => Left(None)
      }
    }, t) match {
    case Some(resultTerm) => resultTerm
    case None => throw new IllegalArgumentException("Unable to functionalize " + t)
  }

  /**
    * Replaces all functions with argument arg in formula f with a variable of the same name.
    *
    * @param f The formula.
    * @param arg The function argument.
    * @return The term with functions replaced by variables.
    */
  private def defunctionalize(f: Formula, arg: Term, fnNames: String*) = ExpressionTraversal.traverse(
    new ExpressionTraversalFunction {
      override def postT(p: PosInExpr, e: Term): Either[Option[StopTraversal], Term] = e match {
        case FuncOf(Function(name, idx, _, range, false), fnArg) if arg == fnArg
          && (fnNames.isEmpty || fnNames.contains(name)) => Right(Variable(name, idx, range))
        case _ => Left(None)
      }
    }, f) match {
    case Some(resultF) => resultF
    case None => throw new IllegalArgumentException("Unable to defunctionalize " + f)
  }

  def deriveBy(term: Term, v: Variable): Term = {
    val mathTerm = k2m(term)
    val mathVar = k2m(v)
    val input = new MExpr(MathematicaSymbols.D, Array[MExpr](mathTerm, mathVar))
    val (_, result) = run(input)
    result match {
      case t: Term => t
    }
  }
}

/**
  * A link to Mathematica using the JLink interface.
  *
  * @author Andre Platzer
  */
class MathematicaPDESolverTool(override val link: MathematicaLink) extends BaseKeYmaeraMathematicaBridge[KExpr](link, new UncheckedBaseK2MConverter, new DiffUncheckedM2KConverter) with PDESolverTool {

  def pdeSolve(diffSys: DifferentialProgram): Iterator[Term] = {
    val vars = DifferentialHelper.getPrimedVariables(diffSys).map(k2m).toArray
    val f = new MExpr(Expr.SYMBOL, DiffUncheckedM2KConverter.PREFIX + "f")
    val fall = new MExpr(f, vars)
    val characteristics:List[MExpr] = DifferentialHelper.atomicOdes(diffSys).map({
      case AtomicODE(DifferentialSymbol(x),t) =>
        new MExpr(MathematicaSymbols.MULT, Array[MExpr](
          k2m(t),
          new MExpr(MathematicaSymbols.D, Array[MExpr](fall, k2m(x)))
        ))
    })
    val pde = new MExpr(MathematicaSymbols.EQUALS,
      Array[MExpr](
        new MExpr(MathematicaSymbols.PLUS, characteristics.toArray),
        k2m(Number(0))
      )
    )
    val input = new MExpr(MathematicaSymbols.DSOLVE,
      Array[MExpr](
        pde,
        fall,
        new MExpr(Expr.SYM_LIST, vars),
        new MExpr(MathematicaSymbols.RULE, Array[MExpr](
          MathematicaSymbols.GENERATEDPARAMETERS,
          new MExpr(MathematicaSymbols.FUNCTION, Array[MExpr](new MExpr(Expr.SYMBOL, DiffUncheckedM2KConverter.PREFIX + "C")))
        ))
      ))
    val (_, result) = run(input)
    result match {
        //@note List[List[Rule]] are converted to Or of And of Equal where only the right side matters.
      case r: Equal => List(r.right).iterator
      case r: And => FormulaTools.conjuncts(r).map({case Equal(_,b) => b}).iterator
      case r: Or => FormulaTools.disjuncts(r).flatMap(disj=>FormulaTools.conjuncts(disj).map{case Equal(_,b) => b}).iterator
      case r => throw ToolException("Mathematica did not solve the PDE for : " + diffSys + "\n" + r)
    }
  }
}

object DiffUncheckedM2KConverter {
  val PREFIX = "xyk`"
}

/**
  * Accept n-ary arguments as for PDESolve.
  */
private class DiffUncheckedM2KConverter extends UncheckedBaseM2KConverter {

  override def convert(e: MExpr): KExpr = {
    if (e.listQ() && e.args().length == 1) {
      //HACK: convert unique Groebner basis result
      convert(e.args().head)
    } else super.convert(e)
  }

  protected override def convertAtomicTerm(e: MExpr): KExpr = interpretedSymbols.get(e.head) match {
    case Some(fn) => convertFunction(fn, e.args())
    case None =>
      toKeYmaera(e) match {
        case fn: Function =>
          insist(!fn.interpreted, "Expected uninterpreted function symbol, but got interpreted " + fn)
          convertFunction(fn, e.args())
        case result: Variable => result
      }
  }

  private def toKeYmaera(e: MExpr): NamedSymbol = {
    if (e.head.asString().startsWith(DiffUncheckedM2KConverter.PREFIX)) {
      val name = e.head.asString().substring(DiffUncheckedM2KConverter.PREFIX.length)
      val index = None
      val fnDomain = convertFunctionDomain(e.args())
      Function(name, index, fnDomain, Real, interpreted = false)
    } else {
      MathematicaNameConversion.toKeYmaera(e)
    }
  }


  protected def convertFunctionDomain(args: Array[MExpr]): Sort = {
    Range(1,args.length).foldRight[Sort](Real)((i,t)=>Tuple(Real,t))
  }
  protected override def convertFunction(fn: Function, args: Array[MExpr]): KExpr = {
    val arguments = args.map(convert).map(_.asInstanceOf[Term])
    FuncOf(fn, arguments.reduceRightOption[Term]((l, r) => Pair(l, r)).getOrElse(Nothing))
  }
}

/**
  * A link to Mathematica using the JLink interface.
  *
  * @author Andre Platzer
  */
class MathematicaEquationSolverTool(override val link: MathematicaLink) extends BaseKeYmaeraMathematicaBridge[KExpr](link, new UncheckedBaseK2MConverter, new UncheckedBaseM2KConverter) with EquationSolverTool {

  def solve(equations: Formula, vars: List[Expression]): Option[Formula] = {
    val eqs = k2m(equations)
    val v = vars.map(k2m)

    val input = new MExpr(MathematicaSymbols.SOLVE,
      Array[MExpr](
        eqs,
        new MExpr(Expr.SYM_LIST, v.toArray)
        ))
    val (_, result) = run(input)
    result match {
      case f: Formula => Some(f)
      case _ => None
    }
  }
}

/**
  * A link to Mathematica using the JLink interface.
  *
  * @author Andre Platzer
  */
class MathematicaAlgebraTool(override val link: MathematicaLink) extends BaseKeYmaeraMathematicaBridge[KExpr](link, new UncheckedBaseK2MConverter, PegasusM2KConverter) with AlgebraTool {

  override def quotientRemainder(term: Term, div: Term, x:Variable): (Term,Term) = {
    val t = k2m(term)
    val d = k2m(div)
    val v = k2m(x)

    val input = new MExpr(MathematicaSymbols.POLYNOMIALQUOTIENTREMAINDER,
      Array[MExpr](
        t,
        d,
        v
      ))
    val (_, result) = run(input)
    result match {
      case Pair(quot,Pair(rem,Nothing)) => (quot, rem)
      case r => throw new IllegalStateException("Unexpected output " + r + " of class " + r.getClass)
    }
  }

  override def groebnerBasis(polynomials: List[Term]): List[Term] = {
    //@note sort for stable results
    val vars = polynomials.flatMap(p => StaticSemantics.vars(p).symbols[NamedSymbol]).distinct.sorted
    val input = new MExpr(MathematicaSymbols.GROEBNERBASIS,
      Array[MExpr](
        new MExpr(Expr.SYM_LIST, polynomials.map(k2m).toArray),
        new MExpr(Expr.SYM_LIST, vars.map(k2m).toArray),
        new MExpr(MathematicaSymbols.RULE, Array[MExpr](
          MathematicaSymbols.MONOMIALORDER, MathematicaSymbols.DEGREEREVERSELEXICOGRAPHIC
        )),
        new MExpr(MathematicaSymbols.RULE, Array[MExpr](
          MathematicaSymbols.COEFFICIENTDOMAIN, MathematicaSymbols.RATIONALS
        ))
      ))
    val (_, result) = run(input)
    result match {
      // turn nested back into list of terms
      case r: Term => PegasusM2KConverter.decodeTermList(r)
      case r => throw new IllegalStateException("Unexpected output " + r + " of class " + r.getClass)
    }
  }

  override def polynomialReduce(polynomial: Term, GB: List[Term]): (List[Term], Term) = {
    //@note sort for stable results
    val vars = (polynomial::GB).flatMap(p => StaticSemantics.vars(p).symbols[NamedSymbol]).distinct.sorted
    val input = new MExpr(MathematicaSymbols.POLYNOMIALREDUCE,
      Array[MExpr](
        k2m(polynomial),
        new MExpr(Expr.SYM_LIST, GB.map(k2m).toArray),
        new MExpr(Expr.SYM_LIST, vars.map(k2m).toArray),
        new MExpr(MathematicaSymbols.RULE, Array[MExpr](
          MathematicaSymbols.MONOMIALORDER, MathematicaSymbols.DEGREEREVERSELEXICOGRAPHIC
        )),
        new MExpr(MathematicaSymbols.RULE, Array[MExpr](
          MathematicaSymbols.COEFFICIENTDOMAIN, MathematicaSymbols.RATIONALS
        ))
      ))
    val (_, result) = run(input)
    result match {
      //Mathematica returns [l,r] where l is a list, r is a singleton
      case Pair(l,Pair(r,Nothing)) => (PegasusM2KConverter.decodeTermList(l), r)
      case r => throw new IllegalStateException("Unexpected output " + r + " of class " + r.getClass)
    }
  }
}

/**
  * A link to Mathematica using the JLink interface.
  *
  * @author Andre Platzer
  */
class MathematicaSimplificationTool(override val link: MathematicaLink) extends BaseKeYmaeraMathematicaBridge[KExpr](link, new UncheckedBaseK2MConverter, new UncheckedBaseM2KConverter) with SimplificationTool {

  override def simplify(expr: Formula, assumptions: List[Formula]): Formula =
    simplify(expr.asInstanceOf[Expression], assumptions).asInstanceOf[Formula]

  override def simplify(expr: Term, assumptions: List[Formula]): Term =
    simplify(expr.asInstanceOf[Expression], assumptions).asInstanceOf[Term]

  override def simplify(expr: Expression, assumptions: List[Formula]): Expression = expr match {
    case _: AtomicTerm => expr
    case _ =>
      val ex = k2m(expr)
      val assuming = assumptions.map(k2m)

      val input = new MExpr(MathematicaSymbols.FULLSIMPLIFY,
        Array[MExpr](
          ex,
          new MExpr(Expr.SYM_LIST, assuming.toArray)
        ))
      val (_, result) = run(input)
      result match {
        case r: Expression => r
        case _ => throw ToolException("Mathematica did not successfuly simplify: " + expr + " under assumptions " + assumptions)
      }
  }
}

object SimulationM2KConverter extends M2KConverter[Simulation] {

  //@note unchecked, because in back-translation we don't know whether end-of-world 'false' was present in original or not
  def k2m: K2MConverter[Simulation] = null
  override def apply(e: MExpr): Simulation = convert(e)

  private val m2k: M2KConverter[KExpr] = new UncheckedBaseM2KConverter

  override def convert(e: MExpr): Simulation = {
    if (e.listQ() && e.args.forall(_.listQ())) {
      val states: Array[Array[KExpr]] = e.args().map(_.args().map(m2k))
      states.map(state => {
        val endOfWorld = if (state.contains(False)) state.indexOf(False) else state.length
        state.slice(0, endOfWorld).map({
          case fml: Formula => ToolConversions.flattenConjunctions(fml).map({
            case Equal(name: NamedSymbol, value: Number) => name -> value
            case Equal(FuncOf(fn, _), value: Number) => fn -> value
            case s => throw new IllegalArgumentException("Expected state description Equal(...), but got " + s)
          }).toMap
          case s => throw new IllegalArgumentException("Expected state formula, but got " + s)
        }).toList}).toList
    } else throw new IllegalArgumentException("Expected list of simulation states, but got " + e)
  }
}

object SimulationK2MConverter extends K2MConverter[Simulation] {

  //@note unchecked, because don't know whether end-of-world 'false' was present in original or not
  def m2k: M2KConverter[Simulation] = null
  override def apply(e: Simulation): MExpr = convert(e)

  private val k2m: K2MConverter[KExpr] = new UncheckedBaseK2MConverter

  override def convert(e: Simulation): MExpr = {
    new MExpr(MathematicaSymbols.LIST,
      e.map(r => new MExpr(MathematicaSymbols.LIST,
        r.map(s => k2m(s.map{ case (n: Term, v) => Equal(n, v) }.reduceLeft(And))).toArray)).toArray)
  }
}

/**
  * A link to Mathematica using the JLink interface.
  *
  * @author Nathan Fulton
  * @author Stefan Mitsch
  */
class MathematicaSimulationTool(override val link: MathematicaLink) extends BaseKeYmaeraMathematicaBridge[Simulation](link, SimulationK2MConverter, SimulationM2KConverter) with SimulationTool {
  private val basek2m = new UncheckedBaseK2MConverter

  def simulate(initial: Formula, stateRelation: Formula, steps: Int = 10, n: Int = 1): Simulation = {
    // init[n_] := Map[List[#]&, FindInstance[a>=..., {a, ...}, Reals, n]] as pure function
    val init = new MExpr(new MExpr(Expr.SYMBOL, "SetDelayed"), Array(
      new MExpr(Expr.SYMBOL, "init"),
      new MExpr(new MExpr(Expr.SYMBOL, "Function"), Array(
        new MExpr(new MExpr(Expr.SYMBOL, "Map"), Array(
          new MExpr(new MExpr(Expr.SYMBOL, "Function"), Array(new MExpr(MathematicaSymbols.LIST, Array(new MExpr(Expr.SYMBOL, "#"))))),
          new MExpr(new MExpr(Expr.SYMBOL,  "FindInstance"),
            Array(basek2m(initial),
              new MExpr(
                MathematicaSymbols.LIST,
                StaticSemantics.symbols(initial).toList.sorted.map(basek2m).toArray),
              new MExpr(Expr.SYMBOL, "Reals"),
              new MExpr(Expr.SYMBOL, "#")))))))))
    // step[pre_] := Module[{apre=a/.pre, ...}, FindInstance[apre>=..., {a, ...}, Reals]] as pure function
    val (stepPreVars, stepPostVars) = StaticSemantics.symbols(stateRelation).partition(_.name.startsWith("pre"))
    val pre2post = stepPostVars.filter(_.name != "t_").map(v => Variable("pre" + v.name, v.index, v.sort) -> v).toMap[NamedSymbol, NamedSymbol]
    val stepModuleInit = stepPreVars.toList.sorted.map(s =>
      new MExpr(new MExpr(Expr.SYMBOL, "Set"), Array(
        basek2m(s),
        new MExpr(new MExpr(Expr.SYMBOL, "ReplaceAll"), Array(
          basek2m(pre2post.getOrElse(s, throw new IllegalArgumentException("No post variable for " + s.prettyString))),
          new MExpr(Expr.SYMBOL, "#")))))).toArray
    val step = new MExpr(new MExpr(Expr.SYMBOL, "SetDelayed"), Array(
      new MExpr(Expr.SYMBOL, "step"),
      new MExpr(new MExpr(Expr.SYMBOL, "Function"),
        Array(new MExpr(new MExpr(Expr.SYMBOL, "Module"),
          Array(new MExpr(MathematicaSymbols.LIST, stepModuleInit),
            new MExpr(new MExpr(Expr.SYMBOL,  "FindInstance"),
              Array(basek2m(stateRelation),
                new MExpr(
                  MathematicaSymbols.LIST,
                  stepPostVars.toList.sorted.map(basek2m).toArray),
                new MExpr(Expr.SYMBOL, "Reals")))))))))
    // Map[N[NestList[step,#,steps]]&, init[n]]
    val exec = new MExpr(new MExpr(Expr.SYMBOL, "Map"), Array(
      new MExpr(new MExpr(Expr.SYMBOL, "Function"),
        Array(new MExpr(new MExpr(Expr.SYMBOL, "N"), Array(new MExpr(new MExpr(Expr.SYMBOL, "NestList"),
          Array(new MExpr(Expr.SYMBOL, "step"), new MExpr(Expr.SYMBOL, "#"), new MExpr(steps))))))),
      new MExpr(new MExpr(Expr.SYMBOL, "Apply"), Array(new MExpr(Expr.SYMBOL, "init"),
        new MExpr(MathematicaSymbols.LIST, Array(new MExpr(n)))))))
    // initial;step;simulate
    val simulate = new MExpr(new MExpr(Expr.SYMBOL, "CompoundExpression"), Array(init, step, exec))

    run(simulate)._2
  }

  def simulateRun(initial: SimState, stateRelation: Formula, steps: Int = 10): SimRun = ???
}
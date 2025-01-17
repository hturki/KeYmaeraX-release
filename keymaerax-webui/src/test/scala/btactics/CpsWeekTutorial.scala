/**
* Copyright (c) Carnegie Mellon University.
* See LICENSE.txt for the conditions of this license.
*/
package edu.cmu.cs.ls.keymaerax.btactics

import edu.cmu.cs.ls.keymaerax.Configuration
import edu.cmu.cs.ls.keymaerax.bellerophon.parser.BelleParser
import edu.cmu.cs.ls.keymaerax.bellerophon.{OnAll, SaturateTactic}
import edu.cmu.cs.ls.keymaerax.btactics.DebuggingTactics.print
import edu.cmu.cs.ls.keymaerax.btactics.TactixLibrary._
import edu.cmu.cs.ls.keymaerax.btactics.Augmentors._
import edu.cmu.cs.ls.keymaerax.btactics.InvariantGenerator.GenProduct
import edu.cmu.cs.ls.keymaerax.core.{DotTerm, Formula, Function, Real, SubstitutionPair, USubst, Unit}
import edu.cmu.cs.ls.keymaerax.parser.KeYmaeraXArchiveParser
import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
import edu.cmu.cs.ls.keymaerax.tags.SlowTest
import testHelper.ParserFactory._

import scala.language.postfixOps
import org.scalatest.LoneElement._
import testHelper.KeYmaeraXTestTags.TodoTest

/**
 * Tutorial test cases.
 *
 * @author Stefan Mitsch
 */
@SlowTest
class CpsWeekTutorial extends TacticTestBase {

  "Example 0" should "prove with abstract invariant J(x)" in withQE { _ =>
    val s = parseToSequent(getClass.getResourceAsStream("/examples/tutorials/cpsweek/00_robosimple.kyx"))
    val tactic = implyR('R) & SaturateTactic(andL('L)) & loop("J(v)".asFormula)('R) <(
      skip,
      skip,
      print("Step") & normalize & OnAll(solve('R))
      ) & US(USubst(SubstitutionPair(
            "J(v)".asFormula.replaceFree("v".asTerm, DotTerm()), "v<=10".asFormula.replaceFree("v".asTerm, DotTerm()))::Nil)) & OnAll(QE)

    proveBy(s, tactic) shouldBe 'proved
  }

  "Example 1" should "have 4 open goals for abstract invariant J(x,v)" in withQE { _ =>
    val s = parseToSequent(getClass.getResourceAsStream("/examples/tutorials/cpsweek/01_robo1.kyx"))
    val tactic = implyR('R) & SaturateTactic(andL('L)) & loop("J(x,v)".asFormula)('R) <(
      print("Base case"),
      print("Use case"),
      print("Step") & normalize & OnAll(solve('R))
      )
    val result = proveBy(s, tactic)
    result.subgoals should have size 4
    result.subgoals(0) shouldBe "x!=m(), b()>0 ==> J(x,v)".asSequent
    result.subgoals(1) shouldBe "J(x,v), b()>0 ==> x!=m()".asSequent
    result.subgoals(2) shouldBe "J(x,v), b()>0, !SB(x,m()) ==> \\forall t_ (t_>=0 -> J(a*(t_^2/2)+v*t_+x,a*t_+v))".asSequent
    result.subgoals(3) shouldBe "J(x,v), b()>0 ==> \\forall t_ (t_>=0 -> J((-b())*(t_^2/2)+v*t_+x,(-b())*t_+v))".asSequent
  }

  it should "have 4 open goals for abstract invariant J(x,v) with master" in withQE { _ =>
    val s = parseToSequent(getClass.getResourceAsStream("/examples/tutorials/cpsweek/01_robo1.kyx"))
    val cgen = TactixLibrary.invGenerator match { case c: ConfigurableGenerator[GenProduct] => c }
    val result = proveBy(s, explore(cgen))
    result.subgoals should have size 4
    result.subgoals(0) shouldBe "x!=m(), b()>0 ==> J(x,v)".asSequent
    result.subgoals(1) shouldBe "J(x,v), b()>0 ==> x!=m()".asSequent
    result.subgoals(2) shouldBe "J(x,v), b()>0, !SB(x,m()), t_>=0 ==> J(a*(t_^2/2)+v*t_+x,a*t_+v)".asSequent
    result.subgoals(3) shouldBe "J(x,v), b()>0, t_>=0 ==> J((-b())*(t_^2/2)+v*t_+x,(-b())*t_+v)".asSequent
  }

  it should "prove automatically with the correct conditions" in withQE { _ =>
    val s = parseToSequent(getClass.getResourceAsStream("/examples/tutorials/cpsweek/01_robo1-full.kyx"))
    proveBy(s, master()) shouldBe 'proved
  }

  it should "prove with a manually written searchy tactic" in withMathematica { _ =>
    val s = parseToSequent(getClass.getResourceAsStream("/examples/tutorials/cpsweek/01_robo1-full.kyx"))
    val tactic = implyR('R) & SaturateTactic(andL('L)) & loop("v^2<=2*b()*(m()-x)".asFormula)('R) <(
      print("Base case") & closeId,
      print("Use case") & QE,
      print("Step") & normalize & solve('R) & QE
      )
    proveBy(s, tactic) shouldBe 'proved
  }

  it should "stop after ODE to let users inspect a counterexample with false speed sb condition" in withQE { _ =>
    val modelContent = io.Source.fromInputStream(getClass.getResourceAsStream("/examples/tutorials/cpsweek/01_robo1-falsespeedsb.kyx")).mkString
    val tactic = BelleParser(io.Source.fromInputStream(getClass.getResourceAsStream("/examples/tutorials/cpsweek/01_robo1-falsespeedsb.kyt")).mkString)
    val result = proveBy(KeYmaeraXArchiveParser.parseAsProblemOrFormula(modelContent), tactic)
    result.subgoals should have size 2
  }

  "Example 2" should "have expected open goal and a counter example" in withMathematica { tool =>
    val s = parseToSequent(getClass.getResourceAsStream("/examples/tutorials/cpsweek/02_robo2-justbrakenaive.kyx"))
    val result = proveBy(s, master(keepQEFalse=false))
    result.isProved shouldBe false //@note This assertion is a soundness check!
    result.subgoals.loneElement shouldBe "x<=m(), b()>0, t_>=0, \\forall s_ (0<=s_&s_<=t_ -> (-b())*s_+v>=0) ==> (-b())*(t_^2/2)+v*t_+x <= m()".asSequent

    // counter example
    tool.findCounterExample(result.subgoals.head.toFormula).get.keySet should contain only (Function("b", None, Unit, Real),
      "x".asVariable, "v".asVariable, Function("m", None, Unit, Real), "t_".asVariable, "s_".asVariable)
    // can't actually check cex values, may differ from run to run

    // cut in concrete values to get nicer CEX
    val cutFml = "x=1 & v=2 & m()=x+3".asFormula
    val afterCut = proveBy(result.subgoals.head, cut(cutFml))
    afterCut.subgoals should have size 2
    afterCut.subgoals.head.ante should contain (cutFml)
    val cex = tool.findCounterExample(afterCut.subgoals.head.toFormula).get
    cex.keySet should contain only (Function("b", None, Unit, Real), "x".asVariable, "v".asVariable,
      Function("m", None, Unit, Real), "t_".asVariable, "s_".asVariable)
    cex.get("x".asVariable) should contain ("1".asTerm)
    cex.get("v".asVariable) should contain ("2".asTerm)
    cex.get(Function("m", None, Unit, Real)) should contain ("4".asTerm)
  }

  it should "find the braking condition" in withMathematica { _ =>
    val s = parseToSequent(getClass.getResourceAsStream("/examples/tutorials/cpsweek/02_robo2-justbrakenaive.kyx"))
    val result = proveBy(s, master(keepQEFalse=false))
    result.subgoals.loneElement shouldBe "x<=m(), b()>0, t_>=0, \\forall s_ (0<=s_&s_<=t_ -> (-b())*s_+v>=0) ==> (-b())*(t_^2/2)+v*t_+x <= m()".asSequent

    val initCond = proveBy(result.subgoals.head, TactixLibrary.partialQE)
    initCond.subgoals.loneElement shouldBe "x<=m(), b()>0, t_>=0, \\forall s_ (0<=s_&s_<=t_->(-b())*s_+v>=0) ==> v<=0|v>0&((t_<=0|(0 < t_&t_<=v*b()^-1)&x<=1/2*(-2*t_*v+t_^2*b()+2*m()))|t_>v*b()^-1)".asSequent

    val simpler = proveBy(initCond.subgoals.head, TactixLibrary.transform("b()=v/t_ & t_>0 & m() >= -b()/2*t_^2+v*t_+x".asFormula)(1))
    simpler.subgoals.loneElement shouldBe "x<=m(), b()>0, t_>=0, \\forall s_ (0<=s_&s_<=t_->(-b())*s_+v>=0) ==> b()=v/t_ & t_>0 & m() >= -b()/2*t_^2+v*t_+x".asSequent

    // now let's transform once again and put in t_ = v/b
    val cond = proveBy(simpler.subgoals.head, TactixLibrary.transform("t_=v/b() & v>0 & m()-x >= v^2/(2*b())".asFormula)(1))
    cond.subgoals.loneElement shouldBe "x<=m(), b()>0, t_>=0, \\forall s_ (0<=s_&s_<=t_->(-b())*s_+v>=0) ==> t_=v/b() & v>0 & m()-x >= v^2/(2*b())".asSequent
  }

  it should "prove braking automatically with the correct condition" in withQE { _ =>
    val s = parseToSequent(getClass.getResourceAsStream("/examples/tutorials/cpsweek/03_robo2-justbrake.kyx"))
    proveBy(s, master()) shouldBe 'proved
  }

  it should "find the acceleration condition" in withMathematica { _ =>
    val s = parseToSequent(getClass.getResourceAsStream("/examples/tutorials/cpsweek/04_robo2-justaccnaive.kyx"))
    val result = proveBy(s, master(keepQEFalse=false))
    result.subgoals.loneElement shouldBe "A()>=0, b()>0, v^2<=2*b()*(m()-x), ep()>0, Q(x,v), t_>=0, \\forall s_ (0<=s_&s_<=t_ -> A()*s_+v>=0 & s_+0<=ep()) ==> (A()*t_+v)^2 <= 2*b()*(m()-(A()*(t_^2/2)+v*t_+x))".asSequent

    val initCond = proveBy(result.subgoals.head, hideL(-5, "Q(x,v)".asFormula) & TactixLibrary.partialQE)
    initCond.subgoals.loneElement shouldBe "A()>=0, b()>0, v^2<=2*b()*(m()-x), ep()>0, t_>=0, \\forall s_ (0<=s_&s_<=t_->A()*s_+v>=0&s_<=ep()) ==> t_<=0|t_>0&(ep() < t_|ep()>=t_&((v < 0|v=0&(A()<=0|A()>0&(m() < x|m()>=1/2*b()^-1*(t_^2*A()^2+2*x*b()+t_^2*A()*b()))))|v>0&(m() < 1/2*b()^-1*(v^2+2*x*b())|m()>=1/2*b()^-1*(v^2+2*t_*v*A()+t_^2*A()^2+2*t_*v*b()+2*x*b()+t_^2*A()*b()))))".asSequent

    // now get rid of stuff that violates our assumptions and transform into nicer shape
    val simpler = proveBy(initCond.subgoals.head, TactixLibrary.transform("m()>=1/2*b()^-1*(A()^2*t_^2+A()*b()*t_^2+2*A()*t_*v+2*b()*t_*v+v^2+2*b()*x)".asFormula)(1))
    simpler.subgoals.loneElement shouldBe "A()>=0, b()>0, v^2<=2*b()*(m()-x), ep()>0, t_>=0, \\forall s_ (0<=s_&s_<=t_->A()*s_+v>=0&s_<=ep()) ==> m()>=1/2*b()^-1*(A()^2*t_^2+A()*b()*t_^2+2*A()*t_*v+2*b()*t_*v+v^2+2*b()*x)".asSequent

    val cond = proveBy(simpler.subgoals.head, TactixLibrary.transform("m()-x >= v^2/(2*b())+(A()/b()+1)*(A()/2*t_^2 + v*t_)".asFormula)(1))
    cond.subgoals.loneElement shouldBe "A()>=0, b()>0, v^2<=2*b()*(m()-x), ep()>0, t_>=0, \\forall s_ (0<=s_&s_<=t_->A()*s_+v>=0&s_<=ep()) ==> m()-x >= v^2/(2*b())+(A()/b()+1)*(A()/2*t_^2 + v*t_)".asSequent
  }

  it should "prove acceleration automatically with the correct condition" in withQE { _ =>
    val s = parseToSequent(getClass.getResourceAsStream("/examples/tutorials/cpsweek/05_robo2-justacc.kyx"))
    proveBy(s, master()) shouldBe 'proved
  }

  it should "prove the full model" in withQE { _ =>
    val s = KeYmaeraXArchiveParser.getEntry("CPSWeek Tutorial Example 1", io.Source.fromInputStream(
      getClass.getResourceAsStream("/examples/tutorials/cpsweek/cpsweek.kyx")).mkString).get.model.asInstanceOf[Formula]
    proveBy(s, master()) shouldBe 'proved
  }

  it should "find a hint for SB from parsed tactic" in withMathematica { _ =>
    val modelContent = io.Source.fromInputStream(getClass.getResourceAsStream("/examples/tutorials/cpsweek/06_robo2-fullnaive.kyx")).mkString
    val tactic = BelleParser(io.Source.fromInputStream(getClass.getResourceAsStream("/examples/tutorials/cpsweek/06_robo2-fullnaive.kyt")).mkString)
    val result = proveBy(KeYmaeraXArchiveParser.parseAsProblemOrFormula(modelContent), tactic)
    result.subgoals should have size 2
    //simplified some
    result.subgoals.last.succ should contain only "(m() < 0&(t<=0&((v < 0|v=0&(A()<=0|A()>0&x<=1/2*((-t^2)*A()+2*t*A()*ep()+(-A())*ep()^2+2*m())))|v>0&x<=1/2*(2*t*v+(-t^2)*A()+-2*v*ep()+2*t*A()*ep()+(-A())*ep()^2+2*m()))|t>0&((v < 0|v=0&((ep() < t|ep()=t&(A()<=0|A()>0&(x < m()|x=1/2*((-t^2)*A()+2*t*A()*ep()+(-A())*ep()^2+2*m()))))|ep()>t&(A()<=0|A()>0&x<=1/2*((-t^2)*A()+2*t*A()*ep()+(-A())*ep()^2+2*m()))))|v>0&((ep() < t|ep()=t&(x < m()|x=1/2*(2*t*v+(-t^2)*A()+-2*v*ep()+2*t*A()*ep()+(-A())*ep()^2+2*m())))|ep()>t&x<=1/2*(2*t*v+(-t^2)*A()+-2*v*ep()+2*t*A()*ep()+(-A())*ep()^2+2*m()))))|m()=0&(t<=0&((v < 0|v=0&(A()<=0|A()>0&(x<=1/2*((-t^2)*A()+2*t*A()*ep()+(-A())*ep()^2)|x>0)))|v>0&(x<=1/2*(2*t*v+(-t^2)*A()+-2*v*ep()+2*t*A()*ep()+(-A())*ep()^2)|x>0))|t>0&((v < 0|v=0&(ep()<=t|ep()>t&(A()<=0|A()>0&(x<=1/2*((-t^2)*A()+2*t*A()*ep()+(-A())*ep()^2)|x>0))))|v>0&(ep()<=t|ep()>t&(x<=1/2*(2*t*v+(-t^2)*A()+-2*v*ep()+2*t*A()*ep()+(-A())*ep()^2)|x>0)))))|m()>0&((t < 0&((v < 0|v=0&(A()<=0|A()>0&x<=1/2*((-t^2)*A()+2*t*A()*ep()+(-A())*ep()^2+2*m())))|v>0&x<=1/2*(2*t*v+(-t^2)*A()+-2*v*ep()+2*t*A()*ep()+(-A())*ep()^2+2*m()))|t=0&((v < 0|v=0&(A()<=0|A()>0&x<=1/2*((-A())*ep()^2+2*m())))|v>0&x<=1/2*(-2*v*ep()+(-A())*ep()^2+2*m())))|t>0&((v < 0|v=0&(ep()<=t|ep()>t&(A()<=0|A()>0&x<=1/2*((-t^2)*A()+2*t*A()*ep()+(-A())*ep()^2+2*m()))))|v>0&(ep()<=t|ep()>t&x<=1/2*(2*t*v+(-t^2)*A()+-2*v*ep()+2*t*A()*ep()+(-A())*ep()^2+2*m()))))".asFormula
    //@todo simplify with FullSimplify
  }

  "A searchy tactic" should "prove both a simple and a complicated model" in withMathematica { _ =>
    def tactic(j: Formula) = implyR('R) & SaturateTactic(andL('L)) & loop(j)('R) <(
      print("Base case") & closeId,
      print("Use case") & QE,
      print("Step") & normalize & OnAll(solve('R) & QE)
      )

    val simple = parseToSequent(getClass.getResourceAsStream("/examples/tutorials/cpsweek/01_robo1-full.kyx"))
    proveBy(simple, tactic("v^2<=2*b()*(m()-x)".asFormula)) shouldBe 'proved

    val harder = KeYmaeraXArchiveParser.getEntry("CPSWeek Tutorial Example 1", io.Source.fromInputStream(
      getClass.getResourceAsStream("/examples/tutorials/cpsweek/cpsweek.kyx")).mkString).get.model.asInstanceOf[Formula]
    proveBy(harder, tactic("v^2<=2*b()*(m()-x)".asFormula)) shouldBe 'proved
  }

  "2D Car" should "be provable" in withQE ({ _ =>
    val s = KeYmaeraXArchiveParser.getEntry("CPSWeek Tutorial Example 4", io.Source.fromInputStream(
      getClass.getResourceAsStream("/examples/tutorials/cpsweek/cpsweek.kyx")).mkString).get.model.asInstanceOf[Formula]

    def di(a: String) = {
      val accCond = "2*b()*abs(mx-x)>v^2+(A()+b())*(A()*ep()^2+2*ep()*v)|2*b()*abs(my-y)>v^2+(A()+b())*(A()*ep()^2+2*ep()*v)".asFormula
      diffInvariant("dx^2+dy^2=1".asFormula, "t>=0".asFormula, s"v=old(v)+$a*t".asFormula)('R) &
      DebuggingTactics.print("Now what?") &
      dC(s"-t*(v-$a/2*t)<=x-old(x) & x-old(x)<=t*(v-$a/2*t) & -t*(v-$a/2*t)<=y-old(y) & y-old(y)<=t*(v-$a/2*t)".asFormula)('R) <(
        skip,
        Idioms.doIf(_.subgoals.head.ante.contains(accCond))(hideL('L, accCond)) &
        (boxAnd('R) & andR('R) <(dI()('R), skip))*3 & dI()('R) & done
      )
    }

    val dw = exhaustiveEqR2L(hide=true)('Llast)*3 /* 3 old(...) in DI */ & SaturateTactic(andL('L)) &
      print("Before diffWeaken") & dW(1) & print("After diffWeaken")

    def hideQE(x: String) = SaturateTactic(hideL('Llike, "dx^2+dy^2=1".asFormula)) & hideL('L, "t<=ep()".asFormula) &
      hideL('L, s"-t*(v--b()/2*t)<=$x-${x}_0".asFormula) & hideL('L, s"$x-${x}_0<=t*(v--b()/2*t)".asFormula) &
      hideL('L, "r!=0".asFormula) & hideL('L, "A()>=0".asFormula) & hideL('L, "ep()>0".asFormula) &
      hideR('R, s"2*b()*abs(m$x-$x)>v^2".asFormula)

    val tactic = implyR('R) & SaturateTactic(andL('L)) & loop("r!=0 & v>=0 & dx^2+dy^2=1 & (2*b()*abs(mx-x)>v^2 | 2*b()*abs(my-y)>v^2)".asFormula)('R) <(
      print("Base case") & QE,
      print("Use case") & QE,
      print("Step") & chase('R) & andR('R) <(
        allR('R) & implyR('R) & di("-b()") & dw & prop <(hideQE("y") & QE, hideQE("x") & QE)
        ,
        // in tutorial: only show braking branch, acceleration takes too long (needs abs and hiding and cuts etc.)
        SaturateTactic(allR('R) | implyR('R)) & di("A()") & dw
        )
      )

    withTemporaryConfig(Map(Configuration.Keys.QE_ALLOW_INTERPRETED_FNS -> "true")) {
      proveBy(s, tactic).subgoals should have size 1
    }
  }, 300)

  "Motzkin" should "be provable with DI+DW" in withQE { _ =>
    val s = KeYmaeraXArchiveParser.getEntry("CPSWeek Tutorial Example 3", io.Source.fromInputStream(
      getClass.getResourceAsStream("/examples/tutorials/cpsweek/cpsweek.kyx")).mkString).get.model.asInstanceOf[Formula]
    val tactic = implyR('R) & diffInvariant("x1^4*x2^2+x1^2*x2^4-3*x1^2*x2^2+1 <= c".asFormula)('R) & dW('R) & prop
    proveBy(s, tactic) shouldBe 'proved
  }

  "Damped oscillator" should "be provable with DI+DW" in withQE { _ =>
    val s = KeYmaeraXArchiveParser.getEntry("CPSWeek Tutorial Example 2", io.Source.fromInputStream(
      getClass.getResourceAsStream("/examples/tutorials/cpsweek/cpsweek.kyx")).mkString).get.model.asInstanceOf[Formula]
    val tactic = implyR('R) & dI()('R)
    proveBy(s, tactic) shouldBe 'proved
  }

  "Self crossing" should "be provable with DI+DW" in withQE { _ =>
    val s = parseToSequent(getClass.getResourceAsStream("/examples/tutorials/cpsweek/10-diffinv-self-crossing.kyx"))
    val tactic = implyR('R) & diffInvariant("x^2+x^3-y^2-c=0".asFormula)('R) & dW('R) & prop
    proveBy(s, tactic) shouldBe 'proved
  }

}

package edu.cmu.cs.ls.keymaerax.btactics

import edu.cmu.cs.ls.keymaerax.bellerophon._
import edu.cmu.cs.ls.keymaerax.bellerophon.parser.BelleParser
import edu.cmu.cs.ls.keymaerax.btactics.DebuggingTactics.error
import edu.cmu.cs.ls.keymaerax.btactics.TactixLibrary._
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.parser.StringConverter._
import edu.cmu.cs.ls.keymaerax.pt.ProvableSig
import org.scalatest.time.SpanSugar._
import testHelper.KeYmaeraXTestTags.SlowTest

import scala.collection.immutable.IndexedSeq
import scala.collection.mutable
import scala.language.postfixOps

/**
 * Very fine-grained tests of the sequential interpreter.
 * These tests are all I/O tests, so it should be possible to switch out
 * theInterpreter when other interpreters are implemented.
 * @author Nathan Fulton
 */
class SequentialInterpreterTests extends TacticTestBase {

  "AndR" should "prove |- 1=1 ^ 2=2" in {
    val tactic = andR(1)
    val v = {
      val f = "1=1 & 2=2".asFormula
      BelleProvable(ProvableSig.startProof(f))
    }
    val result = theInterpreter.apply(tactic, v)

    val expectedResult = Seq(
      Sequent(IndexedSeq(), IndexedSeq("1=1".asFormula)),
      Sequent(IndexedSeq(), IndexedSeq("2=2".asFormula))
    )

    result shouldBe a[BelleProvable]
    result.asInstanceOf[BelleProvable].p.subgoals shouldBe expectedResult
  }

  "Sequential Combinator" should "prove |- 1=2 -> 1=2" in {
    val tactic = implyR(1) & close
    val v = {
      val f = "1=2 -> 1=2".asFormula
      BelleProvable(ProvableSig.startProof(f))
    }
    val result = theInterpreter.apply(tactic, v)

    result shouldBe a[BelleProvable]
    result.asInstanceOf[BelleProvable].p shouldBe 'proved
  }

  "Either combinator" should "prove |- 1=2 -> 1=2 by AndR | (ImplyR & Close)" in {
    val tactic = andR(1) | (implyR(1) & close)
    val v = {
      val f = "1=2 -> 1=2".asFormula
      BelleProvable(ProvableSig.startProof(f))
    }
    val result = theInterpreter.apply(tactic, v)

    result shouldBe a[BelleProvable]
    result.asInstanceOf[BelleProvable].p shouldBe 'proved
  }

  it should "prove |- 1=2 -> 1=2 by (ImplyR & Close) | AndR" in {
    val tactic = (implyR(1) & close) | andR(1)
    val v = {
      val f = "1=2 -> 1=2".asFormula
      BelleProvable(ProvableSig.startProof(f))
    }
    val result = theInterpreter.apply(tactic, v)

    result shouldBe a[BelleProvable]
    result.asInstanceOf[BelleProvable].p shouldBe 'proved
  }

  it should "failover to right whenever a non-closing and non-partial tactic is provided on the left" in {
    val tactic = implyR(1)& DebuggingTactics.done  | skip& DebuggingTactics.done
    
    a[Throwable] shouldBe thrownBy(
      shouldResultIn(
        tactic,
        "1=2 -> 1=2".asFormula,
        Seq(Sequent(IndexedSeq(), IndexedSeq("1=2 -> 1=2".asFormula)))
      )
    )
  }

  it should "fail when neither tactic manages to close the goal and also neither is partial" in {
    val tactic = implyR(1) & DebuggingTactics.done | (skip & skip) & DebuggingTactics.done
    val f = "1=2 -> 1=2".asFormula
    a[BelleThrowable] should be thrownBy theInterpreter(tactic, BelleProvable(ProvableSig.startProof(f))
    )
  }

  "After combinator" should "prove a simple property" in {
    val result = theInterpreter.apply(implyR(1) > close, BelleProvable(ProvableSig.startProof("1=2 -> 1=2".asFormula)))
    result shouldBe a[BelleProvable]
    result.asInstanceOf[BelleProvable].p shouldBe 'proved
  }

  it should "run right tactic on left failure" in {
    val right = new DependentTactic("ANON") {
      override def computeExpr(v: BelleValue): BelleExpr = v match {
        case _: BelleProvable => fail("Expected a BelleThrowable, but got a BelleProvable")
        case err: BelleValue with BelleThrowable => throw err
      }
    }
    the [BelleThrowable] thrownBy theInterpreter.apply(andR(1) > right, BelleProvable(ProvableSig.startProof("1=2 -> 1=2".asFormula))) should
      have message "[Bellerophon Runtime] Tactic andR applied at Fixed(1,None,true) on a non-matching expression in ElidingProvable(Provable{\n==> 1:  1=2->1=2\tImply\n  from\n==> 1:  1=2->1=2\tImply})"
  }

  "OnAll combinator" should "prove |- (1=1->1=1) & (2=2->2=2)" in {
    val f = "(1=1->1=1) & (2=2->2=2)".asFormula
    val expr = andR(SuccPos(0)) & OnAll (implyR(SuccPos(0)) & close)
    shouldClose(expr, f)
  }

  it should "move inside Eithers correctly" in {
    val f = "(1=1->1=1) & (2=2->2=2)".asFormula
    val expr = andR(SuccPos(0)) & OnAll (andR(SuccPos(0)) | (implyR(SuccPos(0)) & close))
    shouldClose(expr, f)
  }

  it should "support 'Rlike' unification matching" in {
    val result = proveBy("(a=0&b=1 -> c=2) | (d=3 -> e=4) | (f=5&g=6 -> h=7)".asFormula,
      SaturateTactic(orR('R)) & SaturateTactic(onAll(implyR('Rlike, "p_()&q_()->r_()".asFormula)))
    )
    result.subgoals should have size 1
    result.subgoals.head.ante should contain only ("a=0&b=1".asFormula, "f=5&g=6".asFormula)
    result.subgoals.head.succ should contain only ("c=2".asFormula, "d=3->e=4".asFormula, "h=7".asFormula)
  }

  it should "support 'Llike' unification matching" in {
    val result = proveBy("(a=0&b=1 -> c=2) & (d=3 -> e=4) & (f=5&g=6 -> h=7) -> i=8".asFormula,
      implyR('R) & SaturateTactic(andL('L)) & SaturateTactic(onAll(implyL('Llike, "p_()&q_()->r_()".asFormula)))
    )
    result.subgoals should have size 4
    result.subgoals(0).ante should contain only "d=3->e=4".asFormula
    result.subgoals(0).succ should contain only ("a=0&b=1".asFormula, "f=5&g=6".asFormula, "i=8".asFormula)
    result.subgoals(1).ante should contain only ("d=3->e=4".asFormula, "c=2".asFormula)
    result.subgoals(1).succ should contain only ("f=5&g=6".asFormula, "i=8".asFormula)
    result.subgoals(2).ante should contain only ("d=3->e=4".asFormula, "h=7".asFormula)
    result.subgoals(2).succ should contain only ("a=0&b=1".asFormula, "i=8".asFormula)
    result.subgoals(3).ante should contain only ("d=3->e=4".asFormula, "c=2".asFormula, "h=7".asFormula)
    result.subgoals(3).succ should contain only "i=8".asFormula
  }

  it should "fail inapplicable builtin-rules with legible error messages" in {
    the [BelleThrowable] thrownBy proveBy("x=5".asFormula, andR(1)) should have message
      """[Bellerophon Runtime] Tactic andR applied at Fixed(1,None,true) on a non-matching expression in ElidingProvable(Provable{
        |==> 1:  x=5	Equal
        |  from
        |==> 1:  x=5	Equal})""".stripMargin
  }

  "* combinator" should "prove |- (1=1->1=1) & (2=2->2=2)" in {
    val f = "(1=1->1=1) & (2=2->2=2)".asFormula
    val expr = SaturateTactic(
          OnAll(andR(SuccPos(0))) |
          OnAll(implyR(SuccPos(0)) & close)
        )
  }

  it should "prove x=2&y=3&z=4 |- z=4" in {
    shouldClose(SaturateTactic(andL('_)) &
      assertE("x=2".asFormula, "x=2 not at -1")(-1) & assertE("y=3".asFormula, "y=3 not at -2")(-2) &
      assertE("z=4".asFormula, "z=4 not at -3")(-3) & close,
      Sequent(IndexedSeq("x=2&y=3&z=4".asFormula), IndexedSeq("z=4".asFormula)))
  }

  it should "repeat 0 times if not applicable" in {
    shouldClose(SaturateTactic(andL('_)) & close,
      Sequent(IndexedSeq("x=2".asFormula), IndexedSeq("x=2".asFormula)))
  }

  it should "saturate until no longer applicable" in {
    shouldClose(SaturateTactic(andL('Llast)) &
      assertE("x=2".asFormula, "x=2 not at -1")(-1) & assertE("y=3".asFormula, "y=3 not at -2")(-2) &
      assertE("z=4|z=5".asFormula, "z=4|z=5 not at -3")(-3) & close,
      Sequent(IndexedSeq("x=2&y=3&(z=4|z=5)".asFormula), IndexedSeq("x=2".asFormula)))
  }

  it should "not try right branch when used in combination with either combinator" in {
    val result = proveBy(Sequent(IndexedSeq("x=2&y=3&(z=4|z=5)".asFormula), IndexedSeq("x=2".asFormula)),
      SaturateTactic(SaturateTactic(andL('Llast)) | close))
    result.subgoals should have size 1
    result.subgoals.head.ante should contain only ("x=2".asFormula, "y=3".asFormula, "z=4 | z=5".asFormula)
    result.subgoals.head.succ should contain only "x=2".asFormula
  }

  it should "saturate 'Rlike' unification matching" in {
    val result = proveBy("(a=0&b=1 -> c=2) | (d=3 -> e=4) | (f=5&g=6 -> h=7)".asFormula,
      SaturateTactic(orR('R)) & SaturateTactic(implyR('Rlike, "p_()&q_()->r_()".asFormula))
      )
    result.subgoals should have size 1
    result.subgoals.head.ante should contain only ("a=0&b=1".asFormula, "f=5&g=6".asFormula)
    result.subgoals.head.succ should contain only ("c=2".asFormula, "d=3->e=4".asFormula, "h=7".asFormula)
  }

  it should "trace in the database" in withDatabase { db => withMathematica { _ =>
    val fml = "[x:=3;](x>0 & x>1 & x>2)".asFormula
    val modelContent = s"ProgramVariables Real x; End.\n Problem ${fml.prettyString} End."
    db.createProof(modelContent, "saturateTest")
    db.proveBy(modelContent, SaturateTactic(boxAnd('R) & andR('R)) & master()) shouldBe 'proved
  }}

  "+ combinator" should "saturate with at least 1 repetition" in {
    val result = proveBy(Sequent(IndexedSeq("x=2&y=3&(z=4|z=5)".asFormula), IndexedSeq("x=2".asFormula)),
      andL('Llast) & SaturateTactic(andL('Llast)))
    result.subgoals should have size 1
    result.subgoals.head.ante should contain only ("x=2".asFormula, "y=3".asFormula, "z=4 | z=5".asFormula)
    result.subgoals.head.succ should contain only "x=2".asFormula
  }

  it should "fail when not at least 1 repetition is possible" in {
    a [BelleThrowable] should be thrownBy proveBy(Sequent(IndexedSeq("z=4|z=5".asFormula), IndexedSeq("x=2".asFormula)),
      andL('Llast) & SaturateTactic(andL('Llast)))
  }

  it should "saturate with at least 1 repetition and try right branch in combination with either combinator" in {
    proveBy(Sequent(IndexedSeq("x=2&y=3&(z=4|z=5)".asFormula), IndexedSeq("x=2".asFormula)),
      SaturateTactic((andL('Llast) & SaturateTactic(andL('Llast))) | close)) shouldBe 'proved
  }

  "must idiom" should "fail when no change occurs" in {
    a [BelleThrowable] should be thrownBy proveBy("x=2".asFormula, Idioms.must(skip))
  }

  it should "do nothing when change occurred" in {
    val result = proveBy("x=2|x=3".asFormula, Idioms.must(orR(1)))
    result.subgoals should have size 1
    result.subgoals.head.ante shouldBe empty
    result.subgoals.head.succ should contain only ("x=2".asFormula, "x=3".asFormula)
  }

  "Branch Combinator" should "prove |- (1=1->1=1) & (2=2->2=2)" in {
    val tactic = andR(SuccPos(0)) < (
      implyR(SuccPos(0)) & close,
      implyR(SuccPos(0)) & close
    )
    val v = {
      val f = "(1=1->1=1) & (2=2->2=2)".asFormula
      BelleProvable(ProvableSig.startProof(f))
    }
    val result = theInterpreter.apply(tactic, v)

    result.isInstanceOf[BelleProvable] shouldBe true
    result.asInstanceOf[BelleProvable].p.isProved shouldBe true
  }

  it should "prove |- (1=1->1=1) & (2=2->2=2) with new < combinator" in {
    //@note cannot use < in unit tests without fully qualifying the name, because Matchers also knows < operator
    val tactic = andR(SuccPos(0)) & Idioms.<(
      implyR(SuccPos(0)) & close,
      implyR(SuccPos(0)) & close
      )
    val v = {
      val f = "(1=1->1=1) & (2=2->2=2)".asFormula
      BelleProvable(ProvableSig.startProof(f))
    }
    val result = theInterpreter.apply(tactic, v)

    result.isInstanceOf[BelleProvable] shouldBe true
    result.asInstanceOf[BelleProvable].p shouldBe 'proved
  }

  it should "handle cases were subgoals are added." in {
    val tactic = andR(SuccPos(0)) < (
      andR(SuccPos(0)),
      implyR(SuccPos(0)) & close
    )
    val f = "(2=2 & 3=3) & (1=1->1=1)".asFormula
    shouldResultIn(
      tactic,
      f,
      Seq("==> 2=2".asSequent, "==> 3=3".asSequent)
    )
  }

  it should "fail whenever there's a non-partial tactic that doesn't close its goal." in {
    val tactic = andR(SuccPos(0)) < (
      andR(SuccPos(0)) & DebuggingTactics.done,
      implyR(SuccPos(0)) & close & DebuggingTactics.done
      )
    val f = "(2=2 & 3=3) & (1=1->1=1)".asFormula
    a[BelleThrowable] shouldBe thrownBy(
      theInterpreter.apply(tactic, BelleProvable(ProvableSig.startProof(f)))
    )
  }

  it should "handle cases were subgoals are added -- switch order" in {
    val tactic = andR(SuccPos(0)) < (
      implyR(SuccPos(0)) & close,
      andR(SuccPos(0))
      )
    val f = "(1=1->1=1) & (2=2 & 3=3)".asFormula
    shouldResultIn(
      tactic,
      f,
      Seq("==> 2=2".asSequent, "==> 3=3".asSequent)
    )
  }

  it should "prove |- (1=1->1=1) & (!2=2  | 2=2) with labels out of order" in {
    val tactic = andR(1) & Idioms.<(label("foo"), label("bar")) & Idioms.<(
      (BelleTopLevelLabel("bar"), orR(1) & notR(1) & close),
      (BelleTopLevelLabel("foo"), implyR(1) & close)
    )
    val v = {
      val f = "(1=1->1=1) & (!2=2 | 2=2)".asFormula
      BelleProvable(ProvableSig.startProof(f))
    }
    val result = theInterpreter.apply(tactic, v)

    result.isInstanceOf[BelleProvable] shouldBe true
    result.asInstanceOf[BelleProvable].p shouldBe 'proved
  }

  it should "work with loop labels" in {
    val tactic = implyR(1) & loop("x>1".asFormula)(1)
    val v = BelleProvable(ProvableSig.startProof("x>2 -> [{x:=x+1;}*]x>0".asFormula))
    val result = theInterpreter.apply(tactic, v)

    result.isInstanceOf[BelleProvable] shouldBe true
    val presult = result.asInstanceOf[BelleProvable]
    presult.p.subgoals should have size 3
    presult.label shouldBe Some(BelleLabels.initCase :: BelleLabels.useCase :: BelleLabels.indStep :: Nil)
  }

  it should "not screw up empty labels" in {
    proveBy(
      "((P_() <-> F_()) & (F_() -> (Q_() <-> G_()))) ->(P_() & Q_() <-> F_() & G_())".asFormula, prop) shouldBe 'proved
  }

  it should "finish working branches before failing" in {
    BelleInterpreter.setInterpreter(registerInterpreter(ExhaustiveSequentialInterpreter()))

    var finishedBranches = Seq.empty[Int]
    def logDone(branch: Int) = new DependentTactic("ANON") {
      override def computeExpr(provable: ProvableSig): BelleExpr = {
        finishedBranches = finishedBranches :+ branch
        skip
      }
    }

    a [BelleThrowable] should be thrownBy proveBy("x>0 -> x>5 & x>0 & [?x>1;]x>1".asFormula, implyR(1) & andR(1) <(
      DebuggingTactics.printIndexed("Branch 1") & closeId & /* not reached */ logDone(1),
      andR(1) <(
        DebuggingTactics.printIndexed("Branch 2") & closeId & logDone(2)
        ,
        DebuggingTactics.printIndexed("Branch 3") & testb(1) & prop & logDone(3)
      )
    ))

    finishedBranches should contain theSameElementsAs Seq(2, 3)
  }

  it should "finish working branches before failing with combined error message" in {
    BelleInterpreter.setInterpreter(registerInterpreter(ExhaustiveSequentialInterpreter()))

    var finishedBranches = Seq.empty[Int]
    def logDone(branch: Int) = new DependentTactic("ANON") {
      override def computeExpr(provable: ProvableSig): BelleExpr = {
        finishedBranches = finishedBranches :+ branch
        skip
      }
    }

    the [BelleThrowable] thrownBy proveBy("x>0 -> x>5 & x>2 & [?x>1;]x>1".asFormula, implyR(1) & andR(1) <(
      DebuggingTactics.printIndexed("Branch 1") & closeId & /* not reached */ logDone(1),
      andR(1) <(
        DebuggingTactics.printIndexed("Branch 2") & closeId & /* not reached */ logDone(2)
        ,
        DebuggingTactics.printIndexed("Branch 3") & testb(1) & prop & logDone(3)
      )
    )) should have message
      """[Bellerophon Runtime] Left Message: [Bellerophon Runtime] Unable to create dependent tactic 'id', cause: requirement failed: Expects same formula in antecedent and succedent. Found:
        |   -1:  x>0	Greater
        |==> 1:  x>5	Greater
        |Right Message: [Bellerophon Runtime] Unable to create dependent tactic 'id', cause: requirement failed: Expects same formula in antecedent and succedent. Found:
        |   -1:  x>0	Greater
        |==> 1:  x>2	Greater)""".stripMargin

    finishedBranches should contain theSameElementsAs Seq(3)
  }

  it should "assign labels correctly to remaining open subgoals" in withMathematica { _ =>
    proveBy("x>=0 -> [{x:=x+1; ++ x:=x+2;}*]x>0".asFormula, implyR(1) &
      loop("x>=0".asFormula)(1) <(
        QE,
        QE,
        choiceb(1) & andR(1) <(
          assignb(1) & label("1"),
          assignb(1)
        )
      ), (labels: Option[List[BelleLabel]]) => {
      labels shouldBe Some(
        BelleLabels.useCase.append(BelleLabels.cutShow).append(BelleLabels.QECEX) ::
        BelleSubLabel(BelleLabels.indStep, "1") ::
        BelleLabels.indStep :: Nil)
    })
  }

  "Let" should "fail (but not horribly) when inner proof cannot be started" in {
    val fml = "[{f'=g}][{g'=5}]f>=0".asFormula
    the [BelleThrowable] thrownBy proveBy(fml, BelleParser("let ({`f()=f`}) in (nil)")) should have message
      "[Bellerophon Runtime] Unable to start inner proof in let: edu.cmu.cs.ls.keymaerax.core.FuncOf cannot be cast to edu.cmu.cs.ls.keymaerax.core.Variable"
    val result = proveBy(fml, BelleParser("let ({`f()=f`}) in (nil) | nil"))
    result.subgoals should have size 1
    result.subgoals.head.ante shouldBe empty
    result.subgoals.head.succ should contain only fml
  }

  "Unification" should "work on 1=1->1=1" in {
    val pattern = SequentType("==> p() -> p()".asSequent)
    val e = USubstPatternTactic(Seq((pattern, (x:RenUSubst) => implyR(SuccPos(0)) & close)))
    shouldClose(e, "1=1->1=1".asFormula)
  }

  it should "work when there are non-working patterns" in {
    val pattern1 = SequentType("==> p() -> p()".asSequent)
    val pattern2 = SequentType("==> p() & q()".asSequent)
    val e = USubstPatternTactic(Seq(
      (pattern2, (x:RenUSubst) => error("Should never get here.")),
      (pattern1, (x:RenUSubst) => implyR(SuccPos(0)) & close)
    ))
    shouldClose(e, "1=1->1=1".asFormula)
  }

  it should "work when there are non-working patterns -- flipped order." in {
    val pattern1 = SequentType("==> p() -> p()".asSequent)
    val pattern2 = SequentType("==> p() & q()".asSequent)
    val e = USubstPatternTactic(Seq(
      (pattern1, (x:RenUSubst) => implyR(1) & close),
      (pattern2, (x:RenUSubst) => error("Should never get here."))
    ))
    shouldClose(e, "1=1->1=1".asFormula)
  }

  it should "choose the first applicable unification when there are many options" in {
    val pattern1 = SequentType("==> p() -> p()".asSequent)
    val pattern2 = SequentType("==> p() -> q()".asSequent)
    val e = USubstPatternTactic(Seq(
      (pattern1, (x:RenUSubst) => implyR(1) & close),
      (pattern2, (x:RenUSubst) => error("Should never get here."))
    ))
    shouldClose(e, "1=1->1=1".asFormula)
  }

  it should "choose the first applicable unification when there are many options -- flipped order" in {
    val pattern1 = SequentType("==> p() -> p()".asSequent)
    val pattern2 = SequentType("==> p() -> q()".asSequent)
    val e = USubstPatternTactic(Seq(
      (pattern2, (x:RenUSubst) => error("Should never get here.")),
      (pattern1, (x:RenUSubst) => implyR(1) & close)
    ))
    a[BelleUserGeneratedError] shouldBe thrownBy (shouldClose(e, "1=1->1=1".asFormula))
  }

  "AtSubgoal" should "work" in {
    val t = andR(1) &
      Idioms.atSubgoal(0, implyR(1) & close) &
      Idioms.atSubgoal(0, implyR(1) & close)
    shouldClose(t, "(1=1->1=1) & (2=2->2=2)".asFormula)
  }

  //@todo would need DerivationInfo entry
  //@note: DifferentialTests."ODE" should "prove FM tutorial 4" acts as a smoke test for this
  "ChooseSome" should "record in the database" ignore withDatabase { db => withMathematica { _ =>
    val fml = "x>0 -> [{x:=3;}*]x>0".asFormula
    val modelContent = s"Variables. R x. End.\n Problem. ${fml.prettyString} End."
    db.createProof(modelContent, "chooseSomeTest")

    import TacticFactory._

    val tactic = "chooseSomeTest" by ((pos: Position, seq: Sequent) => { ChooseSome(
        () => ("x>0".asFormula::Nil).iterator,
        (inv: Formula) => loop(inv)(pos)
      ) & Idioms.<(close, close, master())
    })

    db.proveBy(modelContent, tactic(1)) shouldBe 'proved
  }}

  def testTimeoutAlternatives(fml: Formula, t: Seq[BelleExpr], timeout: Long): (ProvableSig, Seq[String]) = {
    val namesToRecord = t.map(_.prettyString)
    val listener = new IOListener {
      val calls: mutable.Buffer[String] = mutable.Buffer[String]()
      override def begin(input: BelleValue, expr: BelleExpr): Unit = {
        val name = expr.prettyString
        if (namesToRecord.contains(name)) calls += name
      }
      override def end(input: BelleValue, expr: BelleExpr, output: Either[BelleValue, BelleThrowable]): Unit = {}
      override def kill(): Unit = {}
    }

    val BelleProvable(result, _) = ExhaustiveSequentialInterpreter(listener::Nil)(
      TimeoutAlternatives(t, timeout),
      BelleProvable(ProvableSig.startProof(fml))
    )
    (result, listener.calls)
  }

  "TimeoutAlternatives" should "succeed sole alternative" in {
    val (result, recorded) = testTimeoutAlternatives("x>=0 -> x>=0".asFormula, prop::Nil, 1000)
    result shouldBe 'proved
    recorded should contain theSameElementsInOrderAs("prop" :: Nil)
  }

  it should "fail on timeout" in {
    val wait: BuiltInTactic = new BuiltInTactic("wait") {
      def result(p: ProvableSig): ProvableSig = { Thread.sleep(5000); p }
    }
    the [BelleThrowable] thrownBy testTimeoutAlternatives("x>=0 -> x>=0".asFormula, wait::Nil, 1000) should have message "[Bellerophon Runtime] Alternative timed out"
  }

  it should "succeed the first succeeding alternative" in {
    val (result, recorded) = testTimeoutAlternatives("x>=0 -> x>=0".asFormula,
      prop::DebuggingTactics.error("Should not be executed")::Nil, 1000)
    result shouldBe 'proved
    recorded should contain theSameElementsInOrderAs("prop" :: Nil)
  }

  it should "try until one succeeds" in {
    val (result, recorded) = testTimeoutAlternatives("x>=0 -> x>=0".asFormula, (implyR(1)&done)::prop::Nil, 1000)
    result shouldBe 'proved
    recorded should contain theSameElementsInOrderAs("(implyR(1)&done())" :: "prop" :: Nil)
  }

  it should "stop trying on timeout" in {
    val wait: BuiltInTactic = new BuiltInTactic("wait") {
      def result(p: ProvableSig): ProvableSig = { Thread.sleep(5000); p }
    }
    the [BelleThrowable] thrownBy testTimeoutAlternatives("x>=0 -> x>=0".asFormula,
      wait::DebuggingTactics.error("Should not be executed")::Nil, 1000) should have message "[Bellerophon Runtime] Alternative timed out"
  }

  "Def tactic" should "define a tactic and apply it later" in {
    val fml = "x>0 -> [x:=2;++x:=x+1;]x>0".asFormula
    val tDef = DefTactic("myAssign", assignb('R))
    val tactic = tDef & implyR(1) & choiceb(1) & andR(1) & OnAll(ApplyDefTactic(tDef))
    val result = proveBy(fml, tactic)
    result.subgoals should have size 2
    result.subgoals.head.ante should contain only "x>0".asFormula
    result.subgoals.head.succ should contain only "2>0".asFormula
    result.subgoals.last.ante should contain only "x>0".asFormula
    result.subgoals.last.succ should contain only "x+1>0".asFormula
  }

  "Def expression" should "define a function and expand it later" ignore {
    val fml = "x>0 -> [{x:=x+1;}*]x>0".asFormula
    val invDef = DefExpression("inv(x)=x".asFormula)
    val tactic = invDef & implyR(1) & loop("inv(x)>0".asFormula)(1)
    val result = proveBy(fml, tactic)
    result.subgoals should have size 3
    result.subgoals(0).ante should contain only "x>0".asFormula
    result.subgoals(0).succ should contain only "inv(x)>0".asFormula
    result.subgoals(1).ante should contain only "inv(x)>0".asFormula
    result.subgoals(1).succ should contain only "x>0".asFormula
    result.subgoals(2).ante should contain only "inv(x)>0".asFormula
    result.subgoals(2).succ should contain only "[x:=x+1;]inv(x)>0".asFormula
    val expanded = proveBy(result, ExpandDef(invDef))
    expanded.subgoals should have size 3
    expanded.subgoals(0).ante should contain only "x>0".asFormula
    expanded.subgoals(0).succ should contain only "x>0".asFormula
    expanded.subgoals(1).ante should contain only "x>0".asFormula
    expanded.subgoals(1).succ should contain only "x>0".asFormula
    expanded.subgoals(2).ante should contain only "x>0".asFormula
    expanded.subgoals(2).succ should contain only "[x:=x+1;]x>0".asFormula
  }

  it should "expand right-away" in {
    val fml = "x>0 -> f(x)>0".asFormula
    val invDef = DefExpression("f(x)=x".asFormula)
    val result = proveBy(Sequent(IndexedSeq(), IndexedSeq(fml)), invDef)
    result.subgoals should have size 1
    result.subgoals.head.ante shouldBe empty
    result.subgoals.head.succ should contain only "x>0 -> x>0".asFormula
  }

  it should "combine with let to postpone expanding" in {
    import TacticFactory._
    val fml = "x>0 -> f(x)>0".asFormula
    val invDef = DefExpression("f(x)=x+1".asFormula)
    val result = proveBy(Sequent(IndexedSeq(), IndexedSeq(fml)), invDef & Let("f(x)".asTerm, "x+1".asTerm,
      "ANON" by ((s: Sequent) => {
        s.ante shouldBe empty
        s.succ should contain only "x>0 -> f(x)>0".asFormula
        nil
      })))
    result.subgoals should have size 1
    result.subgoals.head.ante shouldBe empty
    result.subgoals.head.succ should contain only "x>0 -> x+1>0".asFormula
  }

  it should "define a predicate and expand it later" ignore {
    val fml = "x>0 -> [{x:=x+1;}*]x>0".asFormula
    val invDef = DefExpression("inv(x) <-> x>0".asFormula)
    val tactic = invDef & implyR(1) & loop("inv(x)".asFormula)(1)
    val result = proveBy(fml, tactic)
    result.subgoals should have size 3
    result.subgoals(0).ante should contain only "x>0".asFormula
    result.subgoals(0).succ should contain only "inv(x)".asFormula
    result.subgoals(1).ante should contain only "inv(x)".asFormula
    result.subgoals(1).succ should contain only "x>0".asFormula
    result.subgoals(2).ante should contain only "inv(x)".asFormula
    result.subgoals(2).succ should contain only "[x:=x+1;]inv(x)".asFormula
    val expanded = proveBy(result, ExpandDef(invDef))
    expanded.subgoals should have size 3
    expanded.subgoals(0).ante should contain only "x>0".asFormula
    expanded.subgoals(0).succ should contain only "x>0".asFormula
    expanded.subgoals(1).ante should contain only "x>0".asFormula
    expanded.subgoals(1).succ should contain only "x>0".asFormula
    expanded.subgoals(2).ante should contain only "x>0".asFormula
    expanded.subgoals(2).succ should contain only "[x:=x+1;]x>0".asFormula
  }

//  "Scheduled tactics" should "work" in {
//    val legacyTactic = tactics.TacticLibrary.arithmeticT
//    val t = Legacy.initializedScheduledTactic(DefaultConfiguration.defaultMathematicaConfig, legacyTactic)
//    shouldClose(t, "1=1".asFormula)
//  }
//
//  it should "work again" in {
//    val legacyTactic = tactics.TacticLibrary.arithmeticT
//    val t = Legacy.initializedScheduledTactic(DefaultConfiguration.defaultMathematicaConfig, legacyTactic)
//    shouldClose(t, "x = 0 -> x^2 = 0".asFormula)
//  }
//
//
//  it should "work for non-arith things" in {
//    val legacyTactic = tactics.PropositionalTacticsImpl.AndRightT(SuccPos(0))
//    val t = Legacy.initializedScheduledTactic(DefaultConfiguration.defaultMathematicaConfig, legacyTactic) < (
//      implyR(SuccPos(0)) & close,
//      implyR(SuccPos(0)) & close
//      )
//    shouldClose(t, "(1=1->1=1) & (1=2->1=2)".asFormula)
//  }

  /*"A failing tactic"*/
  it should "print nice errors and provide a stack trace" ignore {
    val itFails = new BuiltInTactic("fails") {
      override def result(provable: ProvableSig) = throw new ProverException("Fails...")
    }

    val conj = Idioms.nil & (itFails | itFails)

    // now try with defs
    def repTwo = conj*2

    def split = andR(1) <(
        repTwo,
        skip)

    val thrown = intercept[Throwable] {
      theInterpreter.apply(Idioms.nil
        & Idioms.nil
        & split
        & Idioms.nil
        & Idioms.nil, BelleProvable(ProvableSig.startProof("1=1 & 2=2".asFormula)))
    }
    thrown.printStackTrace()
    thrown.getMessage should include ("Fails...")
    val s = thrown.getCause.getStackTrace
    //@todo works in isolation, but when run with other tests, we pick up stack trace elements of those too
    s.filter(_.getFileName == "SequentialInterpreterTests.scala").slice(0, 11).map(_.getLineNumber) shouldBe Array(280, 279, 278, 271, 271, 269, 266, 266, 263, 262, 276)
  }

  "Lazy interpreter" should "fail on the first failing branch" in {
    val listener = new IOListener {
      val calls: mutable.Buffer[BelleExpr] = mutable.Buffer[BelleExpr]()
      override def begin(input: BelleValue, expr: BelleExpr): Unit = calls += expr
      override def end(input: BelleValue, expr: BelleExpr, output: Either[BelleValue, BelleThrowable]): Unit = {}
      override def kill(): Unit = {}
    }
    the [BelleThrowable] thrownBy LazySequentialInterpreter(listener::Nil)(
      "andR(1) & <(close, close)".asTactic,
      BelleProvable(ProvableSig.startProof("false & true".asFormula))) should have message
        """[Bellerophon Runtime] [Bellerophon User-Generated Message] Inapplicable close
          |The error occurred on
          |Provable{
          |==> 1:  false	False$
          |  from
          |==> 1:  false	False$}""".stripMargin

    listener.calls should have size 5
    listener.calls should contain theSameElementsInOrderAs(
      "andR(1) & <(close, close)".asTactic :: "andR(1)".asTactic ::
      BranchTactic("close".asTactic :: "close".asTactic :: Nil) :: "close".asTactic ::
      DebuggingTactics.error("Inapplicable close") :: Nil)
  }

  "Exhaustive interpreter" should "explore all branches regardless of failing ones" in {
    val listener = new IOListener {
      val calls: mutable.Buffer[BelleExpr] = mutable.Buffer[BelleExpr]()
      override def begin(input: BelleValue, expr: BelleExpr): Unit = calls += expr
      override def end(input: BelleValue, expr: BelleExpr, output: Either[BelleValue, BelleThrowable]): Unit = {}
      override def kill(): Unit = {}
    }
    the [BelleThrowable] thrownBy ExhaustiveSequentialInterpreter(listener::Nil)(
      "andR(1) & <(close, close)".asTactic,
      BelleProvable(ProvableSig.startProof("false & true".asFormula))) should have message
        """[Bellerophon Runtime] [Bellerophon User-Generated Message] Inapplicable close
          |The error occurred on
          |Provable{
          |==> 1:  false	False$
          |  from
          |==> 1:  false	False$}""".stripMargin

    listener.calls should have size 7
    listener.calls should contain theSameElementsInOrderAs(
      "andR(1) & <(close, close)".asTactic :: "andR(1)".asTactic ::
      BranchTactic("close".asTactic :: "close".asTactic :: Nil) :: "close".asTactic ::
      DebuggingTactics.error("Inapplicable close") :: "close".asTactic :: ProofRuleTactics.closeTrue(1) :: Nil)
  }

  it should "confirm that interpreter debug information slows down search" taggedAs SlowTest in {
    val ante = (1 to 100).map(i => Equal(Variable("x", Some(i)), Number(1))).reduce(And)
    val succ = (1 to 50).map(i => Box(Assign(Variable("y", Some(i)), Number(2)), Greater(Variable("y", Some(i)), Number(1)))).reduce(Or)

    // should take about 1min
    failAfter(2 minutes) {
      val BelleProvable(result, _) = ExhaustiveSequentialInterpreter(Nil, throwWithDebugInfo = true)(
        SaturateTactic(implyR('R) | andL('L) | orR('R) | assignb('R)),
        BelleProvable(ProvableSig.startProof(Imply(ante, succ)))
      )
      result.subgoals.head.ante should have size 100
      result.subgoals.head.succ should have size 50
      result.subgoals.head.succ.foreach(_ shouldBe a [Greater])
    }
  }

  it should "not spend extensive time searching positions without debug information" in {
    val ante = (1 to 100).map(i => Equal(Variable("x", Some(i)), Number(1))).reduce(And)
    val succ = (1 to 50).map(i => Box(Assign(Variable("y", Some(i)), Number(2)), Greater(Variable("y", Some(i)), Number(1)))).reduce(Or)

    // should take about 500ms
    failAfter(2 seconds) {
      val BelleProvable(result, _) = ExhaustiveSequentialInterpreter(Nil)(
        SaturateTactic(implyR('R) | andL('L) | orR('R) | assignb('R)),
        BelleProvable(ProvableSig.startProof(Imply(ante, succ)))
      )
      result.subgoals.head.ante should have size 100
      result.subgoals.head.succ should have size 50
      result.subgoals.head.succ.foreach(_ shouldBe a [Greater])
    }
  }

  it should "stop saturation when proof is closed" in {
    val listener = new IOListener {
      val calls: mutable.Buffer[BelleExpr] = mutable.Buffer[BelleExpr]()
      override def begin(input: BelleValue, expr: BelleExpr): Unit = expr match {
        case NamedTactic(name, _) if name == "prop" => calls += expr
        case _ =>
      }
      override def end(input: BelleValue, expr: BelleExpr, output: Either[BelleValue, BelleThrowable]): Unit = {}
      override def kill(): Unit = {}
    }
    ExhaustiveSequentialInterpreter(listener :: Nil)(
      SaturateTactic(prop),
      BelleProvable(ProvableSig.startProof("x>0 -> x>0".asFormula))
    ) match {
      case BelleProvable(pr, _) => pr shouldBe 'proved
    }
    listener.calls should have size 1
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // Helper methods
  //////////////////////////////////////////////////////////////////////////////////////////////////

  private def shouldClose(expr: BelleExpr, f: Formula): Unit = shouldClose(expr, Sequent(IndexedSeq(), IndexedSeq(f)))

  private def shouldClose(expr: BelleExpr, sequent: Sequent): Unit = {
    val v = BelleProvable(ProvableSig.startProof(sequent))
    val result = theInterpreter.apply(expr, v)
    result shouldBe a[BelleProvable]
    result.asInstanceOf[BelleProvable].p shouldBe 'proved
  }

  private def shouldResultIn(expr: BelleExpr, f: Formula, expectedResult : Seq[Sequent]) = {
    val v = BelleProvable(ProvableSig.startProof(f))
    val result = theInterpreter.apply(expr, v)
    result shouldBe a[BelleProvable]
    result.asInstanceOf[BelleProvable].p.subgoals shouldBe expectedResult
  }
}

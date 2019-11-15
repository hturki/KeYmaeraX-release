/**
* Copyright (c) Carnegie Mellon University.
* See LICENSE.txt for the conditions of this license.
*/
/**
 * HyDRA API Responses
 *  @author Nathan Fulton
 *  @author Stefan Mitsch
 *  @author Ran Ji
 */
package edu.cmu.cs.ls.keymaerax.hydra

import edu.cmu.cs.ls.keymaerax.btactics._
import edu.cmu.cs.ls.keymaerax.btactics.Augmentors._
import edu.cmu.cs.ls.keymaerax.core.{Expression, Formula}
import edu.cmu.cs.ls.keymaerax.bellerophon._
import edu.cmu.cs.ls.keymaerax.core._
import edu.cmu.cs.ls.keymaerax.parser._
import spray.json._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import java.io.{PrintWriter, StringWriter}

import Helpers._
import edu.cmu.cs.ls.keymaerax.bellerophon.parser.{BelleParser, BellePrettyPrinter}
import edu.cmu.cs.ls.keymaerax.launcher.ToolConfiguration
import edu.cmu.cs.ls.keymaerax.pt.ProvableSig
import org.apache.logging.log4j.scala.Logging

import scala.collection.mutable.ListBuffer
import scala.collection.immutable
import scala.collection.immutable.Seq
import scala.util.Try
import scala.util.matching.Regex.Match
import scala.xml.Elem


/**
 * Responses are like views -- they shouldn't do anything except produce appropriately
 * formatted JSON from their parameters.
 *
 * To create a new response:
 * <ol>
 *   <li>Create a new object extending Response (perhaps with constructor arguments)</li>
 *   <li>override the json value with the json to be generated.</li>
 *   <li>override the schema value with Some(File(...)) containing the schema.</li>
 * </ol>
 *
 * See BooleanResponse for the simplest example.
 */
sealed trait Response {
  /**
   * Should be the name of a single file within resources/js/schema.
   */
  val schema: Option[String] = None

  /** Returns the response data in JSON format (unsupported by HtmlResponse). */
  def getJson: JsValue

  /** Returns the printed marshallable response. */
  def print: ToResponseMarshallable = getJson.compactPrint
}

/** Responds with a dynamically generated (server-side) HTML page. */
case class HtmlResponse(html: Elem) extends Response {
  override def getJson: JsValue = throw new UnsupportedOperationException("HTML response is no JSON data")
  override def print: ToResponseMarshallable = html
}

case class BooleanResponse(flag : Boolean, errorText: Option[String] = None) extends Response {
  override val schema = Some("BooleanResponse.js")

  def getJson: JsObject = errorText match {
    case Some(s) =>
      JsObject(
        "success" -> (if(flag) JsTrue else JsFalse),
        "type" -> JsNull,
        "errorText" -> JsString(s)
      )
    case None =>
      JsObject(
        "success" -> (if(flag) JsTrue else JsFalse),
        "type" -> JsNull
      )
  }
}

class PlainResponse(data: (String, JsValue)*) extends Response {
  override def getJson = JsObject(data:_*)
}

class ModelListResponse(models: List[ModelPOJO]) extends Response {
  val objects: List[JsObject] = models.map(modelpojo => JsObject(
    "id" -> JsString(modelpojo.modelId.toString),
    "name" -> JsString(modelpojo.name),
    "date" -> JsString(modelpojo.date),
    "description" -> JsString(modelpojo.description),
    "pubLink" -> JsString(modelpojo.pubLink),
    "keyFile" -> JsString(modelpojo.keyFile),
    "title" -> JsString(modelpojo.title),
    "hasTactic" -> JsBoolean(modelpojo.tactic.isDefined),
    "numAllProofSteps" -> JsNumber(modelpojo.numAllProofSteps),
    "isExercise" -> JsBoolean(KeYmaeraXArchiveParser.isExercise(modelpojo.keyFile))
  ))

  def getJson = JsArray(objects:_*)
}

case class ModelUploadResponse(modelId: Option[String], errorText: Option[String]) extends Response {
  def getJson = JsObject(
    "success" -> JsBoolean(modelId.isDefined),
    "errorText"->JsString(errorText.getOrElse("")),
    "modelId"->JsString(modelId.getOrElse("")))
}

class UpdateProofNameResponse(proofId: String, newName: String) extends Response {
  def getJson = JsArray()
}

/**
 *
 * @param proofs The list of proofs with their status in KeYmaera (proof, loadStatus).
 */
class ProofListResponse(proofs: List[(ProofPOJO, String)]) extends Response {
  override val schema = Some("prooflist.js")

  val objects : List[JsObject] = proofs.map({case (proof, loadStatus) => JsObject(
    "id" -> JsString(proof.proofId.toString),
    "name" -> JsString(proof.name),
    "description" -> JsString(proof.description),
    "date" -> JsString(proof.date),
    "modelId" -> JsString(proof.modelId.toString),
    "stepCount" -> JsNumber(proof.stepCount),
    "status" -> JsBoolean(proof.closed),
    "loadStatus" -> JsString(loadStatus)
  )})

  def getJson = JsArray(objects:_*)
}

class UserLemmasResponse(proofs: List[(ProofPOJO, Option[ModelPOJO])]) extends Response {
  def problemContent(s: String): String = {
    val i = s.indexOf("Problem")
    val j = s.indexOf("End.", i)
    s.substring(i + "Problem".length, j).trim()
  }

  lazy val objects : List[JsObject] = proofs.map({case (proof, model) => JsObject(
    "id" -> JsString(proof.proofId.toString),
    "name" -> (if (model.isDefined) JsString(model.get.name) else JsNull),
    "conclusion" -> (if (model.isDefined) JsString(problemContent(model.get.keyFile)) else JsNull)
  )})

  def getJson = JsArray(objects:_*)
}

class GetModelResponse(model: ModelPOJO) extends Response {

  private def illustrationLinks(): List[String] = {
    KeYmaeraXArchiveParser(model.keyFile).flatMap(_.info.get("Illustration"))
  }

  def getJson = JsObject(
    "id" -> JsString(model.modelId.toString),
    "name" -> JsString(model.name),
    "date" -> JsString(model.date),
    "description" -> JsString(model.description),
    "illustrations" -> JsArray(illustrationLinks().map(JsString(_)).toVector),
    "pubLink" -> JsString(model.pubLink),
    "keyFile" -> JsString(model.keyFile),
    "title" -> JsString(model.title),
    "hasTactic" -> JsBoolean(model.tactic.isDefined),
    "tactic" -> JsString(model.tactic.getOrElse("")),
    "numAllProofSteps" -> JsNumber(model.numAllProofSteps),
    "isExercise" -> JsBoolean(KeYmaeraXArchiveParser.isExercise(model.keyFile))
  )
}

class GetModelTacticResponse(model: ModelPOJO) extends Response {
  def getJson = JsObject(
    "modelId" -> JsString(model.modelId.toString),
    "modelName" -> JsString(model.name),
    "tacticBody" -> JsString(model.tactic.getOrElse(""))
  )
}

class ModelPlexMandatoryVarsResponse(model: ModelPOJO, vars: Set[Variable]) extends Response {
  def getJson = JsObject(
    "modelid" -> JsString(model.modelId.toString),
    "mandatoryVars" -> JsArray(vars.map(v => JsString(v.prettyString)).toVector)
  )
}

class ModelPlexArtifactResponse(model: ModelPOJO, artifact: Expression) extends Response {
  val fmlHtml = JsString(UIKeYmaeraXPrettyPrinter("", plainText=false)(artifact))
  val fmlString = JsString(UIKeYmaeraXPrettyPrinter("", plainText=true)(artifact))
  val fmlPlainString = JsString(artifact.prettyString)

  def getJson = JsObject(
    "modelid" -> JsString(model.modelId.toString),
    "generatedArtifact" -> JsObject(
      "html" -> fmlHtml,
      "string" -> fmlString,
      "plainString" -> fmlPlainString
    )
  )
}

class TestSynthesisResponse(model: ModelPOJO, metric: Formula,
                           //@todo class: List[(SeriesName, List[(Var->Val, SafetyMargin, Variance)])]
                            testCases: List[(String, List[(Map[Term, Term], Option[Number], Number)])]) extends Response {
  private val fmlHtml = JsString(UIKeYmaeraXPrettyPrinter("", plainText=false)(metric))
  private val fmlString = JsString(UIKeYmaeraXPrettyPrinter("", plainText=true)(metric))
  private val fmlPlainString = JsString(KeYmaeraXPrettyPrinter(metric))

  private val minRadius = 5  // minimum radius of bubbles even when all pre are equal to their post
  private val maxRadius = 30 // maximum radius of bubbles even when wildly different values

  private val Number(maxVariance) = testCases.flatMap(_._2).maxBy(_._3.value)._3

  private def radius(n: BigDecimal): BigDecimal =
    if (maxVariance > 0) minRadius + (maxRadius-minRadius)*(n/maxVariance)
    else minRadius

  private def scatterData(tc: List[(Map[Term, Term], Option[Number], Number)]) = JsArray(tc.zipWithIndex.map(
      { case ((_, safetyMargin, Number(variance)), idx) => JsObject(
    "x" -> JsNumber(idx),
    "y" -> (safetyMargin match { case Some(Number(sm)) => JsNumber(sm) case None => JsNumber(-1) }),
    "r" -> JsNumber(radius(variance))
  ) }):_*)

  // pre/post/labels/series
  private def prePostVals(vals: Map[Term, Term]): (JsArray, JsArray, JsArray, JsArray) = {
    val (pre, post) = vals.partition({ case (v, _) => v.isInstanceOf[BaseVariable] })
    val preByPost: Map[Term, Term] = post.map({
      case (post@FuncOf(Function(name, idx, Unit, Real, _), _), _) if name.endsWith("post") =>
        post -> Variable(name.substring(0, name.length-"post".length), idx)
      case (v, _) => v -> v
    })
    val preJson = pre.map({ case (v, Number(value)) => JsObject("v" -> JsString(v.prettyString), "val" -> JsNumber(value)) })
    val postJson = post.map({ case (v, Number(value)) => JsObject("v" -> JsString(preByPost(v).prettyString), "val" -> JsNumber(value)) })
    val sortedKeys = pre.keys.toList.sortBy(_.prettyString)
    val labels = sortedKeys.map(v => JsString(v.prettyString))
    val preSeries = sortedKeys.map(k => pre.get(k) match { case Some(Number(v)) => JsNumber(v) })
    val postSeries = sortedKeys.map({ case k@BaseVariable(n, i, _) =>
      post.get(FuncOf(Function(n + "post", i, Unit, Real), Nothing)) match {
        case Some(Number(v)) => JsNumber(v)
        case None => pre.get(k) match { case Some(Number(v)) => JsNumber(v) } //@note constants
      }
    })
    (JsArray(preJson.toVector), JsArray(postJson.toVector), JsArray(labels.toVector),
      JsArray(JsArray(preSeries.toVector), JsArray(postSeries.toVector)))
  }

  private def seriesData(data: List[(Map[Term, Term], Option[Number], Number)]): JsArray = JsArray(data.zipWithIndex.map({
    case ((vals: Map[Term, Term], safetyMargin, Number(variance)), idx) =>
      val (preVals, postVals, labels, series) = prePostVals(vals)
      JsObject(
        "name" -> JsString(""+idx),
        "safetyMargin" -> (safetyMargin match { case Some(Number(sm)) => JsNumber(sm) case None => JsNumber(-1) }),
        "variance" -> JsNumber(variance),
        "pre" -> preVals,
        "post" -> postVals,
        "labels" -> labels,
        "seriesData" -> series
      )
  }):_*)

  def getJson = JsObject(
    "modelid" -> JsString(model.modelId.toString),
    "metric" -> JsObject(
      "html" -> fmlHtml,
      "string" -> fmlString,
      "plainString" -> fmlPlainString
    ),
    "plot" -> JsObject(
      "data" -> JsArray(testCases.map({ case (_, tc) => scatterData(tc) }):_*),
      "series" -> JsArray(testCases.map({ case (name, _) => JsString(name) }):_*),
      "labels" -> JsArray(JsString("Case"), JsString("Safety Margin"), JsString("Variance"))
    ),
    "caseInfos" -> JsArray(testCases.map({ case (name, data) => JsObject("series" -> JsString(name), "data" -> seriesData(data)) }):_*)
  )
}

class ModelPlexArtifactCodeResponse(model: ModelPOJO, code: String) extends Response {
  def getJson = JsObject(
    "modelid" -> JsString(model.modelId.toString),
    "modelname" -> JsString(model.name),
    "code" -> JsString(code)
  )
}

class LoginResponse(flag: Boolean, user: UserPOJO, sessionToken: Option[String]) extends Response {
  def getJson = JsObject(
    "success" -> (if (flag) JsTrue else JsFalse),
    "sessionToken" -> (if (flag && sessionToken.isDefined) JsString(sessionToken.get) else JsFalse),
    "key" -> JsString("userId"),
    "value" -> JsString(user.userName.replaceAllLiterally("/", "%2F").replaceAllLiterally(":", "%3A")),
    "userAuthLevel" -> JsNumber(user.level),
    "type" -> JsString("LoginResponse")
  )
}

class CreatedIdResponse(id: String) extends Response {
  def getJson = JsObject("id" -> JsString(id))
}

class PossibleAttackResponse(val msg: String) extends Response with Logging {
  logger.fatal(s"POSSIBLE ATTACK: $msg")
  override def getJson: JsValue = new ErrorResponse(msg).getJson
}

class ErrorResponse(val msg: String, val exn: Throwable = null) extends Response {
  private lazy val writer = new StringWriter
  private lazy val stacktrace =
    if (exn != null) {
      exn.printStackTrace(new PrintWriter(writer))
      writer.toString
        .replaceAll("[\\t]at spray\\.routing\\..*", "")
        .replaceAll("[\\t]at java\\.util\\.concurrent\\..*", "")
        .replaceAll("[\\t]at java\\.lang\\.Thread\\.run.*", "")
        .replaceAll("[\\t]at scala\\.Predef\\$\\.require.*", "")
        .replaceAll("[\\t]at akka\\.spray\\.UnregisteredActorRefBase.*", "")
        .replaceAll("[\\t]at akka\\.dispatch\\..*", "")
        .replaceAll("[\\t]at scala\\.concurrent\\.forkjoin\\..*", "")
        .replaceAll("[\\t]at scala\\.runtime\\.AbstractPartialFunction.*", "")
        .replaceAll("\\s+$|\\s*(\n)\\s*|(\\s)\\s*", "$1$2") //@note collapse newlines
    } else ""
  def getJson = JsObject(
    "textStatus" -> (if (msg != null) JsString(msg.replaceAllLiterally("[Bellerophon Runtime]", "")) else JsString("")),
    "causeMsg" -> (if (exn != null && exn.getMessage != null) JsString(exn.getMessage.replaceAllLiterally("[Bellerophon Runtime", "")) else JsString("")),
    "errorThrown" -> JsString(stacktrace),
    "type" -> JsString("error")
  )
}

class KvpResponse(val key: String, val value: String) extends Response {
  override def getJson: JsValue = JsObject(key -> JsString(value))
}

case class ParseErrorResponse(override val msg: String, expect: String, found: String, detailedMsg: String,
                         loc: Location, override val exn: Throwable = null) extends ErrorResponse(msg, exn) {
  override def getJson = JsObject(super.getJson.fields ++ Map(
    "details" -> JsObject(
      "expect" -> JsString(expect),
      "found" -> JsString(found),
      "detailedMsg" -> JsString(detailedMsg)
    ),
    "location" -> JsObject(
      "line" -> JsNumber(loc.line),
      "column" -> JsNumber(loc.column)
    )
  ))
}

class TacticErrorResponse(msg: String, tacticMsg: String, exn: Throwable = null)
    extends ErrorResponse(msg, exn) {
  override def getJson: JsObject = exn match {
    case ex: BelleUnexpectedProofStateError =>
      JsObject(super.getJson.fields ++ Map(
        "tacticMsg" -> JsString(tacticMsg.replaceAllLiterally("[Bellerophon Runtime]", ""))
      ))
    case ex: CompoundException =>
      val exceptions = flatten(ex)
      val messages = exceptions.size + " tactic attempts failed:\n" + exceptions.zipWithIndex.map({
        case (x: BelleUnexpectedProofStateError, i) =>
          (i+1) + ". " + x.getMessage.replaceAllLiterally("[Bellerophon Runtime]", "") +
            ":\n" + x.proofState.subgoals.map(_.toString).mkString(",")
        case (x, i) => (i+1) + ". " + x.getMessage.replaceAllLiterally("[Bellerophon Runtime]", "")
      }).mkString("\n") + "\n"
      JsObject(super.getJson.fields.filter(_._1 != "textStatus") ++ Map(
        "textStatus" -> JsString(messages),
        "tacticMsg" -> JsString(tacticMsg)
      ))
    case _ =>
      JsObject(super.getJson.fields ++ Map(
        "tacticMsg" -> JsString(tacticMsg.replaceAllLiterally("[Bellerophon Runtime]", ""))
      ))
  }

  private def flatten(ex: BelleThrowable): List[BelleThrowable] = ex match {
    case ex: CompoundException => flatten(ex.left) ++ flatten(ex.right)
    case _ => ex :: Nil
  }
}

class ToolConfigErrorResponse(tool: String, msg: String) extends ErrorResponse(msg, null) {
  override def getJson: JsObject = JsObject(super.getJson.fields ++ Map("tool" -> JsString(tool)))
}

class GenericOKResponse() extends Response {
  def getJson = JsObject(
    "success" -> JsTrue
  )
}

class UnimplementedResponse(callUrl: String) extends ErrorResponse("Call unimplemented: " + callUrl) {}

class ProofStatusResponse(proofId: String, status: String, error: Option[String] = None) extends Response {
  override val schema = Some("proofstatus.js")
  def getJson = JsObject(
    "proofId" -> JsString(proofId),
    "type" -> JsString("ProofLoadStatus"),
    "status" -> JsString(status),
    "textStatus" -> JsString(status + ": " + proofId),
    "errorThrown" -> JsString(error.getOrElse(""))
  )
}
class ProofIsLoadingResponse(proofId: String) extends ProofStatusResponse(proofId, "loading")
class ProofNotLoadedResponse(proofId: String) extends ProofStatusResponse(proofId, "notloaded")
class ProofIsLoadedResponse(proofId: String) extends ProofStatusResponse(proofId, "loaded")
// progress "open": open goals
// progress "closed": no open goals but not checked for isProved
class ProofProgressResponse(proofId: String, isClosed: Boolean)
  extends ProofStatusResponse(proofId, if (isClosed) "closed" else "open")

class ProofVerificationResponse(proofId: String, provable: ProvableSig, tactic: String) extends Response {
  override def getJson = JsObject(
    "proofId" -> JsString(proofId),
    "isProved" -> JsBoolean(provable.isProved),
    "provable" -> JsString(provable.underlyingProvable.toString),
    "tactic" -> JsString(tactic))
}

class GetProblemResponse(proofid: String, tree: String) extends Response {
  def getJson = JsObject(
    "proofid" -> JsString(proofid),
    "proofTree" -> JsonParser(tree)
  )
}

case class RunBelleTermResponse(proofId: String, nodeId: String, taskId: String, info: String) extends Response {
  def getJson = JsObject(
    "proofId" -> JsString(proofId),
    "nodeId" -> JsString(nodeId),
    "taskId" -> JsString(taskId),
    "type" -> JsString("runbelleterm"),
    "info" -> JsString(info)
  )
}

case class TaskStatusResponse(proofId: String, nodeId: String, taskId: String, status: String,
                              progress: Option[(Option[(BelleExpr, Long)], Seq[(BelleExpr, Either[BelleValue, BelleThrowable])])]) extends Response {
  def getJson: JsValue = {
    JsObject(
      "proofId" -> JsString(proofId),
      "parentId" -> JsString(nodeId),
      "taskId" -> JsString(taskId),
      "status" -> JsString(status),
      "type" -> JsString("taskstatus"),
      "currentStep" -> progress.map(p => JsObject(
        "ruleName" -> p._1.map(c => JsString(c._1.prettyString)).getOrElse(JsNull),
        "duration" -> p._1.map(c => JsNumber(c._2)).getOrElse(JsNull),
        "stepStatus" -> JsNull
      )).getOrElse(JsNull),
      "progress" -> progress.map(p => JsArray(
        p._2.map(e => JsString(e._1.prettyString)):_*
      )).getOrElse(JsArray())
    )
  }

}

case class TaskResultResponse(proofId: String, parent: ProofTreeNode, marginLeft: Int, marginRight: Int, progress: Boolean = true) extends Response {
  private lazy val openChildren = parent.children.filter(_.numSubgoals > 0)

  def getJson = JsObject(
    "proofId" -> JsString(proofId),
    "parent" -> JsObject(
      "id" -> JsString(parent.id.toString),
      "children" -> JsArray(openChildren.map(c => JsString(c.id.toString)):_*)
    ),
    "newNodes" -> JsArray(nodesJson(openChildren, marginLeft, marginRight).map(_._2):_*),
    "progress" -> JsBoolean(progress),
    "type" -> JsString("taskresult")
  )
}

case class NodeChildrenResponse(proofId: String, parent: ProofTreeNode, marginLeft: Int, marginRight: Int) extends Response {
  def getJson = JsObject(
    "proofId" -> JsString(proofId),
    "parent" -> JsObject(
      "id" -> JsString(parent.id.toString),
      "children" -> JsArray(parent.children.map(c => JsString(c.id.toString)):_*)
    ),
    "newNodes" -> JsArray(nodesJson(parent.children, marginLeft, marginRight).map(_._2):_*),
    "progress" -> JsBoolean(true)
  )
}

case class ProofNodeSequentResponse(proofId: String, node: ProofTreeNode, marginLeft: Int, marginRight: Int) extends Response {
  def getJson = JsObject(
    "proofId" -> JsString(proofId),
    "nodeId" -> JsString(node.id.toString),
    "sequent" -> (node.goal match { case None => JsNull case Some(goal) => sequentJson(goal, marginLeft, marginRight) })
  )
}

class UpdateResponse(update: String) extends Response {
  def getJson = JsObject(
    "type" -> JsString("update"),
    "events" -> JsonParser(update)
  )
}

class ProofTreeResponse(tree: String) extends Response {
  def getJson = JsObject(
    "type" -> JsString("proof"),
    "tree" -> JsonParser(tree)
  )
}

class OpenProofResponse(proof: ProofPOJO, loadStatus: String) extends Response {
  override val schema = Some("proof.js")
  def getJson = JsObject(
    "id" -> JsString(proof.proofId.toString),
    "name" -> JsString(proof.name),
    "description" -> JsString(proof.description),
    "date" -> JsString(proof.date),
    "modelId" -> JsString(proof.modelId.toString),
    "stepCount" -> JsNumber(proof.stepCount),
    "status" -> JsBoolean(proof.closed),
    "tactic" -> (proof.tactic match { case None => JsNull case Some(t) => JsString(t) }),
    "loadStatus" -> JsString(loadStatus)
  )
}

class ProofAgendaResponse(tasks: List[(ProofPOJO, List[Int], String)]) extends Response {
  override val schema = Some("proofagenda.js")
  val objects: List[JsObject] = tasks.map({ case (proofPojo, nodeId, nodeJson) => JsObject(
    "proofId" -> JsString(proofPojo.proofId.toString),
    "nodeId" -> Helpers.nodeIdJson(nodeId),
    "proofNode" -> JsonParser(nodeJson)
  )})

  def getJson = JsArray(objects:_*)
}

/** JSON conversions for frequently-used response formats */
object Helpers {
  trait FormatProvider {
    /** Prints whitespace and checks that the remaining format string starts with `check` (literally). Advances the format string past `check`. */
    def printWS(check: String = ""): String
    /** Prints whitespace prefix and formats `next` according to the format string. */
    def print(next: String): String
  }

  /** Stateful format provider to read off whitespace and line breaks from a pretty-printed string. */
  case class PrettyPrintFormatProvider(var format: String) extends FormatProvider {
    private val LINEINDENT = "\\n(\\s*)".r
    private val SPACES = "\\s+".r

    /** Advances the format string `format` to the first non-whitespace character and returns the whitespace prefix. */
    def advanceWS(): String = {
      LINEINDENT.findPrefixMatchOf(format) match {
        case Some(m) =>
          format = format.substring(m.end)
          m.matched
        case None => SPACES.findPrefixMatchOf(format) match {
          case Some(m) =>
            format = format.substring(m.end)
            m.matched
          case None => ""
        }
      }
    }

    private def printHtmlWS(): String = advanceWS().replaceAllLiterally("\n", "<br/>").replaceAll("\\s", "&nbsp;")

    /** Prints whitespace and checks that the remaining format string starts with `check` (literally). Advances the format string past `check`. */
    def printWS(check: String = ""): String = {
      val result = printHtmlWS()
      assert(format.startsWith(check), s"'$format' did not start with '$check'")
      format = format.substring(check.length)
      result
    }

    /** Prints whitespace prefix and formats `next` according to the format string. */
    def print(next: String): String = {
      printHtmlWS() + next.map(c => printWS(if (c != ' ') c.toString else "") + c).reduceOption(_ + _).getOrElse("")
    }
  }

  /** Noop format provider. */
  class NoneFormatProvider extends FormatProvider {
    override def printWS(check: String): String = ""
    override def print(next: String): String = next
  }

  def sequentJson(sequent: Sequent, marginLeft: Int, marginRight: Int): JsValue = {
    def fmlsJson(isAnte: Boolean, fmls: IndexedSeq[Formula]): JsValue = {
      JsArray(fmls.zipWithIndex.map { case (fml, i) =>
        /* Formula ID is formula number followed by comma-separated PosInExpr.
         formula number = strictly positive if succedent, strictly negative if antecedent, 0 is never used
        */
        val idx = if (isAnte) (-i)-1 else i+1
        val fmlString = JsString(UIKeYmaeraXPrettyPrinter(idx.toString, plainText=true)(fml))

        val format = new KeYmaeraXPrettierPrinter(if (isAnte) marginLeft else marginRight)(fml)
        val fmlJson = printJson(PosInExpr(), fml, PrettyPrintFormatProvider(format))(Position(idx), fml)
        JsObject(
          "id" -> JsString(idx.toString),
          "formula" -> JsObject(
            "json" -> fmlJson,
            "string" -> fmlString
          )
        )
      }.toVector)
    }
    JsObject(
      "ante" -> fmlsJson(isAnte = true, sequent.ante),
      "succ" -> fmlsJson(isAnte = false, sequent.succ)
    )
  }

  private def printObject(text: String, kind: String = "text"): JsValue = JsObject("text"->JsString(text), "kind" -> JsString(kind))
  private def print(text: String, fp: FormatProvider, kind: String = "text"): JsValue = printObject(fp.print(text), kind)
  private def print(q: PosInExpr, text: String, kind: String, fp: FormatProvider)(implicit top: Position): JsValue =
    JsObject("id" -> JsString(top + (if (q.pos.nonEmpty) "," + q.pos.mkString(",") else "")),
      "text"->JsString(fp.print(text)), "kind" -> JsString(kind))
  private def print(q: PosInExpr, kind: String, hasStep: Boolean, isEditable: Boolean, plainText: => String,
                    children: JsValue*)(implicit top: Position): JsValue = {
    JsObject(
      "id" -> JsString(top + (if (q.pos.nonEmpty) "," + q.pos.mkString(",") else "")),
      "kind" -> JsString(kind),
      "plain" -> (if (isEditable || q.pos.isEmpty) JsString(plainText) else JsNull),
      "step" -> JsString(if (hasStep) "has-step" else "no-step"),
      "editable" -> JsString(if (isEditable) "editable" else "not-editable"),
      "children"->JsArray(children:_*))
  }

  private def op(expr: Expression, fp: FormatProvider, opLevel: String = "op"): JsValue = expr match {
    // terms
    case _: Minus        => printObject(fp.printWS(OpSpec.op(expr).opcode) + "&minus;", opLevel + " k4-term-op")
    case _: Neg          => printObject(fp.printWS(OpSpec.op(expr).opcode) + "&minus;", opLevel + " k4-term-op")
    case _: Term         => printObject(fp.print(OpSpec.op(expr).opcode), opLevel + " k4-term-op")
    // formulas
    case _: NotEqual     => printObject(fp.printWS(OpSpec.op(expr).opcode) + "&ne;", opLevel + " k4-cmpfml-op")
    case _: GreaterEqual => printObject(fp.printWS(OpSpec.op(expr).opcode) + "&ge;", opLevel + " k4-cmpfml-op")
    case _: Greater      => printObject(fp.printWS(OpSpec.op(expr).opcode) + "&gt;", opLevel + " k4-cmpfml-op")
    case _: LessEqual    => printObject(fp.printWS(OpSpec.op(expr).opcode) + "&le;", opLevel + " k4-cmpfml-op")
    case _: Less         => printObject(fp.printWS(OpSpec.op(expr).opcode) + "&lt;", opLevel + " k4-cmpfml-op")
    case _: Forall       => printObject(fp.printWS(OpSpec.op(expr).opcode) + "&forall;", opLevel + " k4-fml-op")
    case _: Exists       => printObject(fp.printWS(OpSpec.op(expr).opcode) + "&exist;", opLevel + " k4-fml-op")
    case _: Not          => printObject(fp.printWS(OpSpec.op(expr).opcode) + "&not;", opLevel + " k4-propfml-op")
    case _: And          => printObject(fp.printWS(OpSpec.op(expr).opcode) + "&and;", opLevel + " k4-propfml-op")
    case _: Or           => printObject(fp.printWS(OpSpec.op(expr).opcode) + "&or;", opLevel + " k4-propfml-op")
    case _: Imply        => printObject(fp.printWS(OpSpec.op(expr).opcode) + "&rarr;", opLevel + " k4-propfml-op")
    case _: Equiv        => printObject(fp.printWS(OpSpec.op(expr).opcode) + "&#8596;", opLevel + " k4-propfml-op")
    case _: Formula      => printObject(fp.printWS(OpSpec.op(expr).opcode) + OpSpec.op(expr).opcode, opLevel + " k4-fml-op")
    // programs
    case _: Choice       => printObject(fp.printWS(OpSpec.op(expr).opcode) + "&cup;", opLevel + " k4-prg-op")
    case _: Program      => printObject(fp.printWS(OpSpec.op(expr).opcode) + OpSpec.op(expr).opcode, opLevel + " k4-prg-op")
    case _ => printObject(fp.printWS(OpSpec.op(expr).opcode) + OpSpec.op(expr).opcode, opLevel)
  }

  private def skipParens(expr: Modal): Boolean = OpSpec.op(expr.child) <= OpSpec.op(expr)
  private def skipParens(expr: Quantified): Boolean = OpSpec.op(expr.child) <= OpSpec.op(expr)
  private def skipParens(expr: UnaryComposite): Boolean = OpSpec.op(expr.child) <= OpSpec.op(expr)
  private def skipParensLeft(expr: BinaryComposite): Boolean =
    OpSpec.op(expr.left) < OpSpec.op(expr) || OpSpec.op(expr.left) <= OpSpec.op(expr) &&
      OpSpec.op(expr).assoc == LeftAssociative && OpSpec.op(expr.left).assoc == LeftAssociative
  private def skipParensRight(expr: BinaryComposite): Boolean =
    OpSpec.op(expr.right) < OpSpec.op(expr) || OpSpec.op(expr.right) <= OpSpec.op(expr) &&
      OpSpec.op(expr).assoc == RightAssociative && OpSpec.op(expr.right).assoc == RightAssociative

  private def wrapLeft(expr: BinaryComposite, left: => JsValue, fp: FormatProvider): List[JsValue] =
    if (skipParensLeft(expr)) left::Nil else print("(", fp)::left::print(")", fp)::Nil
  private def wrapRight(expr: BinaryComposite, right: => JsValue, fp: FormatProvider): List[JsValue] =
    if (skipParensRight(expr)) right::Nil else print("(", fp)::right::print(")", fp)::Nil
  private def wrapChild(expr: UnaryComposite, child: => JsValue, fp: FormatProvider): List[JsValue] =
    if (skipParens(expr)) child::Nil else print("(", fp)::child::print(")", fp)::Nil
  private def wrapChild(expr: Quantified, child: => JsValue, fp: FormatProvider): List[JsValue] =
    if (skipParens(expr)) child::Nil else print("(", fp)::child::print(")", fp)::Nil
  private def wrapChild(expr: Modal, child: => JsValue, fp: FormatProvider): List[JsValue] =
    if (skipParens(expr)) child::Nil else print("(", fp)::child::print(")", fp)::Nil
  private def pwrapLeft(expr: BinaryCompositeProgram, left: => List[JsValue], fp: FormatProvider): List[JsValue] =
    if (skipParensLeft(expr)) left else print("{", fp, "prg-open")+:left:+print("}", fp, "prg-close")
  private def pwrapRight(expr: BinaryCompositeProgram, right: => List[JsValue], fp: FormatProvider): List[JsValue] =
    if (skipParensRight(expr)) right else print("{", fp, "prg-open")+:right:+print("}", fp, "prg-close")

  private def printJson(q: PosInExpr, expr: Expression, fp: FormatProvider)(implicit top: Position, topExpr: Expression): JsValue = {
    val hasStep = UIIndex.allStepsAt(expr, Some(top++q), None).nonEmpty
    val parent = if (q.pos.isEmpty) None else topExpr match {
      case t: Term => t.sub(q.parent)
      case f: Formula => f.sub(q.parent)
      case p: Program => p.sub(q.parent)
      case _ => None
    }
    val isEditable = (expr, parent) match {
      // edit "top-most" formula only
      case (f: Formula, Some(_: Program | _: Modal) | None) => f.isFOL
      case (_, _) => false
    }

    expr match {
      //case t: UnaryCompositeTerm => print("", q, "term", hasStep, isEditable, op(t) +: wrapChild(t, printJson(q ++ 0, t.child)):_*)
      //case t: BinaryCompositeTerm => print("", q, "term", hasStep, isEditable, wrapLeft(t, printJson(q ++ 0, t.left)) ++ (op(t)::Nil) ++ wrapRight(t, printJson(q ++ 1, t.right)):_*)
      case f: ComparisonFormula =>
        print(q, "formula", hasStep, isEditable, expr.prettyString, wrapLeft(f, printJson(q ++ 0, f.left, fp), fp) ++ (op(f, fp)::Nil) ++ wrapRight(f, printJson(q ++ 1, f.right, fp), fp):_*)
      case DifferentialFormula(g) => print(q, "formula", hasStep, isEditable, expr.prettyString, print("(", fp), print(g.prettyString, fp), print(")", fp), op(expr, fp))
      case f: Quantified => print(q, "formula", hasStep, isEditable, expr.prettyString, op(f, fp)::print(f.vars.map(_.prettyString).mkString(","), fp)::Nil ++ wrapChild(f, printJson(q ++ 0, f.child, fp), fp):_*)
      case f: Box => print(q, "formula", hasStep, isEditable, expr.prettyString, print("[", fp, "mod-open")::printJson(q ++ 0, f.program, fp)::print("]", fp, "mod-close")::Nil ++ wrapChild(f, printJson(q ++ 1, f.child, fp), fp):_*)
      case f: Diamond => print(q, "formula", hasStep, isEditable, expr.prettyString, print("<", fp, "mod-open")::printJson(q ++ 0, f.program, fp)::print(">", fp, "mod-close")::Nil ++ wrapChild(f, printJson(q ++ 1, f.child, fp), fp):_*)
      case f: UnaryCompositeFormula => print(q, "formula", hasStep, isEditable, expr.prettyString, op(f, fp)+:wrapChild(f, printJson(q ++ 0, f.child, fp), fp):_*)
      case _: AtomicFormula => print(q, "formula", hasStep, isEditable, expr.prettyString, print(expr.prettyString, fp))
      case f: BinaryCompositeFormula => print(q, "formula", hasStep, isEditable, expr.prettyString, wrapLeft(f, printJson(q ++ 0, f.left, fp), fp) ++ (op(f, fp)::Nil) ++ wrapRight(f, printJson(q ++ 1, f.right, fp), fp):_*)
      case p: Program => print(q, "program", false, false, expr.prettyString, printPrgJson(q, p, fp):_*)
      case _ => print(q, expr.prettyString, "term", fp)
    }
  }

  private def printPrgJson(q: PosInExpr, expr: Program, fp: FormatProvider)(implicit top: Position, topExpr: Expression): List[JsValue] = expr match {
    case Assign(x, e) => printJson(q ++ 0, x, fp)::op(expr, fp, "topop")::printJson(q ++ 1, e, fp)::print(";", fp)::Nil
    case AssignAny(x) => printJson(q ++ 0, x, fp)::op(expr, fp, "topop")::print(";", fp)::Nil
    case Test(f) => op(expr, fp, "topop")::printJson(q ++ 0, f, fp)::print(";", fp)::Nil
    case t: UnaryCompositeProgram => print("{", fp, "prg-open")+:printRecPrgJson(q ++ 0, t.child, fp):+print("}", fp, "prg-close"):+op(t, fp, "topop")
    case t: Compose => pwrapLeft(t, printRecPrgJson(q ++ 0, t.left, fp), fp)++(print(q, "", "topop k4-prg-op", fp)::Nil)++pwrapRight(t, printRecPrgJson(q ++ 1, t.right, fp), fp)
    case t: BinaryCompositeProgram => pwrapLeft(t, printRecPrgJson(q ++ 0, t.left, fp), fp) ++ (op(t, fp, "topop")::Nil) ++ pwrapRight(t, printRecPrgJson(q ++ 1, t.right, fp), fp)
    case ODESystem(ode, f) if f != True => print("{", fp, "prg-open")::printJson(q ++ 0, ode, fp)::print(q, "&", "topop k4-prg-op", fp)::printJson(q ++ 1, f, fp)::print("}", fp, "prg-close")::Nil
    case ODESystem(ode, f) if f == True => print("{", fp, "prg-open")::printJson(q ++ 0, ode, fp)::print("}", fp, "prg-close")::Nil
    case AtomicODE(xp, e) => printJson(q ++ 0, xp, fp)::op(expr, fp, "topop")::printJson(q ++ 1, e, fp)::Nil
    case t: DifferentialProduct => printJson(q ++ 0, t.left, fp)::op(t, fp, "topop")::printJson(q ++ 1, t.right, fp)::Nil
    case c: DifferentialProgramConst => print(c.prettyString, fp)::Nil
    case c: ProgramConst => print(c.prettyString, fp)::Nil
    case t: ParallelAndChannels => print("{", fp, "prg-open")::printJson(q ++ 0, t.program, fp)::print(q, "&", "topop k4-prg-op", fp)::printJson(q ++ 1, t.channels, fp)::print("}", fp, "prg-close")::Nil
  }

  private def printRecPrgJson(q: PosInExpr, expr: Program, fp: FormatProvider)(implicit top: Position, topExpr: Expression): List[JsValue] = expr match {
    case Assign(x, e) => printJson(q ++ 0, x, fp)::op(expr, fp)::printJson(q ++ 1, e, fp)::print(";", fp)::Nil
    case AssignAny(x) => printJson(q ++ 0, x, fp)::op(expr, fp)::print(";", fp)::Nil
    case Test(f) => op(expr, fp)::printJson(q ++ 0, f, fp)::print(";", fp)::Nil
    case t: UnaryCompositeProgram => print("{", fp, "prg-open")+:printRecPrgJson(q ++ 0, t.child, fp):+print("}", fp, "prg-close"):+op(t, fp)
    case t: Compose => pwrapLeft(t, printRecPrgJson(q ++ 0, t.left, fp), fp) ++ pwrapRight(t, printRecPrgJson(q ++ 1, t.right, fp), fp)
    case t: BinaryCompositeProgram => pwrapLeft(t, printRecPrgJson(q ++ 0, t.left, fp), fp) ++ (op(t, fp)::Nil) ++ pwrapRight(t, printRecPrgJson(q ++ 1, t.right, fp), fp)
    case ODESystem(ode, f) if f != True => print("{", fp, "prg-open")::printJson(q ++ 0, ode, fp)::print("&", fp)::printJson(q ++ 1, f, fp)::print("}", fp, "prg-close")::Nil
    case ODESystem(ode, f) if f == True => print("{", fp, "prg-open")::printJson(q ++ 0, ode, fp)::print("}", fp, "prg-close")::Nil
    case AtomicODE(xp, e) => printJson(q ++ 0, xp, fp)::op(expr, fp)::printJson(q ++ 1, e, fp)::Nil
    case t: DifferentialProduct => printJson(q ++ 0, t.left, fp)::op(t, fp)::printJson(q ++ 1, t.right, fp)::Nil
    case c: DifferentialProgramConst => print(c.prettyString, fp)::Nil
    case c: ProgramConst => print(c.prettyString, fp)::Nil
  }

  /** Only first node's sequent is printed. */
  def nodesJson(nodes: List[ProofTreeNode], marginLeft: Int, marginRight: Int, printAllSequents: Boolean = false): List[(String, JsValue)] = {
    if (nodes.isEmpty) Nil
    else nodeJson(nodes.head, withSequent=true, marginLeft, marginRight) +: nodes.tail.map(nodeJson(_, withSequent=printAllSequents, marginLeft, marginRight))
  }

  def nodeJson(node: ProofTreeNode, withSequent: Boolean, marginLeft: Int, marginRight: Int): (String, JsValue) = {
    val id = JsString(node.id.toString)
    val sequent =
      if (withSequent) node.goal match { case None => JsNull case Some(goal) => sequentJson(goal, marginLeft, marginRight) }
      else JsNull
    val childrenIds = JsArray(node.children.map(s => JsString(s.id.toString)):_*)
    val parent = node.parent.map(n => JsString(n.id.toString)).getOrElse(JsNull)

    val posLocator =
      if (node.maker.isEmpty || node.maker.get.isEmpty) None
      else Try(BelleParser(node.maker.get)).toOption match { //@todo probably performance bottleneck
        case Some(pt: AppliedPositionTactic) => Some(pt.locator)
        case Some(pt: AppliedDependentPositionTactic) => Some(pt.locator)
        case _ => None
      }

    (node.id.toString, JsObject(
      "id" -> id,
      "isClosed" -> JsBoolean(node.numSubgoals <= 0),
      "sequent" -> sequent,
      "children" -> childrenIds,
      "rule" -> ruleJson(node.makerShortName.getOrElse(""), posLocator),
      "labels" -> JsArray(node.label.map(_.components).getOrElse(Nil).map(c => JsString(c.label)).toVector),
      "parent" -> parent))
  }

  def sectionJson(section: List[String]): JsValue = {
    JsObject("path" -> JsArray(section.map(JsString(_)):_*))
  }

  def deductionJson(deduction: List[List[String]]): JsValue =
    JsObject("sections" -> JsArray(deduction.map(sectionJson):_*))

  def itemJson(item: AgendaItem): (String, JsValue) = {
    val value = JsObject(
      "id" -> JsString(item.id.toString),
      "name" -> JsString(item.name),
      "proofId" -> JsString(item.proofId),
      "deduction" -> deductionJson(List(item.path)))
    (item.id.toString, value)
  }

  def nodeIdJson(n: List[Int]): JsValue = JsNull
  def proofIdJson(n: String): JsValue = JsString(n)

  def ruleJson(ruleName: String, pos: Option[PositionLocator]): JsValue = {
    val belleTerm = ruleName.split("\\(")(0)
    val (name, codeName, asciiName, maker, derivation: JsValue) = Try(DerivationInfo.ofCodeName(belleTerm)).toOption match {
      case Some(di) => (di.display.name, di.codeName, di.display.asciiName, ruleName,
          ApplicableAxiomsResponse(Nil, Map.empty, pos).derivationJson(di).fields.getOrElse("derivation", JsNull))
      case None => (ruleName, ruleName, ruleName, ruleName, JsNull)
    }

    JsObject(
      "id" -> JsString(name),
      "name" -> JsString(name),
      "codeName" -> JsString(codeName),
      "asciiName" -> JsString(asciiName),
      "maker" -> JsString(maker),
      "pos" -> (pos match {
        case Some(Fixed(p, _, _)) => JsString(p.prettyString)
        case _ => JsString("")
      }),
      "derivation" -> derivation
    )
  }

  def agendaItemJson(item: AgendaItemPOJO): JsValue = {
    JsObject(
      "agendaItemId" -> JsString(item.initialProofNode.toString),
      "proofId" -> JsString(item.proofId.toString),
      "displayName" -> JsString(item.displayName)
    )
  }
}

case class AgendaAwesomeResponse(modelId: String, proofId: String, root: ProofTreeNode, leaves: List[ProofTreeNode],
                                 agenda: List[AgendaItem], closed: Boolean, marginLeft: Int, marginRight: Int) extends Response {
  override val schema = Some("agendaawesome.js")

  private lazy val proofTree = {
    val theNodes: List[(String, JsValue)] = nodeJson(root, withSequent=false, marginLeft, marginRight) +: nodesJson(leaves, marginLeft, marginRight)
    JsObject(
      "id" -> proofIdJson(proofId),
      "nodes" -> JsObject(theNodes.toMap),
      "root" -> JsString(root.id.toString),
      "isProved" -> JsBoolean(root.done)
    )
  }

  private lazy val agendaItems = JsObject(agenda.map(itemJson):_*)

  def getJson =
    JsObject (
      "modelId" -> JsString(modelId),
      "proofTree" -> proofTree,
      "agendaItems" -> agendaItems,
      "closed" -> JsBoolean(closed)
    )
}

class GetAgendaItemResponse(item: AgendaItemPOJO) extends Response {
  def getJson: JsValue = agendaItemJson(item)
}

class ProofTaskParentResponse (parent: ProofTreeNode, marginLeft: Int, marginRight: Int) extends Response {
  def getJson: JsValue = nodeJson(parent, withSequent=true, marginLeft, marginRight)._2
}

class GetPathAllResponse(path: List[ProofTreeNode], parentsRemaining: Int, marginLeft: Int, marginRight: Int) extends Response {
  def getJson: JsValue =
    JsObject (
      "numParentsUntilComplete" -> JsNumber(parentsRemaining),
      "path" -> JsArray(path.map(nodeJson(_, withSequent=true, marginLeft, marginRight)._2):_*)
    )
}

class GetBranchRootResponse(node: ProofTreeNode, marginLeft: Int, marginRight: Int) extends Response {
  def getJson: JsValue = nodeJson(node, withSequent=true, marginLeft, marginRight)._2
}

case class LemmasResponse(infos: List[ProvableInfo]) extends Response {
  override def getJson: JsValue = {
    val json = infos.map(i =>
      JsObject(
        "name" -> JsString(i.canonicalName),
        "codeName" -> JsString(i.codeName),
        "defaultKeyPos" -> {
          val key = AxiomIndex.axiomIndex(i.canonicalName)._1
          JsString(key.pos.mkString("."))
        },
        "displayInfo" -> (i.display match {
          case AxiomDisplayInfo(_, f) => JsString(f)
          case _ => JsNull
        }),
        "displayInfoParts" -> RequestHelper.jsonDisplayInfoComponents(i)))

    JsObject("lemmas" -> JsArray(json:_*))
  }
}

case class ApplicableAxiomsResponse(derivationInfos: List[(DerivationInfo, Option[DerivationInfo])],
                                    suggestedInput: Map[ArgInfo, Expression],
                                    suggestedPosition: Option[PositionLocator] = None) extends Response {
  def inputJson(input: ArgInfo): JsValue = {
    (suggestedInput.get(input), input) match {
      case (Some(e), FormulaArg(name, _)) =>
        JsObject (
          "type" -> JsString(input.sort),
          "param" -> JsString(name),
          "value" -> JsString(e.prettyString)
        )
      case (_, ListArg(name, elementSort, _)) => //@todo suggested input for Formula*
        JsObject(
          "type" -> JsString(input.sort),
          "elementType" -> JsString(elementSort),
          "param" -> JsString(name)
        )
      case _ =>
        JsObject (
          "type" -> JsString(input.sort),
          "param" -> JsString(input.name)
        )
    }
  }

  def inputsJson(info: List[ArgInfo]): JsArray = {
    info match {
      case Nil => JsArray()
      case inputs => JsArray(inputs.map(inputJson):_*)
    }
  }

  private def helpJson(codeName: String): JsString = {
    val helpResource = getClass.getResourceAsStream(s"/help/axiomsrules/$codeName.html")
    if (helpResource == null) JsString("")
    else JsString(scala.io.Source.fromInputStream(helpResource)(scala.io.Codec.UTF8).mkString)
  }

  def axiomJson(info: DerivationInfo): JsObject = {
    val formulaText =
      (info, info.display) match {
        case (_, AxiomDisplayInfo(_, formulaDisplay)) => formulaDisplay
        case (_, InputAxiomDisplayInfo(_, formulaDisplay, _)) => formulaDisplay
        case (info:AxiomInfo, _) => info.formula.prettyString
      }
    JsObject(
      "type" -> JsString("axiom"),
      "formula" -> JsString(formulaText),
      "codeName" -> JsString(info.codeName),
      "canonicalName" -> JsString(info.canonicalName),
      "defaultKeyPos" -> {
        val key = AxiomIndex.axiomIndex(info.canonicalName)._1
        JsString(key.pos.mkString("."))
      },
      "displayInfoParts" -> RequestHelper.jsonDisplayInfoComponents(info),
      "input" -> inputsJson(info.inputs),
      "help" -> helpJson(info.codeName)
    )
  }

  def tacticJson(info: DerivationInfo): JsObject = {
    JsObject(
      "type" -> JsString("tactic"),
      "expansible" -> JsBoolean(info.revealInternalSteps),
      "input" -> inputsJson(info.inputs),
      "help" -> helpJson(info.codeName)
    )
  }

  def sequentJson(sequent:SequentDisplay): JsValue = {
    val json = JsObject (
    "ante" -> JsArray(sequent.ante.map(JsString(_)):_*),
    "succ" -> JsArray(sequent.succ.map(JsString(_)):_*),
    "isClosed" -> JsBoolean(sequent.isClosed)
    )
   json
  }

  def ruleJson(info: DerivationInfo, conclusion: SequentDisplay, premises: List[SequentDisplay]): JsObject = {
    val conclusionJson = sequentJson(conclusion)
    val premisesJson = JsArray(premises.map(sequentJson):_*)
    JsObject(
      "type" -> JsString("sequentrule"),
      "expansible" -> JsBoolean(info.revealInternalSteps),
      "conclusion" -> conclusionJson,
      "premise" -> premisesJson,
      "input" -> inputsJson(info.inputs),
      "help" -> helpJson(info.codeName)
    )
  }

  def derivationJson(derivationInfo: DerivationInfo): JsObject = {
    val derivation = derivationInfo match {
      case info: AxiomInfo => axiomJson(info)
      case info: DerivationInfo => info.display match {
        case _: SimpleDisplayInfo => tacticJson(info)
        case _: AxiomDisplayInfo => axiomJson(info)
        case RuleDisplayInfo(_, conclusion, premises) => ruleJson(info, conclusion, premises)
      }
    }
    JsObject(
      "id" -> new JsString(derivationInfo.codeName),
      "name" -> new JsString(derivationInfo.display.name),
      "asciiName" -> new JsString(derivationInfo.display.asciiName),
      "codeName" -> new JsString(derivationInfo.codeName),
      "derivation" -> derivation
    )
  }

  private def posJson(pos: Option[PositionLocator]): JsValue = pos match {
    case Some(Fixed(p, _, _)) => new JsString(p.toString)
    case Some(Find(_, _, _: AntePosition, _)) => new JsString("L")
    case Some(Find(_, _, _: SuccPosition, _)) => new JsString("R")
    case _ => JsNull
  }

  def derivationJson(info: (DerivationInfo, Option[DerivationInfo])): JsObject = info._2 match {
    case Some(comfort) =>
      JsObject(
        "standardDerivation" -> derivationJson(info._1),
        "comfortDerivation" -> derivationJson(comfort),
        "positionSuggestion" -> posJson(suggestedPosition)
      )
    case None =>
      JsObject(
        "standardDerivation" -> derivationJson(info._1),
        "positionSuggestion" -> posJson(suggestedPosition)
      )
  }

  def getJson = JsArray(derivationInfos.map(derivationJson):_*)
}

class PruneBelowResponse(item: AgendaItem) extends Response {
  def getJson: JsObject = JsObject(
    "agendaItem" -> Helpers.itemJson(item)._2
  )
}

class CounterExampleResponse(kind: String, fml: Formula = True, cex: Map[NamedSymbol, Expression] = Map()) extends Response {
  def getJson: JsObject = {
    val bv = StaticSemantics.boundVars(fml).toSet[NamedSymbol]
    val (boundCex, freeCex) = cex.partition(e => bv.contains(e._1))
    JsObject(
      "result" -> JsString(kind),
      "origFormula" -> JsString(fml.prettyString.replaceAllLiterally("()", "")),
      "cexFormula" -> JsString(createCexFormula(fml, cex).replaceAllLiterally("()", "")),
      "cexValues" -> JsArray(
        freeCex.map(e => JsObject(
          "symbol" -> JsString(e._1.prettyString.replaceAllLiterally("()", "")),
          "value" -> JsString(e._2.prettyString.replaceAllLiterally("()", "")))
        ).toList:_*
      ),
      "speculatedValues" -> JsArray(
        boundCex.map(e => JsObject(
          "symbol" -> JsString(e._1.prettyString.replaceAllLiterally("()", "")),
          "value" -> JsString(e._2.prettyString.replaceAllLiterally("()", "")))
        ).toList:_*
      )
    )
  }

  private def createCexFormula(fml: Formula, cex: Map[NamedSymbol, Expression]): String = {
    def replaceWithCexVals(fml: Formula, cex: Map[NamedSymbol, Expression]): Formula = {
      ExpressionTraversal.traverse(new ExpressionTraversal.ExpressionTraversalFunction {
        override def preT(p: PosInExpr, t: Term): Either[Option[ExpressionTraversal.StopTraversal], Term] = t match {
          case v: Variable if cex.contains(v) => Right(cex(v).asInstanceOf[Term])
          case FuncOf(fn, _) if cex.contains(fn) => Right(cex(fn).asInstanceOf[Term])
          case _ => Left(None)
        }

        override def preF(p: PosInExpr, f: Formula): Either[Option[ExpressionTraversal.StopTraversal], Formula] = f match {
          case PredOf(fn, _) => Right(cex(fn).asInstanceOf[Formula])
          case _ => Left(None)
        }
      }, fml).get
    }

    if (cex.nonEmpty & cex.forall(_._2.isInstanceOf[Term])) {
      val Imply(assumptions, conclusion) = fml

      //@note flag false comparison formulas `cmp` with (cmp<->false)
      val cexConclusion = ExpressionTraversal.traverse(new ExpressionTraversal.ExpressionTraversalFunction {
        private def makeSeq(fml: Formula): Sequent = Sequent(immutable.IndexedSeq(), immutable.IndexedSeq(fml))

        override def preF(p: PosInExpr, f: Formula): Either[Option[ExpressionTraversal.StopTraversal], Formula] = f match {
          case cmp: ComparisonFormula =>
            val cexCmp = TactixLibrary.proveBy(replaceWithCexVals(cmp, cex), TactixLibrary.RCF)
            if (cexCmp.subgoals.size > 1 || cexCmp.subgoals.headOption.getOrElse(makeSeq(True)) == makeSeq(False)) {
              Right(And(False, And(cmp, False)))
            } else Right(cmp)
          case _ => Left(None)
        }
      }, conclusion).get

      val cexFml = UIKeYmaeraXPrettyPrinter.htmlEncode(Imply(assumptions, cexConclusion).prettyString)

      //@note look for variables at word boundary (do not match in the middle of other words, do not match between &;)
      val symMatcher = s"(${cex.keySet.map(_.prettyString).mkString("|")})(?![^&]*;)\\b".r("v")
      val cexFmlWithVals = symMatcher.replaceAllIn(cexFml, (m: Match) => {
        val cexSym = UIKeYmaeraXPrettyPrinter.htmlEncode(m.group("v"))
        if ((m.before + cexSym).endsWith("false")) {
          cexSym
        } else {
          val cexVal = UIKeYmaeraXPrettyPrinter.htmlEncode(cex.find(_._1.prettyString == cexSym).get._2.prettyString)
          s"""<div class="k4-cex-fml"><ul><li>$cexVal</li></ul>$cexSym</div>"""
        }
      })

      //@note look for (false & cexCmp & false) groups and replace with boldface danger spans
      val cexMatcher = "false&and;(.*?)&and;false".r("fml")
      cexMatcher.replaceAllIn(cexFmlWithVals, (m: Match) => {
        val cexCmp = m.group("fml")
        s"""<div class="k4-cex-highlight text-danger">$cexCmp</div>"""
      })
    } else {
      replaceWithCexVals(fml, cex).prettyString
    }
  }
}

class ODEConditionsResponse(sufficient: List[Formula], necessary: List[Formula]) extends Response {
  //@todo formula JSON with HTML formatting in UI
  override def getJson: JsValue = JsObject(
    "sufficient" -> JsArray(sufficient.map(f => JsObject("text" -> JsString(f.prettyString))).toVector),
    "necessary" -> JsArray(necessary.map(f => JsObject("text" -> JsString(f.prettyString))).toVector)
  )
}

class PegasusCandidatesResponse(candidates: Seq[Either[Seq[(Formula, String)],Seq[(Formula, String)]]]) extends Response {
  //@todo formula JSON with HTML formatting in UI
  override def getJson: JsValue = JsObject(
    "candidates" -> JsArray(candidates.map({
      case Left(invs) => JsObject(
        "fmls" -> JsArray(invs.map(f => JsObject("text" -> JsString(f._1.prettyString), "method" -> JsString(f._2))).toVector),
        "isInv" -> JsBoolean(true))
      case Right(invs) => JsObject(
        "fmls" -> JsArray(invs.map(f => JsObject("text" -> JsString(f._1.prettyString), "method" -> JsString(f._2))).toVector),
        "isInv" -> JsBoolean(false))
    }).toVector)
  )
}

class SetupSimulationResponse(initial: Formula, stateRelation: Formula) extends Response {
  def getJson = JsObject(
    "initial" -> JsString(initial.prettyString),
    "stateRelation" -> JsString(stateRelation.prettyString)
  )
}

class SimulationResponse(simulation: List[List[Map[NamedSymbol, Number]]], stepDuration: Term) extends Response {
  def getJson: JsObject = {
    val seriesList = simulation.map(convertToDataSeries)
    JsObject(
      "varNames" -> JsArray(seriesList.head.map(_._1).map(name => JsString(name.prettyString)).toVector),
      "ticks" -> JsArray(seriesList.head.head._2.indices.map(i => JsString(i.toString)).toVector),
      "lineStates" -> JsArray(seriesList.map(series =>
        JsArray(series.map({
          case (_, vs) => JsArray(vs.map(v => JsNumber(v.value)).toVector)
        }).toVector)).toVector),
      "radarStates" -> JsArray(simulation.map(run => JsArray(run.map(state =>
        JsArray(state.map({case (_, v) => JsNumber(v.value)}).toVector)).toVector)).toVector)
    )
  }

  def convertToDataSeries(sim: List[Map[NamedSymbol, Number]]): List[(NamedSymbol, List[Number])] = {
    // convert to data series
    val dataSeries: Map[NamedSymbol, ListBuffer[Number]] = sim.head.keySet.map(_ -> ListBuffer[Number]()).toMap
    sim.foreach(state => state.foreach({
      case (n, v) => dataSeries.getOrElse(n, throw new IllegalStateException("Unexpected data series " + n)) += v
    }))
    dataSeries.mapValues(_.toList).toList
  }
}

class KyxConfigResponse(kyxConfig: String) extends Response {
  def getJson = JsObject(
    "kyxConfig" -> JsString(kyxConfig)
  )
}

class KeymaeraXVersionResponse(installedVersion: String, upToDate: Option[Boolean], latestVersion: Option[String]) extends Response {
  assert(upToDate.isDefined == latestVersion.isDefined, "upToDate and latestVersion should both be defined, or both be undefined.")
  def getJson: JsObject = upToDate match {
    case Some(b) if b => JsObject("keymaeraXVersion" -> JsString(installedVersion), "upToDate" -> JsTrue)
    case Some(b) if !b => JsObject("keymaeraXVersion" -> JsString(installedVersion), "upToDate" -> JsFalse, "latestVersion" -> JsString(latestVersion.get))
    case None => JsObject("keymaeraXVersion" -> JsString(installedVersion))
  }
}

class ConfigureMathematicaResponse(linkNamePrefix: String, jlinkLibDirPrefix: String, success: Boolean) extends Response {
  def getJson = JsObject(
    "linkNamePrefix" -> JsString(linkNamePrefix),
    "jlinkLibDirPrefix" -> JsString(jlinkLibDirPrefix),
    "success" -> {if(success) JsTrue else JsFalse}
  )
}

class MathematicaConfigSuggestionResponse(os: String, jvmBits: String, suggestionFound: Boolean,
                                          suggestion: ToolConfiguration.ConfigSuggestion,
                                          allSuggestions: List[ToolConfiguration.ConfigSuggestion]) extends Response {

  private def convertSuggestion(info: ToolConfiguration.ConfigSuggestion): JsValue = JsObject(
    "version" -> JsString(info.version),
    "kernelPath" -> JsString(info.kernelPath),
    "kernelName" -> JsString(info.kernelName),
    "jlinkPath" -> JsString(info.jlinkPath),
    "jlinkName" -> JsString(info.jlinkName)
  )

  def getJson: JsValue = JsObject(
    "os" -> JsString(os),
    "jvmArchitecture" -> JsString(jvmBits),
    "suggestionFound" -> JsBoolean(suggestionFound),
    "suggestion" -> convertSuggestion(suggestion),
    "allSuggestions" -> JsArray(allSuggestions.map(convertSuggestion):_*)
  )
}

//@todo these are a mess.
class SystemInfoResponse(os: String, osVersion: String, jvmHome: String, jvmVendor: String,
                         jvmVersion: String, jvmBits: String) extends Response {
  def getJson: JsValue = JsObject(
    "os" -> JsString(os),
    "osVersion" -> JsString(osVersion),
    "jvmHome" -> JsString(jvmHome),
    "jvmVendor" -> JsString(jvmVendor),
    "jvmVersion" -> JsString(jvmVersion),
    "jvmArchitecture" -> JsString(jvmBits)
  )
}

class MathematicaConfigurationResponse(linkName: String, jlinkLibDir: String, jlinkTcpip: String) extends Response {
  def getJson: JsValue = JsObject(
    "linkName" -> JsString(linkName),
    "jlinkLibDir" -> JsString(jlinkLibDir),
    "jlinkTcpip" -> JsString(jlinkTcpip)
  )
}

class ToolConfigStatusResponse(tool: String, configured: Boolean) extends Response {
  def getJson: JsValue = JsObject(
    "tool" -> JsString(tool),
    "configured" -> { if (configured) JsTrue else JsFalse }
  )
}

class ToolStatusResponse(tool: String, availableWorkers: Int) extends Response {
  def getJson: JsValue = JsObject(
    "tool" -> JsString(tool),
    "busy" -> JsBoolean(availableWorkers <= 0),
    "availableWorkers" -> JsNumber(availableWorkers)
  )
}

class ListExamplesResponse(examples: List[ExamplePOJO]) extends Response {
  def getJson: JsValue = JsArray(
    examples.map(e =>
      JsObject(
        "id" -> JsNumber(e.id),
        "title" -> JsString(e.title),
        "description" -> JsString(e.description),
        "infoUrl" -> JsString(e.infoUrl),
        "url" -> JsString(e.url),
        "image" -> JsString(e.imageUrl)
      )
    ).toVector
  )
}


/**
 * @return JSON that is directly usable by angular.treeview
 */
class AngularTreeViewResponse(tree: String) extends Response {
  override val schema = Some("angular.treeview.js")

  def getJson = JsArray( convert(JsonParser(tree).asJsObject) )

  private def convert(node: JsObject) : JsValue = {
    //TODO switch to Jolt (https://github.com/bazaarvoice/jolt) once they can handle trees
    val children = (node.fields.get("children") match {
      case Some(c) => c
      case None => throw new IllegalArgumentException("Schema violation")
    }) match {
      case JsArray(c) => c
      case _ => throw new IllegalArgumentException("Schema violation")
    }
    val proofInfo = node.fields.get("infos") match {
      case Some(info) => info
      case None => JsArray()
    }

    val id = node.fields.get("id") match { case Some(i) => i case None => throw new IllegalArgumentException("Schema violation") }
    if (children.nonEmpty) {
      // TODO only retrieves the first alternative of the bipartite graph
      val step = children.head.asJsObject
      val rule = step.fields.get("rule") match {
        case Some(r) => r.asJsObject.fields.get("name") match {
          case Some(n) => n
          case None => throw new IllegalArgumentException("Schema violation")
        }
        case None => throw new IllegalArgumentException("Schema violation")
      }
      val subgoals = step.fields.get("children") match {
        case Some(gl) => gl.asInstanceOf[JsArray].elements.map(g => convert(g.asJsObject()))
        case None => throw new IllegalArgumentException("Schema violation")
      }
      JsObject(
        "id" -> id,
        "label" -> rule,
        "info" -> proofInfo,
        "children" -> JsArray(subgoals)
      )
    } else {
      JsObject(
        "id" -> id,
        "label" -> JsString("Open Goal"), // TODO only if the goal is closed, which is not yet represented in JSON
        "info" -> proofInfo,
        "children" -> JsArray()
      )
    }
  }
}


class DashInfoResponse(openProofs: Int, allModels: Int, provedModels: Int) extends Response {
  override val schema = Some("DashInfoResponse.js")
  def getJson = JsObject(
    "open_proof_count" -> JsNumber(openProofs),
    "all_models_count" -> JsNumber(allModels),
    "proved_models_count" -> JsNumber(provedModels)
  )
}

class ExtractDatabaseResponse(path: String) extends Response {
  def getJson = JsObject(
    "path" -> JsString(path)
  )
}

class NodeResponse(tree: String) extends Response {
  //todo add schema.
  val node: JsObject = JsonParser(tree).asJsObject
  def getJson: JsObject = node
}


case class GetTacticResponse(tacticText: String) extends Response {
  def getJson = JsObject(
    "tacticText" -> JsString(tacticText)
  )
}

case class ExpandTacticResponse(detailsProofId: Int, tacticParent: String, stepsTactic: String,
                                tree: List[ProofTreeNode], openGoals: List[AgendaItem],
                                marginLeft: Int, marginRight: Int) extends Response {
  private lazy val proofTree = {
    val theNodes: List[(String, JsValue)] = nodesJson(tree, marginLeft, marginRight, printAllSequents=true)
    JsObject(
      "nodes" -> JsObject(theNodes.toMap),
      "root" -> JsString(tree.head.id.toString))
  }

  def getJson = JsObject(
    "tactic" -> JsObject(
      "stepsTactic" -> JsString(stepsTactic.trim()),
      "parent" -> JsString(tacticParent)
    ),
    "detailsProofId" -> JsString(detailsProofId.toString),
    if (tree.nonEmpty) "proofTree" -> proofTree else "proofTree" -> JsObject(),
    "openGoals" -> JsObject(openGoals.map(itemJson):_*)
  )
}

class TacticDiffResponse(diff: TacticDiff.Diff) extends Response {
  def getJson = JsObject(
    "context" -> JsString(BellePrettyPrinter(diff._1.t)),
    "replOld" -> JsArray(diff._2.map({ case (dot, repl) => JsObject("dot" -> JsString(BellePrettyPrinter(dot)), "repl" -> JsString(BellePrettyPrinter(repl))) }).toVector),
    "replNew" -> JsArray(diff._3.map({ case (dot, repl) => JsObject("dot" -> JsString(BellePrettyPrinter(dot)), "repl" -> JsString(BellePrettyPrinter(repl))) }).toVector)
  )
}

class ExtractProblemSolutionResponse(tacticText: String) extends Response {
  def getJson = JsObject(
    "fileContents" -> JsString(tacticText)
  )
}

class ValidateProofResponse(taskId: String, proved: Option[Boolean]) extends Response {
  def getJson: JsObject = proved match {
    case Some(isProved) => JsObject(
      "uuid" -> JsString(taskId),
      "running" -> JsBoolean(false),
      "proved" -> JsBoolean(isProved)
    )
    case None => JsObject(
      "uuid" -> JsString(taskId),
      "running" -> JsBoolean(true)
    )
  }
}

class MockResponse(resourceName: String) extends Response {
  //@todo add schema
  def getJson: JsValue = scala.io.Source.fromInputStream(getClass.getResourceAsStream(resourceName)).mkString.parseJson
}

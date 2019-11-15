/**
* Copyright (c) Carnegie Mellon University.
* See LICENSE.txt for the conditions of this license.
*/
/**
 * Differential Dynamic Logic lexer for concrete KeYmaera X notation.
  *
  * @author Andre Platzer
 * @see "Andre Platzer. A uniform substitution calculus for differential dynamic logic.  arXiv 1503.01981, 2015."
 */
package edu.cmu.cs.ls.keymaerax.parser

import org.apache.logging.log4j.scala.Logging

import scala.annotation.tailrec
import scala.collection.immutable._
import scala.util.matching.Regex

/**
 * LexerModes corresponds to file types.
 */
sealed abstract class LexerMode
object ExpressionMode extends LexerMode
object AxiomFileMode extends LexerMode
object ProblemFileMode extends LexerMode
object LemmaFileMode extends LexerMode

/**
 * Terminal symbols of the differential dynamic logic grammar.
  *
  * @author Andre Platzer
 */
sealed abstract class Terminal(val img: String) {
  override def toString: String = getClass.getSimpleName
  /** Human-readable description */
  def description: String = img
  /**
   * @return The regex that identifies this token.
   */
  def regexp : scala.util.matching.Regex = img.r

  val startPattern: Regex = ("^" + regexp.pattern.pattern).r
}
private abstract class OPERATOR(val opcode: String) extends Terminal(opcode) {
  //final def opcode: String = img
  override def toString: String = getClass.getSimpleName //+ "\"" + img + "\""
}
private case class IDENT(name: String, index: Option[Int] = None) extends Terminal(name + (index match {case Some(x) => "_"+x.toString case None => ""})) {
  override def toString: String = "ID(\"" + (index match {
    case None => name
    case Some(idx) => name + "," + idx
  }) + "\")"
  override def regexp: Regex = IDENT.regexp
}
private object IDENT {
  //@note Pattern is more permissive than NamedSymbol's since Lexer's IDENT will include the index, so xy_95 is acceptable.
  def regexp: Regex = """([a-zA-Z][a-zA-Z0-9]*\_?\_?[0-9]*)""".r
  val startPattern: Regex = ("^" + regexp.pattern.pattern).r
}
private case class NUMBER(value: String) extends Terminal(value) {
  override def toString: String = "NUM(" + value + ")"
  override def regexp: Regex = NUMBER.regexp
}
private object NUMBER {
  //A bit weird, but this gives the entire number in a single group.
  //def regexp = """(-?[0-9]+\.?[0-9]*)""".r
  //@NOTE Minus sign artificially excluded from the number to make sure x-5 lexes as IDENT("x"),MINUS,NUMBER("5") not as IDENT("x"),NUMBER("-5")
  def regexp: Regex = """([0-9]+\.?[0-9]*)""".r
  val startPattern: Regex = ("^" + regexp.pattern.pattern).r
}

/**
 * End Of Stream
 */
object EOF extends Terminal("<EOF>") {
  override def regexp: Regex = "$^".r //none.
}

private object LPAREN  extends Terminal("(") {
  override def regexp: Regex = """\(""".r
}
private object RPAREN  extends Terminal(")") {
  override def regexp: Regex = """\)""".r
}
private object LBANANA  extends Terminal("(|") {
  override def regexp: Regex = """\(\|""".r
}
private object RBANANA  extends Terminal("|)") {
  override def regexp: Regex = """\|\)""".r
}
private object LBRACE  extends Terminal("{") {
  override def regexp: Regex = """\{""".r
}
private object RBRACE  extends Terminal("}") {
  override def regexp: Regex = """\}""".r
}
private object LBARB   extends Terminal("{|") {
  override def regexp: Regex = """\{\|""".r
}
private object RBARB   extends Terminal("|}") {
  override def regexp: Regex = """\|\}""".r
}
private object LBOX    extends Terminal("[") {
  override def regexp: Regex = """\[""".r
}
private object RBOX    extends Terminal("]") {
  override def regexp: Regex = """\]""".r
}
private object LDIA    extends OPERATOR("<") {
  override def regexp: Regex = """\<""".r
}//@todo really operator or better not?
private object RDIA    extends OPERATOR(">") {
  override def regexp: Regex = """\>""".r
}

private object PRG_DEF  extends OPERATOR("::=")

private object COMMA   extends OPERATOR(",")
private object COLON   extends OPERATOR(":")

private object PRIME   extends OPERATOR("'")
private object POWER   extends OPERATOR("^") {
  override def regexp: Regex = """\^""".r
}
private object STAR    extends OPERATOR("*") {
  override def regexp: Regex = """\*""".r
}
private object SLASH   extends OPERATOR("/")
private object PLUS    extends OPERATOR("+") {
  override def regexp: Regex = """\+""".r
}
private object MINUS   extends OPERATOR("-")

private object NOT     extends OPERATOR("!") {
  override def regexp: Regex = """\!""".r
}
private object AMP     extends OPERATOR("&")
private object DOUBLE_PIPE      extends OPERATOR("||") {
  override def regexp: Regex = """\|\|""".r
}
private object OR      extends OPERATOR("|") {
  override def regexp: Regex = """\|""".r
}
private object EQUIV   extends OPERATOR("<->")
private object EQUIV_UNICODE extends OPERATOR("↔")
private object IMPLY   extends OPERATOR("->")
private object IMPLY_UNICODE extends OPERATOR("→")

//@todo maybe could change to <-- to disambiguate poor lexer's x<-7 REVIMPLY from LDIA MINUS
private object REVIMPLY extends OPERATOR("<-")
private object REVIMPLY_UNICODE extends OPERATOR("←")

private object FORALL  extends OPERATOR("\\forall") {
  override def regexp: Regex = """\\forall""".r
}
private object EXISTS  extends OPERATOR("\\exists") {
  override def regexp: Regex = """\\exists""".r
}

private object EQ      extends OPERATOR("=")
private object NOTEQ   extends OPERATOR("!=") {
  override def regexp: Regex = """\!=""".r
}
private object GREATEREQ extends OPERATOR(">=")
private object LESSEQ  extends OPERATOR("<=")

//Unicode versions of operators:
private object LESSEQ_UNICODE extends OPERATOR("≤")
private object GREATEREQ_UNICODE extends OPERATOR("≥")
private object AND_UNICODE extends OPERATOR("∧")
private object OR_UNICODE extends OPERATOR("∨")
private object UNEQUAL_UNICODE extends OPERATOR("≠")
private object FORALL_UNICODE extends OPERATOR("∀")
private object EXISTS_UNICODE extends OPERATOR("∃")

private object TRUE    extends OPERATOR("true")
private object FALSE   extends OPERATOR("false")

//@todo should probably also allow := *
private object ASSIGNANY extends OPERATOR(":=*") {
  override def regexp: Regex = """:=\*""".r
}
private object ASSIGN  extends OPERATOR(":=")
private object TEST    extends OPERATOR("?") {
  override def regexp: Regex = """\?""".r
}
private object IF extends OPERATOR("if")
private object ELSE extends OPERATOR("else")
private object SEMI    extends OPERATOR(";")
private object CHOICE  extends OPERATOR("++") {
  override def regexp: Regex = """\+\+|\u222A""".r
}
//@todo simplify lexer by using silly ^@ notation rather than ^d for now. @ for adversary isn't too bad to remember but doesn't look as good as ^d.
private object DUAL    extends OPERATOR("^@") {
  override def regexp: Regex = """\^\@""".r
}
private object TILDE      extends OPERATOR("~")
private object BACKSLASH extends Terminal("\\\\")
private object QUOTATION_MARK extends Terminal("\"")

/*@TODO
private object DCHOICE  extends OPERATOR("--") {
  override def regexp = """--""".r
}
*/

// pseudos: could probably demote so that some are not OPERATOR
private object NOTHING extends Terminal("")

private case class DOT(index: Option[Int] = None) extends Terminal("•" + (index match {case Some(x) => "_"+x case None => ""})) {
  override def toString: String = "DOT(\"" + (index match {
    case None => ""
    case Some(idx) => idx
  }) + "\")"
  override def regexp: Regex = DOT.regexp
}
private object DOT {
  def regexp: Regex = """((?:•(?:\_[0-9]+)?)|(?:\.\_[0-9]+))""".r
  val startPattern: Regex = ("^" + regexp.pattern.pattern).r
}

private object PLACE   extends OPERATOR("⎵") //("_")
private object ANYTHING extends OPERATOR("??") {
  override def regexp: Regex = """\?\?""".r
}
private object PSEUDO  extends Terminal("<pseudo>")

private object EXERCISE_PLACEHOLDER extends Terminal("__________")

// @annotations

private object INVARIANT extends Terminal("@invariant") {
  override def regexp: Regex = """\@invariant""".r
}

// axiom and problem file

private object AXIOM_BEGIN extends Terminal("Axiom") {
  override def regexp: Regex = """Axiom""".r
}
private object END_BLOCK extends Terminal("End")
private case class DOUBLE_QUOTES_STRING(var s: String) extends Terminal("<string>") {
  override def regexp: Regex = DOUBLE_QUOTES_STRING_PAT.regexp
}
private object DOUBLE_QUOTES_STRING_PAT {
  def regexp: Regex = """\"([^\"]*)\"""".r
  val startPattern: Regex = ("^" + regexp.pattern.pattern).r
}
private object PERIOD extends Terminal(".") {
  override def regexp: Regex = "\\.".r
}
private object FUNCTIONS_BLOCK extends Terminal("Functions") {
  //not totally necessary -- you'll still get the right behavior because . matches \. But also allows stuff like Functions: which maybe isn't terrible.
//  override def regexp = """Functions\.""".r
}
private object DEFINITIONS_BLOCK extends Terminal("Definitions")
private object PROGRAM_VARIABLES_BLOCK extends Terminal("ProgramVariables")
private object VARIABLES_BLOCK extends Terminal("Variables") //used in axioms file...
private object PROBLEM_BLOCK extends Terminal("Problem")
private object TACTIC_BLOCK extends Terminal("Tactic")
//@todo the following R, B, T, P etc should be lexed as identifiers. Adapt code to make them disappear.
//@todo the following should all be removed or at most used as val REAL = Terminal("R")
private object REAL extends Terminal("$$$R")
private object BOOL extends Terminal("$$$B")
//Is there any reason we parse a bunch of stuff just to throw it away? Are these suppose to be in our sort heirarchy...?
private object TERM extends Terminal("$$$T")
private object PROGRAM extends Terminal("$$$P")
private object CP extends Terminal("$$$CP")
private object MFORMULA extends Terminal("$$F")

///////////
// Section: Terminal signals for extended lemma files.
///////////
private object SEQUENT_BEGIN extends Terminal("Sequent")  {
  override def regexp: Regex = """Sequent""".r
}
private object TURNSTILE extends Terminal("==>") {
  override def regexp: Regex = """==>""".r
}
private object FORMULA_BEGIN extends Terminal("Formula") {
  override def regexp: Regex = """Formula""".r
}

///////////
// Section: Terminal signals for tool files.
///////////
private object LEMMA_BEGIN extends Terminal("Lemma") {
  override def regexp: Regex = """Lemma""".r
}
private object TOOL_BEGIN extends Terminal("Tool") {
  override def regexp: Regex = """Tool""".r
}
private object HASH_BEGIN extends Terminal("Hash") {
  override def regexp: Regex = """Hash""".r
}
private case class TOOL_VALUE(var s: String) extends Terminal("<string>") {
  override def regexp: Regex = TOOL_VALUE_PAT.regexp
}
private object TOOL_VALUE_PAT {
  // values are nested into quadruple ", because they can contain single, double, or triple " themselves (arbitrary Scala code)
  def regexp: Regex = "\"{4}(([^\"]|\"(?!\"\"\")|\"\"(?!\"\")|\"\"\"(?!\"))*)\"{4}".r
//  def regexp = "\"([^\"]*)\"".r
  val startPattern: Regex = ("^" + regexp.pattern.pattern).r
}

private object SHARED_DEFINITIONS_BEGIN extends Terminal("SharedDefinitions") {}

private case class ARCHIVE_ENTRY_BEGIN(name: String) extends Terminal("ArchiveEntry|Lemma|Theorem|Exercise") {
  override def toString: String = name
  override def regexp: Regex = ARCHIVE_ENTRY_BEGIN.regexp
}
private object ARCHIVE_ENTRY_BEGIN {
  def regexp: Regex = "(ArchiveEntry|Lemma|Theorem|Exercise)".r
  val startPattern: Regex = ("^" + regexp.pattern.pattern).r
}

///////////
// Section: Terminal signals for tactics.
///////////
private object BACKTICK extends Terminal("`") {}

/**
 * Lexer for KeYmaera X turns string into list of tokens.
  *
  * @author Andre Platzer
 * @author nfulton
 */
object KeYmaeraXLexer extends (String => List[Token]) with Logging {
  /** Lexer's token stream with first token at head. */
  type TokenStream = List[Token]

  private val whitespace = """^(\s+)""".r
  private val newline = """(?s)(^\n)""".r //@todo use something more portable.
  private val comment = """(?s)(^/\*[\s\S]*?\*/)""".r

  /** Normalize all new lines in input to a s*/
  def normalizeNewlines(input: String): String = input.replace("\r\n", "\n").replace("\r", "\n").replaceAll(" *\n", "\n")
  /**
   * The lexer has multiple modes for the different sorts of files that are supported by KeYmaeraX.
   * The lexer will disallow non-expression symbols from occuring when the lexer is in expression mode.
   * This also ensures that reserved symbols are never used as function names.
    *
    * @param input The string to lex.
   * @param mode The lexer mode.
   * @return A stream of symbols corresponding to input.
   */
  //@todo performance bottleneck
  def inMode(input: String, mode: LexerMode): KeYmaeraXLexer.TokenStream = {
    val correctedInput = normalizeNewlines(input)
    logger.debug("LEX: " + correctedInput)
    val output = lex(correctedInput, SuffixRegion(1,1), mode)
    require(!output.exists(x => x.tok == ANYTHING), "output should not contain ??")
    require(output.last.tok.equals(EOF), "Expected EOF but found " + output.last.tok)
    output
  }

  def apply(input: String): KeYmaeraXLexer.TokenStream = inMode(input, ExpressionMode)

  /**
   * The lexer.
    *
    * @todo optimize
   * @param input The input to lex.
   * @param inputLocation The position of the input (e.g., wrt a source file).
   * @param mode The mode of the lexer.
   * @return A token stream.
   */
//  //@todo //@tailrec
//  private def lex(input: String, inputLocation:Location, mode: LexerMode): TokenStream =
//    if(input.trim.length == 0) {
//      List(Token(EOF))
//    }
//    else {
//      findNextToken(input, inputLocation, mode) match {
//        case Some((nextInput, token, nextLoc)) =>
//          //if (DEBUG) print(token)
//          token +: lex(nextInput, nextLoc, mode)
//        case None => List(Token(EOF)) //note: This case can happen if the input is e.g. only a comment or only whitespace.
//      }
//    }

  private def lex(input: String, inputLocation:Location, mode: LexerMode): TokenStream = {
    var remaining: String = input
    var loc: Location = inputLocation
    val output: scala.collection.mutable.ListBuffer[Token] = scala.collection.mutable.ListBuffer.empty
    while (!remaining.isEmpty) {
      findNextToken(remaining, loc, mode) match {
        case Some((nextInput, token, nextLoc)) =>
          output.append(token)
          remaining = nextInput
          loc = nextLoc
        case None => //note: This case can happen if the input is e.g. only a comment or only whitespace.
          output.append(Token(EOF, loc))
          return replaceAnything(output).to
      }
    }
    output.append(Token(EOF, loc))
    replaceAnything(output).to
  }

  /** Replace all instances of LPAREN,ANYTHING,RPAREN with LBANANA,RBANANA. */
  private def replaceAnything(output: scala.collection.mutable.ListBuffer[Token]): scala.collection.mutable.ListBuffer[Token] = {
    output.find(x => x.tok == ANYTHING) match {
      case None => output
      case Some(anyTok) =>
        val pos = output.indexOf(anyTok)
        replaceAnything(output.patch(pos-1, Token(LBANANA, anyTok.loc) :: Token(RBANANA, anyTok.loc) :: Nil, 3))
    }
  }

  /**
    *
    * @param cols Number of columns to move cursor.
    * @param terminal terminal to generate a token for.
    * @param location Current location.
    * @return Return value of findNextToken
    */
  private def consumeColumns(s: String, cols: Int, terminal: Terminal, location: Location) : Option[(String, Token, Location)] = {
    assert(cols > 0, "Cannot move cursor less than 1 columns.")
    Some((
      s.substring(cols),
      Token(terminal, spanningRegion(location, cols-1)),
      suffixOf(location, cols)))
  }

  private def consumeTerminalLength(s: String, terminal: Terminal, location: Location): Option[(String, Token, Location)] =
    consumeColumns(s, terminal.img.length, terminal, location)

  private def consumeUnicodeTerminalLength(s: String, terminal: Terminal, location: Location, replacementTerminal: Terminal): Option[(String, Token, Location)] = {
    consumeColumns(s, terminal.img.length, terminal, location).map({ case (st, t, l) => (st, Token(replacementTerminal, t.loc), l) })
  }

  private val lexers: Seq[(Regex, (String, Location, LexerMode, String) => Either[(String, Location, LexerMode),Option[(String, Token, Location)]])] = Seq(
    //update location if we encounter whitespace/comments.
    comment -> ((s: String, loc: Location, mode: LexerMode, theComment: String) => {
      val comment = s.substring(0, theComment.length)
      val lastLineCol = comment.lines.toList.last.length //column of last line.
      val lineCount = comment.lines.length
      Left((s.substring(theComment.length), loc match {
        case UnknownLocation       => UnknownLocation
        case Region(sl, _, el, ec) => Region(sl + lineCount - 1, lastLineCol, el, ec)
        case SuffixRegion(sl, sc)  => SuffixRegion(sl + lineCount - 1, sc + theComment.length)
      }, mode)) }),
    newline -> ((s: String, loc: Location, mode: LexerMode, _) =>
      Left((s.tail, loc match {
        case UnknownLocation       => UnknownLocation
        case Region(sl, _, el, ec) => Region(sl+1,1,el,ec)
        case SuffixRegion(sl, _)   => SuffixRegion(sl+1, 1)
      }, mode))),
    whitespace -> ((s: String, loc: Location, mode: LexerMode, spaces: String) =>
      Left((s.substring(spaces.length), loc match {
        case UnknownLocation        => UnknownLocation
        case Region(sl, sc, el, ec) => Region(sl, sc+spaces.length, el, ec)
        case SuffixRegion(sl, sc)   => SuffixRegion(sl, sc+ spaces.length)
      }, mode))),
    //Lemma file cases
    LEMMA_BEGIN.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case LemmaFileMode => Right(consumeTerminalLength(s, LEMMA_BEGIN, loc))
      case ProblemFileMode => Right(consumeColumns(s, LEMMA_BEGIN.img.length, ARCHIVE_ENTRY_BEGIN("Lemma"), loc))
      case _ => throw new Exception("Encountered ``Lemma`` in non-lemma lexing mode.")
    }),
    TOOL_BEGIN.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case LemmaFileMode => Right(consumeTerminalLength(s, TOOL_BEGIN, loc))
      case _ => throw new Exception("Encountered ``Tool`` in non-lemma lexing mode.")
    }),
    HASH_BEGIN.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case LemmaFileMode => Right(consumeTerminalLength(s, HASH_BEGIN, loc))
      case _ => throw new Exception("Encountered ``Tool`` in non-lemma lexing mode.")
    }),
    SEQUENT_BEGIN.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case LemmaFileMode => Right(consumeTerminalLength(s, SEQUENT_BEGIN, loc))
      case _ => throw new Exception("Encountered ``Sequent`` in a non-lemma file.")
    }),
    TURNSTILE.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case LemmaFileMode => Right(consumeTerminalLength(s, TURNSTILE, loc))
      case _ => throw new Exception("Encountered a turnstile symbol ==> in a non-lemma file.")
    }),
    FORMULA_BEGIN.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case LemmaFileMode => Right(consumeTerminalLength(s, FORMULA_BEGIN, loc))
      case _ => throw new Exception("Encountered a formula begin symbol (Formula:) in a non-lemma file.")
    }),
    DOT.startPattern -> ((s: String, loc: Location, _: LexerMode, dot: String) => { val (_, idx) = splitName(dot); Right(consumeTerminalLength(s, DOT(idx), loc)) }),
    // File cases
    PERIOD.startPattern -> ((s: String, loc: Location, _: LexerMode, _) => Right(consumeTerminalLength(s, PERIOD, loc))), //swapOutFor(consumeTerminalLength(PERIOD, loc), DOT)
    ARCHIVE_ENTRY_BEGIN.startPattern -> ((s: String, loc: Location, mode: LexerMode, kind: String) => mode match {
      case ProblemFileMode => Right(consumeColumns(s, kind.length, ARCHIVE_ENTRY_BEGIN(kind), loc))
      case LemmaFileMode if kind == "Lemma" => Right(consumeTerminalLength(s, LEMMA_BEGIN, loc))
      case _ => throw new Exception("Encountered ``" + ARCHIVE_ENTRY_BEGIN(kind).img + "`` in non-problem file lexing mode.")
    }),
    FUNCTIONS_BLOCK.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case AxiomFileMode | ProblemFileMode | LemmaFileMode => Right(consumeTerminalLength(s, FUNCTIONS_BLOCK, loc))
      case _ => throw new Exception("Functions. should only occur when processing files.")
    }),
    DEFINITIONS_BLOCK.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case AxiomFileMode | ProblemFileMode | LemmaFileMode => Right(consumeTerminalLength(s, DEFINITIONS_BLOCK, loc))
      case _ => throw new Exception("Definitions. should only occur when processing files.")
    }),
    PROGRAM_VARIABLES_BLOCK.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case AxiomFileMode | ProblemFileMode | LemmaFileMode => Right(consumeTerminalLength(s, PROGRAM_VARIABLES_BLOCK, loc))
      case _ => throw new Exception("ProgramVariables. should only occur when processing files.")
    }),
    VARIABLES_BLOCK.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case AxiomFileMode | ProblemFileMode | LemmaFileMode => Right(consumeTerminalLength(s, VARIABLES_BLOCK, loc))
      case _ => throw new Exception("Variables. should only occur when processing files.")
    }),
    BOOL.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case AxiomFileMode | ProblemFileMode | LemmaFileMode => Right(consumeTerminalLength(s, BOOL, loc))
      case _ => throw new Exception("Bool symbol declaration should only occur when processing files.")
    }),
    REAL.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case AxiomFileMode | ProblemFileMode | LemmaFileMode => Right(consumeTerminalLength(s,REAL, loc))
      case _ => throw new Exception("Real symbol declaration should only occur when processing files.")
    }),
    TERM.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case AxiomFileMode | ProblemFileMode | LemmaFileMode => Right(consumeTerminalLength(s, TERM, loc))
      case _ => throw new Exception("Term symbol declaration should only occur when processing files.")
    }),
    PROGRAM.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case AxiomFileMode | ProblemFileMode | LemmaFileMode => Right(consumeTerminalLength(s, PROGRAM, loc))
      case _ => throw new Exception("Program symbol declaration should only occur when processing files.")
    }),
    CP.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case AxiomFileMode | ProblemFileMode | LemmaFileMode => Right(consumeTerminalLength(s, CP, loc))
      case _ => throw new Exception("CP symbol declaration should only occur when processing files.")
    }),
    MFORMULA.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case AxiomFileMode | ProblemFileMode | LemmaFileMode => Right(consumeTerminalLength(s, MFORMULA, loc))
      case _ => throw new Exception("MFORMULA symbol declaration should only occur when processing files.")
    }),
    //.kyx file cases
    PROBLEM_BLOCK.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case AxiomFileMode | ProblemFileMode => Right(consumeTerminalLength(s, PROBLEM_BLOCK, loc))
      case _ => throw new Exception("Problem./End. sections should only occur when processing .kyx files.")
    }),
    TACTIC_BLOCK.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case AxiomFileMode | ProblemFileMode => Right(consumeTerminalLength(s, TACTIC_BLOCK, loc))
      case _ => throw new Exception("Tactic./End. sections should only occur when processing .kyx files.")
    }),
    BACKTICK.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case ProblemFileMode => Right(consumeTerminalLength(s, BACKTICK, loc))
      case _ => throw new Exception("Backtick ` should only occur when processing .kyx files.")
    }),
    SHARED_DEFINITIONS_BEGIN.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case ProblemFileMode => Right(consumeTerminalLength(s, SHARED_DEFINITIONS_BEGIN, loc))
      case _ => throw new Exception("SharedDefinitions./End. sections should only occur when processing .kyx files.")
    }),
    //Lemma file cases (2)
    TOOL_VALUE_PAT.startPattern -> ((s: String, loc: Location, mode: LexerMode, str: String) => mode match { //@note must be before DOUBLE_QUOTES_STRING
      case LemmaFileMode =>
        Right(consumeColumns(s, str.length, TOOL_VALUE(str.stripPrefix("\"\"\"\"").stripSuffix("\"\"\"\"")), loc))
      case _ => throw new Exception("Encountered delimited string in non-lemma lexing mode.")
    }),
    //Axiom file cases
    AXIOM_BEGIN.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case AxiomFileMode => Right(consumeTerminalLength(s, AXIOM_BEGIN, loc))
      case _ => throw new Exception("Encountered ``Axiom.`` in non-axiom lexing mode.")
    }),
    END_BLOCK.startPattern -> ((s: String, loc: Location, mode: LexerMode, _) => mode match {
      case AxiomFileMode | ProblemFileMode | LemmaFileMode => Right(consumeTerminalLength(s, END_BLOCK, loc))
      case _ => throw new Exception("Encountered ``Axiom.`` in non-axiom lexing mode.")
    }),
    DOUBLE_QUOTES_STRING_PAT.startPattern -> ((s: String, loc: Location, mode: LexerMode, str: String) => mode match {
      case AxiomFileMode | LemmaFileMode | ProblemFileMode =>
        Right(consumeColumns(s, str.length, DOUBLE_QUOTES_STRING(str.stripPrefix("\"").stripSuffix("\"")), loc))
      case _ => throw new Exception("Encountered delimited string in non-axiom lexing mode.")
    }),
    //These have to come before LBOX,RBOX because otherwise <= becopmes LDIA, EQUALS
    GREATEREQ.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, GREATEREQ, loc))),
    GREATEREQ_UNICODE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeUnicodeTerminalLength(s, GREATEREQ_UNICODE, loc, GREATEREQ))),
    LESSEQ.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, LESSEQ, loc))),
    LESSEQ_UNICODE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeUnicodeTerminalLength(s, LESSEQ_UNICODE, loc, LESSEQ))),
    NOTEQ.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, NOTEQ, loc))),

    LBANANA.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, LBANANA, loc))),
    RBANANA.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, RBANANA, loc))),
    LPAREN.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, LPAREN, loc))),
    RPAREN.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, RPAREN, loc))),
    LBOX.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, LBOX, loc))),
    RBOX.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, RBOX, loc))),
    LBARB.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, LBARB, loc))),
    RBARB.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, RBARB, loc))),
    LBRACE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, LBRACE, loc))),
    RBRACE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, RBRACE, loc))),

    COMMA.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, COMMA, loc))),
    //
    IF.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, IF, loc))),
    ELSE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, ELSE, loc))),
    //This has to come before PLUS because otherwise ++ because PLUS,PLUS instead of CHOICE.
    CHOICE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, CHOICE, loc))),
    //This has to come before MINUS because otherwise -- because MINUS,MINUS instead of DCHOICE.
    //@TODO case DCHOICE.startPattern(_*) => consumeTerminalLength(s, DCHOICE, loc)
    //@note must be before POWER
    DUAL.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, DUAL, loc))),
    //
    PRIME.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, PRIME, loc))),
    SLASH.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, SLASH, loc))),
    POWER.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, POWER, loc))),
    STAR.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, STAR, loc))),
    PLUS.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, PLUS, loc))),
    //
    AMP.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, AMP, loc))),
    AND_UNICODE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeUnicodeTerminalLength(s, AND_UNICODE, loc, AMP))),
    NOT.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, NOT, loc))),
    DOUBLE_PIPE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, DOUBLE_PIPE, loc))),
    OR.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, OR, loc))),
    OR_UNICODE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeUnicodeTerminalLength(s, OR_UNICODE, loc, OR))),
    EQUIV.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, EQUIV, loc))),
    EQUIV_UNICODE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeUnicodeTerminalLength(s, EQUIV_UNICODE, loc, EQUIV))),
    IMPLY.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, IMPLY, loc))),
    IMPLY_UNICODE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeUnicodeTerminalLength(s, IMPLY_UNICODE, loc, IMPLY))),
    REVIMPLY.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, REVIMPLY, loc))),
    REVIMPLY_UNICODE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeUnicodeTerminalLength(s, REVIMPLY_UNICODE, loc, REVIMPLY))),
    //
    FORALL.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, FORALL, loc))),
    FORALL_UNICODE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeUnicodeTerminalLength(s, FORALL_UNICODE, loc, FORALL))),
    EXISTS.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, EXISTS, loc))),
    EXISTS_UNICODE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeUnicodeTerminalLength(s, EXISTS_UNICODE, loc, EXISTS))),
    //
    EQ.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, EQ, loc))),
    UNEQUAL_UNICODE.startPattern -> ((s: String, loc: Location, _, _) => ???),
    TRUE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, TRUE, loc))),
    FALSE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, FALSE, loc))),
    //
    ANYTHING.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, ANYTHING, loc))), //@note this token is stripped out and replaced with (! !) in [[fin`dNextToken]].
    //
    ASSIGNANY.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, ASSIGNANY, loc))),
    ASSIGN.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, ASSIGN, loc))),
    TEST.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, TEST, loc))),
    SEMI.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, SEMI, loc))),
    //
    PLACE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, PLACE, loc))),
    PSEUDO.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, PSEUDO, loc))),
    //
    INVARIANT.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, INVARIANT, loc))),
    //
    IDENT.startPattern -> ((s: String, loc: Location, _, name: String) => {
      val (n, idx) = splitName(name)
      Right(consumeTerminalLength(s, IDENT(n, idx), loc))
    }),
    NUMBER.startPattern -> ((s: String, loc: Location, _, n: String) => Right(consumeTerminalLength(s, NUMBER(n), loc))),
    //@NOTE Minus has to come after number so that -9 is lexed as Number(-9) instead of as Minus::Number(9).
    //@NOTE Yet NUMBER has been demoted not to feature - signs, so it has become irrelevant for now.
    MINUS.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, MINUS, loc))),
    //
    LDIA.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, LDIA, loc))),
    RDIA.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, RDIA, loc))),
    //
    PRG_DEF.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, PRG_DEF, loc))),
    COLON.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, COLON, loc))),
    //
    EXERCISE_PLACEHOLDER.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, EXERCISE_PLACEHOLDER, loc))),
    //
    TILDE.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, TILDE, loc))),
    BACKSLASH.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, BACKSLASH, loc))),
    QUOTATION_MARK.startPattern -> ((s: String, loc: Location, _, _) => Right(consumeTerminalLength(s, QUOTATION_MARK, loc)))
  )

  /**
   * Finds the next token in a string.
    *
    * @todo Untested correctness condition: If a token's regex pattern contains another's, then the more restrictive token is processed first in the massive if/else.
    * @param s The string to process.
   * @param loc The location of s.
   * @param mode The mode of the lexer.
   * @return A triple containing:
   *          _1: the next token,
   *          _2: the portion of the string following the next token,
   *          _3: The location of the beginning of the next string.
   */
  @tailrec
  private def findNextToken(s: String, loc: Location, mode: LexerMode): Option[(String, Token, Location)] = {
    if (s.isEmpty) {
      None
    } else {
      val lexPrefix = lexers.view.map({ case (r,lexer) => r.findPrefixOf(s).map(lexer(s, loc, mode, _)) }).find(_.isDefined).flatten
      lexPrefix match {
        case Some(Left(lexed)) => findNextToken(lexed._1, lexed._2, lexed._3)
        case Some(Right(lexed)) => lexed
        case None => throw LexException(loc.begin + " Lexer does not recognize input at " + loc + " in `\n" + s +"\n` beginning with character `" + s(0) + "`=" + s(0).getNumericValue, loc).inInput(s)
      }
    }
  }

  /**
   * Returns the region containing everything between the starting position of the current location
   * location and the indicated offset of from the starting positiong of the current location,
   * inclusive.
    *
    * @param location Current location
   * @param endColOffset Column offset of the region
   * @return The region spanning from the start of ``location" to the offset from the start of ``location".
   */
  private def spanningRegion(location: Location, endColOffset: Int) =
    location match {
      case UnknownLocation      => UnknownLocation
      case Region(sl, sc, _, _) => Region(sl, sc, sl, sc + endColOffset)
      case SuffixRegion(sl, sc) => Region(sl, sc, sl, sc + endColOffset)
    }

  /**
   *
   * @param location Current location
   * @param colOffset Number of columns to chop off from the starting position of location.
   * @return A region containing all of location except the indicated columns in the initial row.
   *         I.e., the colOffset-suffix of location.
   */
  private def suffixOf(location: Location, colOffset: Int) : Location =
    location match {
      case UnknownLocation        => UnknownLocation
      case Region(sl, sc, el, ec) => Region(sl, sc + colOffset, el, ec)
      case SuffixRegion(sl, sc)   => SuffixRegion(sl, sc + colOffset)
    }

  private def splitName(s : String) : (String, Option[Int]) =
    if(s.contains("_") && !s.endsWith("_")) {
      // a_b_2 ==> "a_b", 2
      val (namePart, idxPart) = {
        val finalUnderscoreIdx = s.lastIndexOf("_")
        ( s.substring(0, finalUnderscoreIdx),
          s.substring(finalUnderscoreIdx + 1, s.length) )
      }

      val idx = Some(Integer.parseInt(idxPart))

      (namePart, idx)
    } else (s, None)
}

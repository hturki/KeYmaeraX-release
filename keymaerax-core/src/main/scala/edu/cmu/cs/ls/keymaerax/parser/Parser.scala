/**
* Copyright (c) Carnegie Mellon University.
* See LICENSE.txt for the conditions of this license.
*/
/**
 * Differential Dynamic Logic parser for concrete KeYmaera X notation.
 * @author Andre Platzer
 * @see "Andre Platzer. A uniform substitution calculus for differential dynamic logic.  arXiv 1503.01981, 2015."
 */
package edu.cmu.cs.ls.keymaerax.parser

import edu.cmu.cs.ls.keymaerax.core._

/**
 * Parser interface for KeYmaera X.
 * Provides a parser to read string and file inputs with differential dynamic logic.
 * A parser is essentially a function from input string to differential dynamic logic [[edu.cmu.cs.ls.keymaerax.core.Expression expressions]].
 * {{{
 *     Parser: String => Expression
 * }}}
 * @author Andre Platzer
 */
trait Parser extends (String => Expression) {
  /** Parse the input string in the concrete syntax as a differential dynamic logic expression */
  def apply(input: String): Expression

  /** Parse the input string in the concrete syntax as a differential dynamic logic term */
  val termParser: (String => Term)

  /** Parse the input string in the concrete syntax as a differential dynamic logic formula */
  val formulaParser: (String => Formula)

  /** Parse the input string in the concrete syntax as a differential dynamic logic program */
  val programParser: (String => Program)

  /** Parse the input string in the concrete syntax as a differential dynamic logic differential program */
  val differentialProgramParser: (String => DifferentialProgram)

  val channelsParser: (String => Channels)

  /** A pretty-printer that can write the output that this parser reads */
  val printer: PrettyPrinter

}

trait TokenParser {
  /** Lexer's token stream with first token at head. */
  type TokenStream = KeYmaeraXLexer.TokenStream

  /** Parse the input tokens in the concrete syntax as a differential dynamic logic expression */
  def parse(input: TokenStream): Expression

  /** Parse the input tokens in the concrete syntax as a differential dynamic logic term */
  val termTokenParser: (TokenStream => Term)

  /** Parse the input tokens in the concrete syntax as a differential dynamic logic formula */
  val formulaTokenParser: (TokenStream => Formula)

  /** Parse the input tokens in the concrete syntax as a differential dynamic logic program */
  val programTokenParser: (TokenStream => Program)

  /** Parse the input tokens in the concrete syntax as a differential dynamic logic differential program */
  val differentialProgramTokenParser: (TokenStream => DifferentialProgram)
}

object ParserHelper {
  /** Returns (approximately!) 's' without any Byte Order Mark.
    * @todo DANGER hack: actually just returns a version of 's' that is converted into ASCII (replacing non-ASCII bytes with '?') and then stripping all the '?' from the prefix of the string.
    * In particular, ay also remove completely valid '?' characters if that's what's at the beginning of the string. */
  def removeBOM(s : String): String = {
    val asASCII = new String(s.getBytes("US-ASCII"), "US-ASCII")
    //Find the length of a uniformly-'?' prefix on asSCII, then remove that many characters from s.
    //This preserves other unicode characters is s, only removing the potential BOM.
    //@todo be less indiscriminate and only remove these guys: https://en.wikipedia.org/wiki/Byte_order_mark#Representations_of_byte_order_marks_by_encoding
    var toRemove = 0
    while(asASCII(toRemove) == '?')
      toRemove += 1
    s.substring(toRemove)
  }
}

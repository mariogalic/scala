/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2006-2009, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id:$

package scala.util.parsing.combinator

import scala.collection.mutable._
import scala.util.parsing.combinator._
import scala.util.parsing.input.Position
import scala.util.parsing.input.Reader

/**
 *  <p>
 *    <code>PackratParsers</code> is a component that extends the parser combinators
 *    provided by <a href="Parsers.html"><code>Parsers</code></a> with a memoization facility
 *    (``Packrat Parsing'').
 *  </p>
 *  <p>
 *    Packrat Parsing is a technique for implementing backtracking, recursive-descent parsers, with the
 *    advantage that it guarantees unlimited lookahead and a linear parse time. Using this technique,
 *    left recursive grammars can also be accepted.
 *  </p>
 *  <p>
 *    Using <code>PackratParsers</code> is very similar to using <code>Parsers</code>:
 *  <ul>
 *    <li> any class/trait that extends <code>Parsers</code> (directly or through a subclass) can
 *         mix in <code>PackratParsers</code>. Example:
 *         <code>object MyGrammar extends StandardTokenParsers with PackratParsers </code>
 *    <li> each grammar production previously declared as a <code>def</code> without formal parameters
 *         becomes a <code>lazy val</code>, and its type is changed from <code>Parser[Elem]</code>
 *         to <code>PackratParser[Elem]</code>. So, for example, <code>def production: Parser[Int] = {...}</code>
 *         becomes <code>lazy val production: PackratParser[Int] = {...}</code>
 *    <li> Important: using <code>PackratParser</code>s is not an ``all or nothing'' decision. They
 *         can be free mixed with regular <code>Parser</code>s in a single grammar.
 *  </ul>
 *  </p>
 *  <p>
 *    Cached parse results are attached to the <i>input</i>, not the grammar.
 *    Therefore, <code>PackratsParser</code>s require a <code>PackratReader</code> as input, which
 *    adds memoization to an underlying <code>Reader</code>. Programmers can create <code>PackratReader</code>
 *    objects either manually, as in <code>production(new PackratReader(new lexical.Scanner("input")))</code>,
 *    but the common way should be to rely on the combinator <code>phrase</code> to wrap a given
 *    input with a <code>PackratReader</code> if the input is not one itself.
 *  </p>
 *
 * @see Bryan Ford: "Packrat Parsing: Simple, Powerful, Lazy, Linear Time." ICFP'02
 * @see Alessandro Warth, James R. Douglass, Todd Millstein: "Packrat Parsers Can Support Left Recursion." PEPM'08
 *
 * @since 2.8
 * @author Manohar Jonnalagedda, Tiark Rompf
 */

trait PackratParsers extends Parsers {

  //type Input = PackratReader[Elem]

  /**
   * A specialized <code>Reader</code> class that wraps an underlying <code>Reader</code>
   * and provides memoization of parse results.
   */
  class PackratReader[+T](underlying: Reader[T]) extends Reader[T]  { outer =>

    /*
     * caching of intermediate parse results and information about recursion
     */

    private[PackratParsers] val cache: HashMap[(Parser[_], Position), MemoEntry[_]] = HashMap.empty


    private[PackratParsers] def getFromCache[T](p: Parser[T]): Option[MemoEntry[T]] = {
      cache.get((p, pos)).asInstanceOf[Option[MemoEntry[T]]]
    }

    private[PackratParsers] def updateCacheAndGet[T](p: Parser[T], w: MemoEntry[T]): MemoEntry[T] = {
      cache.put((p, pos),w)
      w
    }

    /* a cache for storing parser heads: allows to know which parser is involved
       in a recursion*/
    private[PackratParsers] val recursionHeads: HashMap[Position, Head] = HashMap.empty

    //a stack that keeps a list of all involved rules
    private[PackratParsers] var lrStack: List[LR] = Nil




    override def source: java.lang.CharSequence = underlying.source
    override def offset: Int = underlying.offset

    def first: T = underlying.first
    def rest: Reader[T] = new PackratReader(underlying.rest) {
      override private[PackratParsers] val cache = outer.cache
      override private[PackratParsers] val recursionHeads = outer.recursionHeads
      lrStack = outer.lrStack
    }

    def pos: Position = underlying.pos
    def atEnd: Boolean = underlying.atEnd
  }


  /**
   *  <p>
   *    A parser generator delimiting whole phrases (i.e. programs).
   *  </p>
   *  <p>
   *    Overridden to make sure any input passed to the argument parser
   *    is wrapped in a <code>PackratReader</code>.
   *  </p>
   */
  override def phrase[T](p: Parser[T]) = {
    val q = super.phrase(p)
    new PackratParser[T] {
      def apply(in: Input) = in match {
        case in: PackratReader[_] => q(in)
        case in => q(new PackratReader(in))
      }
    }
  }


  private def getPosFromResult(r: ParseResult[_]): Position = r.next.pos

  // auxiliary data structures

  private case class MemoEntry[+T](var r: Either[LR,ParseResult[_]]){
    def getResult: ParseResult[T] = r match {
      case Left(LR(res,_,_)) => res
      case Right(res) => res
    }
  }

  private case class LR(var seed: ParseResult[_], var rule: Parser[_], var head: Option[Head]){
    def getPos: Position = getPosFromResult(seed)
  }

  private case class Head(var headParser: Parser[_], var involvedSet: List[Parser[_]], var evalSet: List[Parser[_]]){
    def getHead = headParser
  }

  /**
   * The root class of packrat parsers.
   */
  abstract class PackratParser[+T] extends super.Parser[T]

  /**
   * Implicitly convert a parser to a packrat parser.
   * The conversion is triggered by giving the appropriate target type:
   * val myParser: PackratParser[MyResult] = aParser
   */
  implicit def parser2packrat[T](p: => super.Parser[T]): PackratParser[T] = {
    lazy val q = p
    memo(super.Parser {in => q(in)})
  }


  /*
   * An unspecified function that is called when a packrat reader is applied.
   * It verifies whether we are in the process of growing a parse or not.
   * In the former case, it makes sure that rules involved in the recursion are evaluated.
   * It also prevents non-involved rules from getting evaluated further
   */

  private def recall(p: super.Parser[_], in: PackratReader[Elem]): Option[MemoEntry[_]] = {
    val cached = in.getFromCache(p)
    val head = in.recursionHeads.get(in.pos)

    head match {
      case None => /*no heads*/ cached
      case Some(h@Head(hp, involved, evalSet)) => {
        //heads found
        if(cached == None && !(hp::involved contains p)) {
          //Nothing in the cache, and p is not involved
          return Some(MemoEntry(Right(Failure("dummy ",in))))
        }
        if(evalSet contains p){
          //something in cache, and p is in the evalSet
          //remove the rule from the evalSet of the Head
          h.evalSet = h.evalSet.filterNot(_==p)
          val tempRes = p(in)
          //we know that cached has an entry here
          val tempEntry: MemoEntry[_] = cached.get // match {case Some(x: MemoEntry[_]) => x}
          //cache is modified
          tempEntry.r = Right(tempRes)
        }
        cached
      }
    }
  }

  /*
   * setting up the left-recursion. We have the LR for the rule head
   * we modify the involvedSets of all LRs in the stack, till we see
   * the current parser again
   */
  private def setupLR(p: Parser[_], in: PackratReader[_], recDetect: LR): Unit = {
    if(recDetect.head == None) recDetect.head = Some(Head(p, Nil, Nil))

    in.lrStack.takeWhile(_.rule != p).foreach {x =>
      x.head = recDetect.head
      recDetect.head.map(h => h.involvedSet = x.rule::h.involvedSet)
    }
  }

  /*
   * growing, if needed the recursion
   * check whether the parser we are growing is the head of the rule.
   * Not => no grow
   */

  /*
   * Once the result of the recall function is known, if it is nil, then we need to store a dummy
failure into the cache (much like in the previous listings) and compute the future parse. If it
is not, however, this means we have detected a recursion, and we use the setupLR function
to update each parser involved in the recursion.
   */

  private def lrAnswer[T](p: Parser[T], in: PackratReader[Elem], growable: LR): ParseResult[T] = growable match {
    //growable will always be having a head, we can't enter lrAnswer otherwise
    case LR(seed ,rule, Some(head)) =>
      if(head.getHead != p) /*not head rule, so not growing*/ seed
      else {
        in.updateCacheAndGet(p, MemoEntry(Right[LR, ParseResult[T]](seed)))
        seed match {
          case f@Failure(_,_) => f
          case e@Error(_,_) => e
          case s@Success(_,_) => /*growing*/ grow(p, in, head)
        }
      }
    case _=> throw new Exception("lrAnswer with no head !!")
  }

  //p here should be strict (cannot be non-strict) !!
  //failing left-recursive grammars: This is done by simply storing a failure if nothing is found

  /**
   * Explicitly convert a given parser to a memoizing packrat parser.
   * In most cases, client code should avoid calling <code>memo</code> directly
   * and rely on implicit conversion instead.
   */
  def memo[T](p: super.Parser[T]): PackratParser[T] = {
    new PackratParser[T] {
      def apply(in: Input) = {
        /*
         * transformed reader
         */
        val inMem = in.asInstanceOf[PackratReader[Elem]]

        //look in the global cache if in a recursion
        val m = recall(p, inMem)
        m match {
          //nothing has been done due to recall
          case None =>
            val base = LR(Failure("Base Failure",in), p, None)
            inMem.lrStack = base::inMem.lrStack
            //cache base result
            inMem.updateCacheAndGet(p,MemoEntry(Left(base)))
            //parse the input
            val tempRes = p(in)
            //the base variable has passed equality tests with the cache
            inMem.lrStack = inMem.lrStack.tail
            //check whether base has changed, if yes, we will have a head
            base.head match {
              case None =>
                /*simple result*/
                inMem.updateCacheAndGet(p,MemoEntry(Right(tempRes)))
                tempRes
              case s@Some(_) =>
                /*non simple result*/
                base.seed = tempRes
                //the base variable has passed equality tests with the cache
                val res = lrAnswer(p, inMem, base)
                res
            }

          case Some(mEntry) => {
            //entry found in cache
            mEntry match {
              case MemoEntry(Left(recDetect)) => {
                setupLR(p, inMem, recDetect)
                //all setupLR does is change the heads of the recursions, so the seed will stay the same
                recDetect match {case LR(seed, _, _) => seed}
              }
              case MemoEntry(Right(res: ParseResult[T])) => res
            }
          }
        }
      }
    }
  }

  private def grow[T](p: super.Parser[T], rest: PackratReader[Elem], head: Head): ParseResult[T] = {
    //store the head into the recursionHeads
    rest.recursionHeads.put(rest.pos, head /*match {case Head(hp,involved,_) => Head(hp,involved,involved)}*/)
    val oldRes: ParseResult[T] = rest.getFromCache(p).get match {
      case MemoEntry(Right(x)) => x
      case _ => throw new Exception("impossible match")
    }

    //resetting the evalSet of the head of the recursion at each beginning of growth
    head.evalSet = head.involvedSet
    val tempRes = p(rest); tempRes match {
      case s@Success(_,_) =>
        if(getPosFromResult(oldRes) < getPosFromResult(tempRes)) {
          rest.updateCacheAndGet(p, MemoEntry(Right(s)))
          grow(p, rest, head)
        } else {
          //we're done with growing, we can remove data from recursion head
          rest.recursionHeads -= rest.pos
          rest.getFromCache(p).get match {
            case MemoEntry(Right(x: ParseResult[T])) => x
            case _ => throw new Exception("impossible match")
          }
        }
      case f =>
        rest.recursionHeads -= rest.pos
        /*rest.updateCacheAndGet(p, MemoEntry(Right(f)));*/oldRes
    }
  }
}

/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2005-2009, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$

package scala.actors

import scala.collection.mutable.{HashSet, Queue}
import scala.compat.Platform

import java.util.{Timer, TimerTask}

import java.util.concurrent.ExecutionException

/**
 * The <code>Actor</code> object provides functions for the definition of
 * actors, as well as actor operations, such as
 * <code>receive</code>, <code>react</code>, <code>reply</code>,
 * etc.
 *
 * @version 0.9.18
 * @author Philipp Haller
 */
object Actor {

  private[actors] val tl = new ThreadLocal[Actor]

  // timer thread runs as daemon
  private[actors] val timer = new Timer(true)

  /**
   * Returns the currently executing actor. Should be used instead
   * of <code>this</code> in all blocks of code executed by
   * actors.
   *
   * @return returns the currently executing actor.
   */
  def self: Actor = self(Scheduler)

  private[actors] def self(sched: IScheduler): Actor = {
    val s = tl.get
    if (s eq null) {
      val r = new ActorProxy(currentThread, sched)
      tl.set(r)
      r
    } else
      s
  }

  private def parentScheduler: IScheduler = {
    val s = tl.get
    if (s eq null) Scheduler else s.scheduler
  }

  /**
   * Resets an actor proxy associated with the current thread.
   * It replaces the implicit <code>ActorProxy</code> instance
   * of the current thread (if any) with a new instance.
   *
   * This permits to re-use the current thread as an actor
   * even if its <code>ActorProxy</code> has died for some reason.
   */
  def resetProxy {
    val a = tl.get
    if ((null ne a) && a.isInstanceOf[ActorProxy])
      tl.set(new ActorProxy(currentThread, parentScheduler))
  }

  /**
   * Removes any reference to an <code>Actor</code> instance
   * currently stored in thread-local storage.
   *
   * This allows to release references from threads that are
   * potentially long-running or being re-used (e.g. inside
   * a thread pool). Permanent references in thread-local storage
   * are a potential memory leak.
   */
  def clearSelf {
    tl.set(null)
  }

  /**
   * <p>This is a factory method for creating actors.</p>
   *
   * <p>The following example demonstrates its usage:</p>
   *
   * <pre>
   * import scala.actors.Actor._
   * ...
   * val a = actor {
   *   ...
   * }
   * </pre>
   *
   * @param  body  the code block to be executed by the newly created actor
   * @return       the newly created actor. Note that it is automatically started.
   */
  def actor(body: => Unit): Actor = {
    val a = new Actor {
      def act() = body
      override final val scheduler: IScheduler = parentScheduler
    }
    a.start()
    a
  }

  /**
   * <p>
   * This is a factory method for creating actors whose
   * body is defined using a <code>Responder</code>.
   * </p>
   *
   * <p>The following example demonstrates its usage:</p>
   *
   * <pre>
   * import scala.actors.Actor._
   * import Responder.exec
   * ...
   * val a = reactor {
   *   for {
   *     res <- b !! MyRequest;
   *     if exec(println("result: "+res))
   *   } yield {}
   * }
   * </pre>
   *
   * @param  body  the <code>Responder</code> to be executed by the newly created actor
   * @return       the newly created actor. Note that it is automatically started.
   */
  def reactor(body: => Responder[Unit]): Actor = {
    val a = new Actor {
      def act() {
        Responder.run(body)
      }
      override final val scheduler: IScheduler = parentScheduler
    }
    a.start()
    a
  }

  /**
   * Receives the next message from the mailbox of the current actor
   * <code>self</code>.
   */
  def ? : Any = self.?

  /**
   * Receives a message from the mailbox of
   * <code>self</code>. Blocks if no message matching any of the
   * cases of <code>f</code> can be received.
   *
   * @param  f a partial function specifying patterns and actions
   * @return   the result of processing the received message
   */
  def receive[A](f: PartialFunction[Any, A]): A =
    self.receive(f)

  /**
   * Receives a message from the mailbox of
   * <code>self</code>. Blocks at most <code>msec</code>
   * milliseconds if no message matching any of the cases of
   * <code>f</code> can be received. If no message could be
   * received the <code>TIMEOUT</code> action is executed if
   * specified.
   *
   * @param  msec the time span before timeout
   * @param  f    a partial function specifying patterns and actions
   * @return      the result of processing the received message
   */
  def receiveWithin[R](msec: Long)(f: PartialFunction[Any, R]): R =
    self.receiveWithin(msec)(f)

  /**
   * Lightweight variant of <code>receive</code>.
   *
   * Actions in <code>f</code> have to contain the rest of the
   * computation of <code>self</code>, as this method will never
   * return.
   *
   * @param  f a partial function specifying patterns and actions
   * @return   this function never returns
   */
  def react(f: PartialFunction[Any, Unit]): Nothing =
    self.react(f)

  /**
   * Lightweight variant of <code>receiveWithin</code>.
   *
   * Actions in <code>f</code> have to contain the rest of the
   * computation of <code>self</code>, as this method will never
   * return.
   *
   * @param  msec the time span before timeout
   * @param  f    a partial function specifying patterns and actions
   * @return      this function never returns
   */
  def reactWithin(msec: Long)(f: PartialFunction[Any, Unit]): Nothing =
    self.reactWithin(msec)(f)

  def eventloop(f: PartialFunction[Any, Unit]): Nothing =
    self.react(new RecursiveProxyHandler(self, f))

  private class RecursiveProxyHandler(a: Actor, f: PartialFunction[Any, Unit])
          extends PartialFunction[Any, Unit] {
    def isDefinedAt(m: Any): Boolean =
      true // events are immediately removed from the mailbox
    def apply(m: Any) {
      if (f.isDefinedAt(m)) f(m)
      a.react(this)
    }
  }

  /**
   * Returns the actor which sent the last received message.
   */
  def sender: OutputChannel[Any] = self.sender

  /**
   * Send <code>msg</code> to the actor waiting in a call to
   * <code>!?</code>.
   */
  def reply(msg: Any): Unit = self.reply(msg)

  /**
   * Send <code>()</code> to the actor waiting in a call to
   * <code>!?</code>.
   */
  def reply(): Unit = self.reply(())

  /**
   * Returns the number of messages in <code>self</code>'s mailbox
   *
   * @return the number of messages in <code>self</code>'s mailbox
   */
  def mailboxSize: Int = self.mailboxSize

  /**
   * <p>
   * Converts a synchronous event-based operation into
   * an asynchronous <code>Responder</code>.
   * </p>
   *
   * <p>The following example demonstrates its usage:</p>
   *
   * <pre>
   * val adder = reactor {
   *   for {
   *     _ <- respondOn(react) { case Add(a, b) => reply(a+b) }
   *   } yield {}
   * }
   * </pre>
   */
  def respondOn[A, B](fun: PartialFunction[A, Unit] => Nothing):
    PartialFunction[A, B] => Responder[B] =
      (caseBlock: PartialFunction[A, B]) => new Responder[B] {
        def respond(k: B => Unit) = fun(caseBlock andThen k)
      }

  private[actors] trait Body[a] {
    def andThen[b](other: => b): Unit
  }

  implicit def mkBody[a](body: => a) = new Body[a] {
    def andThen[b](other: => b): Unit = self.seq(body, other)
  }

  /**
   * Causes <code>self</code> to repeatedly execute
   * <code>body</code>.
   *
   * @param body the code block to be executed
   */
  def loop(body: => Unit): Unit = body andThen loop(body)

  /**
   * Causes <code>self</code> to repeatedly execute
   * <code>body</code> while the condition
   * <code>cond</code> is <code>true</code>.
   *
   * @param cond the condition to test
   * @param body the code block to be executed
   */
  def loopWhile(cond: => Boolean)(body: => Unit): Unit =
    if (cond) { body andThen loopWhile(cond)(body) }
    else continue

  /**
   * Links <code>self</code> to actor <code>to</code>.
   *
   * @param  to the actor to link to
   * @return
   */
  def link(to: AbstractActor): AbstractActor = self.link(to)

  /**
   * Links <code>self</code> to actor defined by <code>body</code>.
   *
   * @param body ...
   * @return     ...
   */
  def link(body: => Unit): Actor = self.link(body)

  /**
   * Unlinks <code>self</code> from actor <code>from</code>.
   *
   * @param from the actor to unlink from
   */
  def unlink(from: Actor): Unit = self.unlink(from)

  /**
   * <p>
   *   Terminates execution of <code>self</code> with the following
   *   effect on linked actors:
   * </p>
   * <p>
   *   For each linked actor <code>a</code> with
   *   <code>trapExit</code> set to <code>true</code>, send message
   *   <code>Exit(self, reason)</code> to <code>a</code>.
   * </p>
   * <p>
   *   For each linked actor <code>a</code> with
   *   <code>trapExit</code> set to <code>false</code> (default),
   *   call <code>a.exit(reason)</code> if
   *   <code>reason != 'normal</code>.
   * </p>
   */
  def exit(reason: AnyRef): Nothing = self.exit(reason)

  /**
   * <p>
   *   Terminates execution of <code>self</code> with the following
   *   effect on linked actors:
   * </p>
   * <p>
   *   For each linked actor <code>a</code> with
   *   <code>trapExit</code> set to <code>true</code>, send message
   *   <code>Exit(self, 'normal)</code> to <code>a</code>.
   * </p>
   */
  def exit(): Nothing = self.exit()

  def continue: Unit = throw new KillActorException
}

/**
 * <p>
 *   This class provides an implementation of event-based actors.
 *   The main ideas of our approach are explained in the two papers
 * </p>
 * <ul>
 *   <li>
 *     <a href="http://lampwww.epfl.ch/~odersky/papers/jmlc06.pdf">
 *     <span style="font-weight:bold; white-space:nowrap;">Event-Based
 *     Programming without Inversion of Control</span></a>,<br/>
 *     Philipp Haller and Martin Odersky, <i>Proc. JMLC 2006</i>, and
 *   </li>
 *   <li>
 *     <a href="http://lamp.epfl.ch/~phaller/doc/haller07coord.pdf">
 *     <span style="font-weight:bold; white-space:nowrap;">Actors that
 *     Unify Threads and Events</span></a>,<br/>
 *     Philipp Haller and Martin Odersky, <i>Proc. COORDINATION 2007</i>.
 *   </li>
 * </ul>
 *
 * @version 0.9.18
 * @author Philipp Haller
 */
@serializable
trait Actor extends AbstractActor {

  private var received: Option[Any] = None

  private val waitingForNone = (m: Any) => false

  /* Whenever this Actor executes on some thread, waitingFor is
   * guaranteed to be equal to waitingForNone.
   *
   * In other words, whenever waitingFor is not equal to
   * waitingForNone, this Actor is guaranteed not to execute on some
   * thread.
   */
  private var waitingFor: Any => Boolean = waitingForNone

  private var isSuspended = false

  protected val mailbox = new MessageQueue
  private var sessions: List[OutputChannel[Any]] = Nil

  protected[actors] def scheduler: IScheduler =
    Scheduler

  /**
   * Returns the number of messages in this actor's mailbox
   *
   * @return the number of messages in this actor's mailbox
   */
  def mailboxSize: Int = synchronized {
    mailbox.size
  }

  /**
   * Sends <code>msg</code> to this actor (asynchronous) supplying
   * explicit reply destination.
   *
   * @param  msg      the message to send
   * @param  replyTo  the reply destination
   */
  def send(msg: Any, replyTo: OutputChannel[Any]) = synchronized {
    if (waitingFor(msg)) {
      waitingFor = waitingForNone

      if (!onTimeout.isEmpty) {
        onTimeout.get.cancel()
        onTimeout = None
      }

      if (isSuspended) {
        sessions = replyTo :: sessions
        received = Some(msg)
        resumeActor()
      } else {
        sessions = List(replyTo)
        // assert continuation != null
        scheduler.execute(new Reaction(this, continuation, msg))
      }
    } else {
      mailbox.append(msg, replyTo)
    }
  }

  /**
   * Receives a message from this actor's mailbox.
   *
   * @param  f    a partial function with message patterns and actions
   * @return      result of processing the received value
   */
  def receive[R](f: PartialFunction[Any, R]): R = {
    assert(Actor.self(scheduler) == this, "receive from channel belonging to other actor")
    this.synchronized {
      if (shouldExit) exit() // links
      val qel = mailbox.extractFirst((m: Any) => f.isDefinedAt(m))
      if (null eq qel) {
        waitingFor = f.isDefinedAt
        suspendActor()
      } else {
        received = Some(qel.msg)
        sessions = qel.session :: sessions
      }
    }
    val result = f(received.get)
    received = None
    sessions = sessions.tail
    result
  }

  /**
   * Receives a message from this actor's mailbox within a certain
   * time span.
   *
   * @param  msec the time span before timeout
   * @param  f    a partial function with message patterns and actions
   * @return      result of processing the received value
   */
  def receiveWithin[R](msec: Long)(f: PartialFunction[Any, R]): R = {
    assert(Actor.self(scheduler) == this, "receive from channel belonging to other actor")
    this.synchronized {
      if (shouldExit) exit() // links

      // first, remove spurious TIMEOUT message from mailbox if any
      val spurious = mailbox.extractFirst((m: Any) => m == TIMEOUT)

      val qel = mailbox.extractFirst((m: Any) => f.isDefinedAt(m))
      if (null eq qel) {
        if (msec == 0) {
          if (f.isDefinedAt(TIMEOUT)) {
            received = Some(TIMEOUT)
            sessions = this :: sessions
          } else
            error("unhandled timeout")
        }
        else {
          waitingFor = f.isDefinedAt
          received = None
          suspendActorFor(msec)
          if (received.isEmpty) {
            // actor is not resumed because of new message
            // therefore, waitingFor has not been updated, yet.
            waitingFor = waitingForNone
            if (f.isDefinedAt(TIMEOUT)) {
              received = Some(TIMEOUT)
              sessions = this :: sessions
            }
            else
              error("unhandled timeout")
          }
        }
      } else {
        received = Some(qel.msg)
        sessions = qel.session :: sessions
      }
    }
    val result = f(received.get)
    received = None
    sessions = sessions.tail
    result
  }

  /**
   * Receives a message from this actor's mailbox.
   * <p>
   * This method never returns. Therefore, the rest of the computation
   * has to be contained in the actions of the partial function.
   *
   * @param  f    a partial function with message patterns and actions
   */
  def react(f: PartialFunction[Any, Unit]): Nothing = {
    assert(Actor.self(scheduler) == this, "react on channel belonging to other actor")
    this.synchronized {
      if (shouldExit) exit() // links
      val qel = mailbox.extractFirst((m: Any) => f.isDefinedAt(m))
      if (null eq qel) {
        waitingFor = f.isDefinedAt
        continuation = f
      } else {
        sessions = List(qel.session)
        scheduleActor(f, qel.msg)
      }
      throw new SuspendActorException
    }
  }

  /**
   * Receives a message from this actor's mailbox within a certain
   * time span.
   * <p>
   * This method never returns. Therefore, the rest of the computation
   * has to be contained in the actions of the partial function.
   *
   * @param  msec the time span before timeout
   * @param  f    a partial function with message patterns and actions
   */
  def reactWithin(msec: Long)(f: PartialFunction[Any, Unit]): Nothing = {
    assert(Actor.self(scheduler) == this, "react on channel belonging to other actor")
    this.synchronized {
      if (shouldExit) exit() // links
      // first, remove spurious TIMEOUT message from mailbox if any
      val spurious = mailbox.extractFirst((m: Any) => m == TIMEOUT)

      val qel = mailbox.extractFirst((m: Any) => f.isDefinedAt(m))
      if (null eq qel) {
        if (msec == 0) {
          if (f.isDefinedAt(TIMEOUT)) {
            sessions = List(this)
            scheduleActor(f, TIMEOUT)
          }
          else
            error("unhandled timeout")
        }
        else {
          waitingFor = f.isDefinedAt
          val thisActor = this
          onTimeout = Some(new TimerTask {
            def run() { thisActor.send(TIMEOUT, thisActor) }
          })
          Actor.timer.schedule(onTimeout.get, msec)
          continuation = f
        }
      } else {
        sessions = List(qel.session)
        scheduleActor(f, qel.msg)
      }
      throw new SuspendActorException
    }
  }

  /**
   * The behavior of an actor is specified by implementing this
   * abstract method. Note that the preferred way to create actors
   * is through the <code>actor</code> method
   * defined in object <code>Actor</code>.
   */
  def act(): Unit

  /**
   * Sends <code>msg</code> to this actor (asynchronous).
   */
  def !(msg: Any) {
    send(msg, Actor.self(scheduler))
  }

  /**
   * Forwards <code>msg</code> to this actor (asynchronous).
   */
  def forward(msg: Any) {
    send(msg, Actor.sender)
  }

  /**
   * Sends <code>msg</code> to this actor and awaits reply
   * (synchronous).
   *
   * @param  msg the message to be sent
   * @return     the reply
   */
  def !?(msg: Any): Any = {
    val replyCh = new Channel[Any](Actor.self(scheduler))
    send(msg, replyCh)
    replyCh.receive {
      case x => x
    }
  }

  /**
   * Sends <code>msg</code> to this actor and awaits reply
   * (synchronous) within <code>msec</code> milliseconds.
   *
   * @param  msec the time span before timeout
   * @param  msg  the message to be sent
   * @return      <code>None</code> in case of timeout, otherwise
   *              <code>Some(x)</code> where <code>x</code> is the reply
   */
  def !?(msec: Long, msg: Any): Option[Any] = {
    val replyCh = new Channel[Any](Actor.self(scheduler))
    send(msg, replyCh)
    replyCh.receiveWithin(msec) {
      case TIMEOUT => None
      case x => Some(x)
    }
  }

  /**
   * Sends <code>msg</code> to this actor and immediately
   * returns a future representing the reply value.
   */
  def !!(msg: Any): Future[Any] = {
    val ftch = new Channel[Any](Actor.self(scheduler))
    val linkedChannel = new AbstractActor {
      def !(msg: Any) =
        ftch ! msg
      def send(msg: Any, replyTo: OutputChannel[Any]) =
        ftch.send(msg, replyTo)
      def forward(msg: Any) =
        ftch.forward(msg)
      def receiver =
        ftch.receiver
      def linkTo(to: AbstractActor) { /* do nothing */ }
      def unlinkFrom(from: AbstractActor) { /* do nothing */ }
      def exit(from: AbstractActor, reason: AnyRef) {
        ftch.send(Exit(from, reason), Actor.this)
      }
      // should never be invoked; return dummy value
      def !?(msg: Any) = msg
      // should never be invoked; return dummy value
      def !?(msec: Long, msg: Any): Option[Any] = Some(msg)
      // should never be invoked; return dummy value
      def !!(msg: Any): Future[Any] = {
        val someChan = new Channel[Any](Actor.self(scheduler))
        new Future[Any](someChan) {
          def apply() =
            if (isSet) value.get
            else ch.receive {
              case any => value = Some(any); any
            }
          def respond(k: Any => Unit): Unit =
            if (isSet) k(value.get)
            else ch.react {
 	      case any => value = Some(any); k(any)
          }
          def isSet = value match {
            case None => ch.receiveWithin(0) {
              case TIMEOUT => false
              case any => value = Some(any); true
            }
            case Some(_) => true
          }
        }
      }
      // should never be invoked; return dummy value
      def !![A](msg: Any, f: PartialFunction[Any, A]): Future[A] = {
        val someChan = new Channel[A](Actor.self(scheduler))
        new Future[A](someChan) {
          def apply() =
            if (isSet) value.get.asInstanceOf[A]
            else ch.receive {
              case any => value = Some(any); any
            }
          def respond(k: A => Unit): Unit =
 	    if (isSet) k(value.get.asInstanceOf[A])
            else ch.react {
              case any => value = Some(any); k(any)
            }
          def isSet = value match {
            case None => ch.receiveWithin(0) {
              case TIMEOUT => false
              case any => value = Some(any); true
            }
            case Some(_) => true
          }
        }
      }
    }
    linkTo(linkedChannel)
    send(msg, linkedChannel)
    new Future[Any](ftch) {
      var exitReason: Option[Any] = None
      val handleReply: PartialFunction[Any, Unit] = {
        case Exit(from, reason) =>
          exitReason = Some(reason)
        case any =>
          value = Some(any)
      }

      def apply(): Any =
        if (isSet) {
          if (!value.isEmpty)
            value.get
          else if (!exitReason.isEmpty) {
            val reason = exitReason.get
            if (reason.isInstanceOf[Throwable])
              throw new ExecutionException(reason.asInstanceOf[Throwable])
            else
              throw new ExecutionException(new Exception(reason.toString()))
          }
        } else ch.receive(handleReply andThen {(x: Unit) => apply()})

      def respond(k: Any => Unit): Unit =
 	if (isSet)
          apply()
 	else
          ch.react(handleReply andThen {(x: Unit) => k(apply())})

      def isSet = (value match {
        case None =>
          val handleTimeout: PartialFunction[Any, Boolean] = {
            case TIMEOUT =>
              false
          }
          val whatToDo =
            handleTimeout orElse (handleReply andThen {(x: Unit) => true})
          ch.receiveWithin(0)(whatToDo)
        case Some(_) => true
      }) || !exitReason.isEmpty
    }
  }

  /**
   * Sends <code>msg</code> to this actor and immediately
   * returns a future representing the reply value.
   * The reply is post-processed using the partial function
   * <code>f</code>. This also allows to recover a more
   * precise type for the reply value.
   */
  def !![A](msg: Any, f: PartialFunction[Any, A]): Future[A] = {
    val ftch = new Channel[A](Actor.self(scheduler))
    send(msg, new OutputChannel[Any] {
      def !(msg: Any) =
        ftch ! f(msg)
      def send(msg: Any, replyTo: OutputChannel[Any]) =
        ftch.send(f(msg), replyTo)
      def forward(msg: Any) =
        ftch.forward(f(msg))
      def receiver =
        ftch.receiver
    })
    new Future[A](ftch) {
      def apply() =
        if (isSet) value.get.asInstanceOf[A]
        else ch.receive {
          case any => value = Some(any); value.get.asInstanceOf[A]
        }
      def respond(k: A => Unit): Unit =
 	if (isSet) k(value.get.asInstanceOf[A])
 	else ch.react {
 	  case any => value = Some(any); k(value.get.asInstanceOf[A])
 	}
      def isSet = value match {
        case None => ch.receiveWithin(0) {
          case TIMEOUT => false
          case any => value = Some(any); true
        }
        case Some(_) => true
      }
    }
  }

  /**
   * Replies with <code>msg</code> to the sender.
   */
  def reply(msg: Any) {
    sender ! msg
  }

  /**
   * Receives the next message from this actor's mailbox.
   */
  def ? : Any = receive {
    case x => x
  }

  def sender: OutputChannel[Any] = sessions.head

  def receiver: Actor = this

  private var continuation: PartialFunction[Any, Unit] = null
  private var onTimeout: Option[TimerTask] = None

  // guarded by lock of this
  protected def scheduleActor(f: PartialFunction[Any, Unit], msg: Any) =
    if ((f eq null) && (continuation eq null)) {
      // do nothing (timeout is handled instead)
    }
    else {
      val task = new Reaction(this,
                              if (f eq null) continuation else f,
                              msg)
      scheduler execute task
    }

  private[actors] var kill: () => Unit = () => {}

  private def suspendActor() {
    isSuspended = true
    while (isSuspended) {
      try {
        wait()
      } catch {
        case _: InterruptedException =>
      }
    }
    // links: check if we should exit
    if (shouldExit) exit()
  }

  private def suspendActorFor(msec: Long) {
    val ts = Platform.currentTime
    var waittime = msec
    var fromExc = false
    isSuspended = true
    while (isSuspended) {
      try {
        fromExc = false
        wait(waittime)
      } catch {
        case _: InterruptedException => {
          fromExc = true
          val now = Platform.currentTime
          val waited = now-ts
          waittime = msec-waited
          if (waittime < 0) { isSuspended = false }
        }
      }
      if (!fromExc) { isSuspended = false }
    }
    // links: check if we should exit
    if (shouldExit) exit()
  }

  private def resumeActor() {
    isSuspended = false
    notify()
  }

  /**
   * Starts this actor.
   */
  def start(): Actor = synchronized {
    // Reset various flags.
    //
    // Note that we do *not* reset `trapExit`. The reason is that
    // users should be able to set the field in the constructor
    // and before `act` is called.

    exitReason = 'normal
    exiting = false
    shouldExit = false

    scheduler execute {
      scheduler.newActor(Actor.this)
      (new Reaction(Actor.this)).run()
    }

    this
  }

  private def seq[a, b](first: => a, next: => b): Unit = {
    val s = Actor.self(scheduler)
    val killNext = s.kill
    s.kill = () => {
      s.kill = killNext

      // to avoid stack overflow:
      // instead of directly executing `next`,
      // schedule as continuation
      scheduleActor({ case _ => next }, 1)
      throw new SuspendActorException
    }
    first
    throw new KillActorException
  }

  private[actors] var links: List[AbstractActor] = Nil

  /**
   * Links <code>self</code> to actor <code>to</code>.
   *
   * @param to ...
   * @return   ...
   */
  def link(to: AbstractActor): AbstractActor = {
    assert(Actor.self(scheduler) == this, "link called on actor different from self")
    this linkTo to
    to linkTo this
    to
  }

  /**
   * Links <code>self</code> to actor defined by <code>body</code>.
   */
  def link(body: => Unit): Actor = {
    assert(Actor.self(scheduler) == this, "link called on actor different from self")
    val a = new Actor {
      def act() = body
      override final val scheduler: IScheduler = Actor.this.scheduler
    }
    link(a)
    a.start()
    a
  }

  private[actors] def linkTo(to: AbstractActor) = synchronized {
    links = to :: links
  }

  /**
   * Unlinks <code>self</code> from actor <code>from</code>.
   */
  def unlink(from: AbstractActor) {
    assert(Actor.self(scheduler) == this, "unlink called on actor different from self")
    this unlinkFrom from
    from unlinkFrom this
  }

  private[actors] def unlinkFrom(from: AbstractActor) = synchronized {
    links = links.filterNot(from.==)
  }

  var trapExit = false
  private[actors] var exitReason: AnyRef = 'normal
  private[actors] var shouldExit = false

  /**
   * <p>
   *   Terminates execution of <code>self</code> with the following
   *   effect on linked actors:
   * </p>
   * <p>
   *   For each linked actor <code>a</code> with
   *   <code>trapExit</code> set to <code>true</code>, send message
   *   <code>Exit(self, reason)</code> to <code>a</code>.
   * </p>
   * <p>
   *   For each linked actor <code>a</code> with
   *   <code>trapExit</code> set to <code>false</code> (default),
   *   call <code>a.exit(reason)</code> if
   *   <code>reason != 'normal</code>.
   * </p>
   */
  protected[actors] def exit(reason: AnyRef): Nothing = {
    exitReason = reason
    exit()
  }

  /**
   * Terminates with exit reason <code>'normal</code>.
   */
  protected[actors] def exit(): Nothing = {
    // links
    if (!links.isEmpty)
      exitLinked()
    terminated()
    throw new SuspendActorException
  }

  // Assume !links.isEmpty
  private[actors] def exitLinked() {
    exiting = true
    // remove this from links
    val mylinks = links.filterNot(this.==)
    // exit linked processes
    mylinks.foreach((linked: AbstractActor) => {
      unlink(linked)
      if (!linked.exiting)
        linked.exit(this, exitReason)
    })
  }

  // Assume !links.isEmpty
  private[actors] def exitLinked(reason: AnyRef) {
    exitReason = reason
    exitLinked()
  }

  // Assume !this.exiting
  private[actors] def exit(from: AbstractActor, reason: AnyRef) {
    if (trapExit) {
      this ! Exit(from, reason)
    }
    else if (reason != 'normal)
      this.synchronized {
        shouldExit = true
        exitReason = reason
        // resume this Actor in a way that
        // causes it to exit
        // (because shouldExit == true)
        if (isSuspended)
          resumeActor()
        else if (waitingFor ne waitingForNone) {
          scheduleActor(null, null)
        }
      }
  }

  private[actors] def terminated() {
    scheduler.terminated(this)
  }

  /* Requires qualified private, because <code>RemoteActor</code> must
   * register a termination handler.
   */
  private[actors] def onTerminate(f: => Unit) {
    scheduler.onTerminate(this) { f }
  }
}


/** <p>
 *    This object is used as the timeout pattern in
 *    <a href="Actor.html#receiveWithin(Long)" target="contentFrame">
 *    <code>receiveWithin</code></a> and
 *    <a href="Actor.html#reactWithin(Long)" target="contentFrame">
 *    <code>reactWithin</code></a>.
 *  </p>
 *  <p>
 *    The following example demonstrates its usage:
 *  </p><pre>
 *    receiveWithin(500) {
 *      <b>case</b> (x, y) <b>=&gt;</b> ...
 *      <b>case</b> TIMEOUT <b>=&gt;</b> ...
 *    }</pre>
 *
 *  @version 0.9.8
 *  @author Philipp Haller
 */
case object TIMEOUT


case class Exit(from: AbstractActor, reason: AnyRef)

/** <p>
 *    This class is used to manage control flow of actor
 *    executions.
 *  </p>
 *
 * @version 0.9.8
 * @author Philipp Haller
 */
private[actors] class SuspendActorException extends Throwable {
  /*
   * For efficiency reasons we do not fill in
   * the execution stack trace.
   */
  override def fillInStackTrace(): Throwable = this
}

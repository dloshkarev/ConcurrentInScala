package ru

import java.time.LocalDateTime

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.pipe
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Try}
import akka.actor._
import akka.pattern._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object Chapter8 {
  val sys = ActorSystem("local")

  //ex. 1
  def ex1() = {

    class TimerActor extends Actor {
      override def receive: Receive = {
        case Sender.Register(t) => {
          // prevent locking
          Future {
            Thread.sleep(t)
            TimerActor.Timeout
          } pipeTo sender()
        }
      }
    }

    object TimerActor {
      case object Timeout
      def props(): Props = Props(new TimerActor())
    }

    class Sender extends Actor {
      override def receive: Receive = {
        case TimerActor.Timeout => println(self.path.name + " done: " + LocalDateTime.now())
        case "start" => {
          val a = context.actorOf(TimerActor.props())
          println(self.path.name + " start: " + LocalDateTime.now())
          a ! Sender.Register(5000)
        }
      }
    }

    object Sender {
      case class Register(t: Int)
      def props(): Props = Props(new Sender())
    }

    (0 to 10).foreach { n =>
      sys.actorOf(Sender.props(), name = "sender-" + n) ! "start"
      Thread.sleep(1000)
    }
    Thread.sleep(20000)
    sys.terminate()
  }

  //ex. 2
  def ex2() = {
    class AccountActor(var amount: Int) extends Actor {
      override def receive: Receive = {
        case AccountActor.Add(v) => {
          amount += v
          println(self.path.name + " Add: " + this.amount)
        }
        case AccountActor.Subtract(to, v) => {
          if (amount - v > 0) {
            amount -= v
            println(self.path.name + " Subtract: " + this.amount)
            sender() ! AccountActor.Success(to, v)
          } else sender() ! AccountActor.Error(self.path.name + ": Amount cannot be negative!")
        }
      }
    }

    object AccountActor {
      def props(n: Int): Props = Props(new AccountActor(n))
      case class Add(amount: Int)
      case class Subtract(to: ActorRef, amount: Int)
      case class Success(to: ActorRef, amount: Int)
      case class Error(text: String)
    }

    class BankerActor extends Actor {
      override def receive: Receive = {
        case BankerActor.Transfer(from, to, amount) => {
          from ! AccountActor.Subtract(to, amount)
        }
        case AccountActor.Success(to, amount) => {
          to ! AccountActor.Add(amount)
        }
        case AccountActor.Error(e) => println(e)
      }
    }

    object BankerActor {
      def props(): Props = Props(new BankerActor)
      case class Transfer(from: ActorRef, to: ActorRef, amount: Int)
    }

    val banker = sys.actorOf(BankerActor.props())
    val a = sys.actorOf(AccountActor.props(10), name = "a")
    val b = sys.actorOf(AccountActor.props(20), name = "b")

    banker ! BankerActor.Transfer(a, b, 5)
    Thread.sleep(500)
    banker ! BankerActor.Transfer(a, b, 5)
    Thread.sleep(500)
    banker ! BankerActor.Transfer(b, a, 10)
    Thread.sleep(20000)
    sys.terminate()
  }

  //ex. 3
  def ex3() = {
    class SessionActor(password: String, r: ActorRef) extends Actor {
      override def receive: Receive = {
        case SessionActor.StartSession(p) => {
          if (p == password) {
            context.become(startSession(r))
          }
        }
      }

      def startSession(r: ActorRef): Actor.Receive = {
        case SessionActor.EndSession => context.unbecome()
        case msg => r.forward(msg)
      }
    }

    object SessionActor {
      def props(password: String, r: ActorRef) = Props(new SessionActor(password, r))
      case class StartSession(password: String)
      case object EndSession
    }

    class TestActor extends Actor {
      override def receive: Receive = {
        case msg => println(msg)
      }
    }

    object TestActor {
      def props() = Props(new TestActor())
    }

    val a = sys.actorOf(TestActor.props())
    val dispatcher = sys.actorOf(SessionActor.props("123", a))
    println("Should be empty: ")
    dispatcher ! SessionActor.StartSession("1")
    dispatcher ! "test"
    dispatcher ! SessionActor.EndSession

    println("Should be non empty: ")
    dispatcher ! SessionActor.StartSession("123")
    dispatcher ! "test"
    dispatcher ! SessionActor.EndSession
    dispatcher ! "qwerty"
    sys.terminate()
  }

  //ex. 4
  def ex4(): Unit = {
    class ActorExecutionContext extends ExecutionContext {
      override def execute(runnable: Runnable): Unit = {
        val a = sys.actorOf(ExecutorActor.props(runnable))
        a ! ExecutorActor.Run
      }

      override def reportFailure(e: Throwable): Unit = e.printStackTrace()

      class ExecutorActor(runnable: Runnable) extends Actor {
        override def receive: Receive = {
          case ExecutorActor.Run => Try(runnable.run()) match {
            case Success(_) => println("done")
            case Failure(e) => reportFailure(e)
          }
        }
      }

      object ExecutorActor {
        def props(runnable: Runnable) = Props(new ExecutorActor(runnable))
        case object Run
      }
    }

    val ec = new ActorExecutionContext
    ec.execute(() => {
      Thread.sleep(1000)
      println("do something")
    })
    ec.execute(() => {
      throw new RuntimeException("test exception")
    })
    Thread.sleep(5000)
    sys.terminate()
  }

  //ex. 5
  def ex5() = {
    class FailureDetector(actor: ActorRef, interval: Int, limit: Int) extends Actor {
      private val cancellable =
        sys.scheduler.schedule(
          0 millis,
          interval millis,
          self,
          FailureDetector.Ping)

      override def receive: Receive = {
        case FailureDetector.Ping => {
          implicit val timeout: Timeout = Timeout(limit millis)
          actor ? FailureDetector.Identify onComplete {
            case Success(_) => println("ok")
            case Failure(e) => {
              println("timed out")
              context.actorSelection(actor.path.parent) ! FailureDetector.Failed(actor)
              cancellable.cancel()
            }
          }
        }
        case FailureDetector.ActorIdentity(delay) => println(s"delay $delay: ok")
      }
    }

    object FailureDetector {
      def props(actor: ActorRef, interval: Int, limit: Int) = Props(new FailureDetector(actor, interval, limit))
      case object Ping
      case object Identify
      case class ActorIdentity(delay: Int)
      case class Failed(actor: ActorRef)
    }

    class TestActor extends Actor {
      override def receive: Receive = {
        case FailureDetector.Identify => {
          val randomDelay = Random.nextInt(1000)
          println(s"delay: $randomDelay")
          Thread.sleep(randomDelay)
          sender() ! FailureDetector.ActorIdentity(randomDelay)
        }
      }
    }

    object TestActor {
      def props() = Props(new TestActor())
    }

    class ParentActor extends Actor {
      override def receive: Receive = {
        case FailureDetector.Failed(actor) => println(actor.path.name + " failed")
        case "run" => {
          val t = sys.actorOf(TestActor.props(), "t1")
          sys.actorOf(FailureDetector.props(t, 500, 500))
        }
      }
    }

    object ParentActor {
      def props() = Props(new ParentActor())
    }

    val test = sys.actorOf(ParentActor.props())
    test ! "run"
    Thread.sleep(10000)
    sys.terminate()
  }

  //ex. 6
  def ex6() = {
    class DistributedMap[K, V](shards: ActorRef*) {
      def update(key: K, value: V): Future[Unit] = ???
      def get(key: K): Future[Option[V]] = ???
    }
  }

}

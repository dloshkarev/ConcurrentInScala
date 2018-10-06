package ru

import java.util.{Timer, TimerTask}

import scala.async.Async.{async, await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise, TimeoutException, _}
import scala.io.Source
import scala.sys.process._
import scala.util.{Failure, Success, Try}
import Chapter2._

import scala.annotation.tailrec
import scala.collection.mutable

object Chapter4 {

  //ex. 1
  def ex1(): Unit = {
    val waitTimer = new java.util.Timer()
    waitTimer.schedule(new TimerTask {
      override def run(): Unit = print(".")
    }, 0, 50)

    val asyncResult = Future {
      waitTimer.cancel()
      Source.fromURL("https://nn.hh.ru/").mkString
    }

    Try(Await.result(asyncResult, 2.seconds)) match {
      case Success(html) => println(html)
      case Failure(e) => {
        waitTimer.cancel()
        e match {
          case _: TimeoutException => println("Timeout expired!")
          case e: Exception => e.printStackTrace()
        }
      }
    }
  }

  //ex. 2
  def ex2() = {
    class IVar[T] {
      private val value = Promise[T]

      def apply: T = if (value.isCompleted) Await.result(value.future, Duration.Inf) else throw new IllegalStateException("empty")

      def :=(x: T): Unit = if (!value.isCompleted) value.success(x) else throw new IllegalStateException("non empty")
    }

    val c = new IVar[Int]
    c := 1
    println(c.apply)
  }

  //ex. 3
  def ex3() = {
    implicit class FutureOpts[T](val self: Future[T]) {
      def exists(p: T => Boolean): Future[Boolean] = self.map(p)
    }

    val f = Future {
      Thread.sleep(1000)
      42
    }

    println(Await.result(f.exists(_ == 43), Duration.Inf))
    println(Await.result(f.exists(_ == 42), Duration.Inf))
  }

  //ex. 4
  def ex4() = {
    implicit class FutureOpts[T](val self: Future[T]) {
      def exists(p: T => Boolean): Future[Boolean] = {
        val r = Promise[Boolean]
        self.foreach(s => r.success(p(s)))
        r.future
      }
    }

    val f = Future {
      Thread.sleep(1000)
      42
    }

    println(Await.result(f.exists(_ == 43), Duration.Inf))
    println(Await.result(f.exists(_ == 42), Duration.Inf))
  }

  //ex. 5
  def ex5() = {
    implicit class FutureOpts[T](val self: Future[T]) {
      def exists(p: T => Boolean): Future[Boolean] = async {
        val s = await {
          self
        }
        p(s)
      }
    }

    val f = Future {
      Thread.sleep(1000)
      42
    }

    println(Await.result(f.exists(_ == 43), Duration.Inf))
    println(Await.result(f.exists(_ == 42), Duration.Inf))
  }

  //ex. 6
  def ex6(): Unit = {
    def spawn(command: String): Future[Int] = Future {
      blocking {
        command !
      }
    }

    val f = spawn("ping -c 10 google.com")

    f.onComplete {
      case Success(i) => println(s"result = $i")
      case Failure(e) => println(s"Error !!!! ${e.toString}")
    }

    Thread.sleep(10000)
  }

  //ex. 7
  def ex7() = {
    class IMap[K, V] {
      val m = new scala.collection.concurrent.TrieMap[K, Promise[V]]()

      implicit def value2Promise(v: V): Promise[V] = {
        val p = Promise[V]
        p.success(v)
      }

      def key2Promise(k: K): Promise[V] = {
        val p = Promise[V]
        m.putIfAbsent(k, p) match {
          case Some(old) => old
          case None => p
        }
      }

      def update(k: K, v: V): Unit = m.putIfAbsent(k, v) match {
        case Some(old) => try {
          old.success(v)
        } catch {
          case e: IllegalStateException => throw new IllegalStateException("non empty: " + old)
          case e => throw e
        }
        case None =>
      }

      def apply(k: K): Future[V] = m.getOrElse(k, key2Promise(k)).future
    }

    val m = new IMap[Int, String]()
    //m.update(1, "test2")
    thread {
      val r = Await.result(m(1), Duration.Inf)
      println(r)
    }
    m.update(1, "test")
  }

  //ex.8
  def ex8() = {
    implicit class PromiseOpts[T](self: Promise[T]) {
      def compose[S](f: S => T): Promise[S] = {
        val p = Promise[S]
        p.future onComplete {
          case Success(x) => Future {
            self.trySuccess(f(x))
          }
          case Failure(e) => self.tryFailure(e)
        }
        p
      }
    }
  }

  //ex. 9
  def ex9() = {
    def scatterGather[T](tasks: Seq[() => T]): Future[Seq[T]] = {
      Future.sequence(tasks.map { block =>
        Future {
          block()
        }
      })
    }

    def f1() = {
      println("f1 started")
      Thread.sleep(100)
      println("f1 done")
      1
    }

    def f2() = {
      println("f2 started")
      Thread.sleep(1000)
      println("f2 done")
      2
    }

    val f = scatterGather(Seq[() => Int](f1, f2))
    println(Await.result(f, Duration.Inf))
  }

  //ex. 10
  //books's implementation - I didn't get what should I change...
  def ex10() = {
    val timer = new Timer(true)

    def timeout(t: Long): Future[Unit] = {
      val p = Promise[Unit]
      timer.schedule(new TimerTask {
        override def run(): Unit = {
          p.success()
          timer.cancel()
        }
      }, t)
      p.future
    }

    timeout(1000) foreach (_ => println("Timed out!"))
    Thread.sleep(2000)
  }

  //ex. 11
  def ex11(): Unit = {
    class DAG[T](val value: T) {
      val edges = new mutable.HashSet[DAG[T]]()
    }

    def fold[T, S](g: DAG[T], f: (T, Seq[S]) => S): Future[S] = {
      def recFold(node: DAG[T]): Future[S] = {
        if (node.edges.isEmpty) Future {
          f(node.value, Nil)
        }
        else {
          val edges: Seq[S] = Await.result(Future.sequence(node.edges.map(recFold).toSeq), Duration.Inf)
          Future {
            f(node.value, edges)
          }
        }
      }

      recFold(g)
    }

    val a = new DAG("a")
    val b = new DAG("b")
    val c = new DAG("c")
    val d = new DAG("d")
    val e = new DAG("e")

    a.edges += b
    b.edges += c
    b.edges += d
    c.edges += e
    d.edges += e

    val f = fold[String, String](a, { (value, edges) =>
      println(value)
      Thread.sleep(1000)
      val combined = if (edges.nonEmpty) edges.reduce(_ + _) else ""
      value + combined
    })

    println(Await.result(f, Duration.Inf))
  }

}

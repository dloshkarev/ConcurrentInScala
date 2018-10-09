package ru

import ru.Chapter2._
import rx.lang.scala.Observable._
import rx.lang.scala.{Observable, Subject}

import scala.concurrent.duration._
import scala.util.Random

object Chapter6 {

  //ex. 1
  def ex1() = {
    val rootThreadGroup = Thread.currentThread().getThreadGroup

    def getRunningThreads(): List[Thread] = {
      val threads = new Array[Thread](rootThreadGroup.activeCount())
      rootThreadGroup.enumerate(threads)
      threads.toList
    }

    var threads: List[Thread] = getRunningThreads()

    val o = interval(1.seconds).flatMap { s =>
      Observable[Thread] { o =>
        val newThreads = getRunningThreads()
        val diff = newThreads.filter(!threads.contains(_))
        if (diff.nonEmpty) {
          threads = newThreads
          diff.foreach(t => o.onNext(t))
        }
      }
    }
    o.subscribe(t => println(t.getName + " run called!"))

    Thread.sleep(1000)
    thread {
      println(s"${Thread.currentThread().getName} started")
      Thread.sleep(2000)
      println(s"${Thread.currentThread().getName} finished")
    }
    Thread.sleep(500)
    thread {
      println(s"${Thread.currentThread().getName} started")
      Thread.sleep(1000)
      println(s"${Thread.currentThread().getName} finished")
    }

    Thread.sleep(1000)
  }

  //ex. 2
  def ex2() = {
    val obs = interval(1.seconds).filter(n => n % 30 != 0 && (n % 5 == 0 || n % 12 == 0))
    obs.subscribe(n => println(n))
    Thread.sleep(100000)
  }

  //ex. 3
  def ex3() = {
    def randomQuote: String = {
      // api doesn't work
      val length = Random.nextInt(10)
      length + "x" * (length - 1)
    }

    interval(1.seconds).map(n => randomQuote).scan((0.0, 0)) { (acc, s) =>
      println(s)
      (acc._1 + s.length, acc._2 + 1)
    }.tail.map(x => x._1 / x._2).subscribe(n => println(n))
    Thread.sleep(10000)
  }

  //ex. 4
  class Signal[T] {
    protected var value: Option[T] = None
    protected var obs: Observable[T] = _

    def this(value: T) {
      this()
      this.value = Some(value)
    }

    def this(obs: Observable[T]) {
      this()
      this.obs = obs
      this.obs.subscribe(t => { value = Option(t) })
    }

    def this(value: T, obs: Observable[T]) {
      this(obs)
      this.value = Some(value)
    }

    def setObservable(obs: Observable[T]): Unit = {
      this.obs = obs
      this.obs.subscribe(t => { value = Option(t) })
    }

    def apply(): T = value.get

    def map[S](f: T => S): Signal[S] = value match {
      case Some(v) => new Signal[S](f(v), obs.map(f))
      case None => new Signal[S](obs.map(f))
    }

    def zip[S](that: Signal[S]): Signal[(T, S)] = (this.value, that.value) match {
      case (Some(v1), Some(v2)) => new Signal[(T, S)]((v1, v2), this.obs.zip(that.obs))
      case _ => new Signal[(T, S)](this.obs.zip(that.obs))
    }

    def scan[S](z: S)(f: (S, T) => S): Signal[S] = this.value match {
      case Some(v) => new Signal[S](f(z, v), this.obs.scan(z)(f))
      case None => new Signal[S](this.obs.scan(z)(f))
    }
  }

  implicit class ObservableOpts[T](val self: Observable[T]) {
    def toSignal: Signal[T] = new Signal[T](self)
  }

  def ex4() = {

    val sub1 = Subject[Int]()
    val sig1 = sub1.toSignal
    sub1.onNext(1)
    assert(sig1() == 1)
    sub1.onNext(2)
    assert(sig1() == 2)

    val sub2 = Subject[Int]()
    val sig2 = sub2.toSignal
    sub2.onNext(1)
    val increment = sig2.map(_ + 1)
    assert(increment() == 2)
    sub2.onNext(2)
    assert(increment() == 3)

    val sub31 = Subject[Int]()
    val sub32 = Subject[String]()
    val sig31 = sub31.toSignal
    val sig32 = sub32.toSignal
    sub31.onNext(1)
    sub32.onNext("a")
    val zipped = sig31.zip(sig32)
    assert(zipped() == (1, "a"))
    sub31.onNext(2)
    sub32.onNext("b")
    assert(zipped() == (2, "b"))

    val sub4 = Subject[Int]()
    val sig4 = sub4.toSignal
    sub4.onNext(1)
    val sum = sig4.scan(10)(_ + _)
    assert(sum() == 11)
    sub4.onNext(2)
    assert(sum() == 12)
    sub4.onNext(3)
    assert(sum() == 15)
  }

  //ex. 5
  def ex5() = {
    class RCell[T](v: T) extends Signal[T](v) {
      private val subject = Subject[T]()
      this.setObservable(subject)

      def :=(x: T): Unit = subject.onNext(x)
    }

    val cell = new RCell[Int](1)
  }

  //Rest of exercises skipped
}

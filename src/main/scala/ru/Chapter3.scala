package ru

import java.util.concurrent.ConcurrentHashMap

import ru.Chapter2._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.ExecutionContext

object Chapter3 {
  //ex. 1
  def ex1(): Unit = {
    class PiggybackContext extends ExecutionContext {
      override def execute(t: Runnable): Unit = {
        try {
          t.run()
        } catch {
          case e: Exception => reportFailure(e)
        }
      }

      override def reportFailure(e: Throwable): Unit = {
        println("exception!")
        e.printStackTrace()
      }
    }

    val context = new PiggybackContext()
    context.execute(() => println("test"))
    context.execute(() => throw new RuntimeException("test exception"))
  }

  //ex. 2
  def ex2(): Unit = {
    class TreiberStack[T] {
      private val stack = new AtomicReference[List[T]](Nil)

      @tailrec
      final def push(t: T): Unit = {
        val old = stack.get()
        if (!stack.compareAndSet(old, t :: old)) push(t)
      }

      @tailrec
      final def pop(): T = {
        val old = stack.get()
        if (old.nonEmpty && stack.compareAndSet(old, old.tail)) old.head
        else pop()
      }

      def printAll(): Unit = {
        println(stack.get())
      }
    }

    val stack = new TreiberStack[Int]
    val t1 = thread {
      (0 to 10).foreach { n =>
        stack.push(n)
      }
    }
    val t2 = thread {
      (0 to 10).foreach { n =>
        stack.pop()
      }
    }

    t1.join()
    t2.join()
    stack.printAll()
  }

  //ex. 3-4
  def ex3(): Unit = {
    class ConcurrentSortedList[T](implicit val ord: Ordering[T]) {
      private val list = new AtomicReference[List[T]](Nil)
      private val sorted = new AtomicBoolean(false)

      @tailrec
      final def add(x: T): Unit = {
        // ex. 4 require linear time, but this solution even better - constant time
        val old = list.get
        sorted.compareAndSet(true, false)
        if (!list.compareAndSet(old, x :: old)) add(x)
      }

      def iterator: Iterator[T] = {
        if (sorted.compareAndSet(false, true)) {
          // Under the terms of the exercise there is no concurrence between 'add' and 'iterator' methods, so no need to make it harder
          list.updateAndGet(_.sorted).iterator
        } else {
          list.get().iterator
        }
      }
    }
    val csl = new ConcurrentSortedList[Int]()


    (1 to 10).map((i) => thread {
      Thread.sleep((Math.random() * 100).toInt)
      for (i <- 1 to 100) {
        Thread.sleep((Math.random() * 10).toInt)
        csl.add((math.random * 100 + i).toInt)
      }
    }
    ).foreach(_.join)

    println(s"length = ${csl.iterator.length}")

    var prev = 0
    var length = 0
    for (a <- csl.iterator) {
      println(a.toString)
      if (prev > a) throw new Exception(s"$prev > $a")
      prev = a
      length += 1
    }

    if (csl.iterator.length != length) throw new Exception(s"${csl.iterator.length} != $length")

    println(s"length = ${csl.iterator.length} ($length)")
  }

  //ex. 5
  def ex5(): Unit = {
    class LazyCell[T](initialization: => T) {
      @volatile
      private var value: Option[T] = None

      def apply(): T = {
        // Double check singleton implementation
        value match {
          case Some(v) => v
          case None => this.synchronized {
            value match {
              case Some(v) => v
              case None => {
                value = Some(initialization)
                value.get
              }
            }
          }
        }
      }
    }

    def init = {
      println(s"init by ${Thread.currentThread().getName}")
      1
    }

    val cell = new LazyCell[Int](init)
    val cell2 = new LazyCell[Int](init)

    (0 to 10).map { n =>
      thread {
        cell.apply()
      }
    }.foreach(_.join())
  }

  //ex. 6
  def ex6(): Unit = {
    class PureLazyCell[T](initialization: => T) {
      private val value = new AtomicReference[Option[T]](None)

      def apply(): T = value.get() match {
        case Some(v) => v
        case None => {
          val v = initialization
          if (!value.compareAndSet(None, Some(v))) apply() else v
        }
      }
    }
    def init = {
      println(s"init by ${Thread.currentThread().getName}")
      1
    }

    val cell = new PureLazyCell[Int](init)
    val cell2 = new PureLazyCell[Int](init)

    (0 to 10).map { n =>
      thread {
        cell.apply()
        Thread.sleep(10)
      }
    }.foreach(_.join())
  }

  // ex. 7
  def ex7(): Unit = {
    class SyncConcurrentMap[A, B] extends scala.collection.concurrent.Map[A, B] {
      private val map = mutable.Map.empty[A, B]

      override def putIfAbsent(k: A, v: B): Option[B] = map.synchronized {
        map.get(k) match {
          case value@Some(_) => value
          case None => map.put(k, v)
        }
      }

      override def remove(k: A, v: B): Boolean = map.synchronized {
        map.get(k) match {
          case Some(value) => if (value == v) {
            map.remove(k)
            true
          } else false
          case None => false
        }
      }

      override def replace(k: A, oldvalue: B, newvalue: B): Boolean = map.synchronized {
        map.get(k) match {
          case Some(value) if value == oldvalue =>
            map.put(k, newvalue)
            true
          case _ => false
        }
      }

      override def replace(k: A, v: B): Option[B] = map.synchronized {
        map.get(k) match {
          case value@Some(_) =>
            map.put(k, v)
            value
          case _ => None
        }
      }

      override def +=(kv: (A, B)): SyncConcurrentMap.this.type = map.synchronized {
        map.put(kv._1, kv._2)
        this
      }

      override def -=(key: A): SyncConcurrentMap.this.type = map.synchronized {
        map.remove(key)
        this
      }

      override def get(key: A): Option[B] = map.synchronized {
        map.get(key)
      }

      override def iterator: Iterator[(A, B)] = map.synchronized {
        map.iterator
      }
    }
  }

  //ex. 8
  // Skipped - not interesting
  def spawn[T](block: => T): T = ???

  //ex. 9
  // Skipped - initial example from book doesn't work for me - fall with 'Should be non-empty' error
  def ex9(): Unit = {
    class Pool[T] {
      val parallelism = Runtime.getRuntime.availableProcessors * 32
      val buckets = new Array[AtomicReference[(List[T], Long)]](parallelism)
      for (i <- 0 until buckets.length)
        buckets(i) = new AtomicReference((Nil, 0L))

      def add(x: T): Unit = {
        val i = (Thread.currentThread.getId * x.## % buckets.length).toInt
        @tailrec def retry() {
          val bucket = buckets(i)
          val v = bucket.get
          val (lst, stamp) = v
          val nlst = x :: lst
          val nstamp = stamp + 1
          val nv = (nlst, nstamp)
          if (!bucket.compareAndSet(v, nv)) retry()
        }
        retry()
      }

      def remove(): Option[T] = {
        val start = (Thread.currentThread.getId % buckets.length).toInt
        @tailrec def scan(witness: Long): Option[T] = {
          var i = (start + 1) % buckets.length
          var sum = 0L
          while (i != start) {
            val bucket = buckets(i)

            @tailrec def retry(): Option[T] = {
              bucket.get match {
                case (Nil, stamp) =>
                  sum += stamp
                  None
                case v @ (lst, stamp) =>
                  val nv = (lst.tail, stamp + 1)
                  if (bucket.compareAndSet(v, nv)) Some(lst.head)
                  else retry()
              }
            }
            retry() match {
              case Some(v) => return Some(v)
              case None =>
            }

            i = (i + 1) % buckets.length
          }
          if (sum == witness) None
          else scan(sum)
        }
        scan(-1L)
      }
    }

    val check = new ConcurrentHashMap[Int, Unit]()
    val pool = new Pool[Int]
    val p = 8
    val num = 1000000
    val inserters = for (i <- 0 until p) yield thread {
      for (j <- 0 until num) pool.add(i * num + j)
    }
    inserters.foreach(_.join())
    val removers = for (i <- 0 until p) yield thread {
      for (j <- 0 until num) {
        pool.remove() match {
          case Some(v) => check.put(v, ())
          case None => sys.error("Should be non-empty.")
        }
      }
    }
    removers.foreach(_.join())
    for (i <- 0 until (num * p)) assert(check.containsKey(i))
  }

}

package ru

import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.collection.immutable.Queue
import scala.concurrent.stm._
import scala.util.Random

object Chapter7 {

  //ex. 1
  def ex1() = {
    class TPair[P, Q](pinit: P, qinit: Q) {
      val refFirst = Ref[P](pinit)
      val refSecond = Ref[Q](qinit)

      def first(implicit txn: InTxn): P = refFirst.single()

      def first_=(x: P)(implicit txn: InTxn): P = refFirst.single.transformAndGet(_ => x)

      def second(implicit txn: InTxn): Q = refSecond.single()

      def second_=(x: Q)(implicit txn: InTxn): Q = refSecond.single.transformAndGet(_ => x)

      def swap()(implicit e: P =:= Q, txn: InTxn): Unit = atomic { implicit txn =>
        val temp = refFirst.get
        refFirst.update(refSecond.get.asInstanceOf[P])
        refSecond.update(temp)
      }
    }

    val pair = new TPair(1, 2)

    atomic { implicit txn =>
      println(pair.first)
      println(pair.second)
      println(pair.first = 3)
      println(pair.second = 4)
    }

    atomic { implicit txn =>
      pair.swap()
      Txn.afterCommit(_ => {
        println("(" + pair.first + ", " + pair.second + ")")
      })
    }
  }

  //ex. 2
  def ex2() = {
    class MVar[T] {
      private var value = Ref[Option[T]](None)

      def put(x: T)(implicit txn: InTxn): Unit = atomic { implicit txn =>
        value.get match {
          case Some(v) => retry
          case None => value.update(Some(x))
        }
      }

      def take()(implicit txn: InTxn): T = atomic { implicit txn =>
        value.get match {
          case Some(v) => {
            value.update(None)
            v
          }
          case None => retry
        }
      }
    }

    def swap[T](a: MVar[T], b: MVar[T])(implicit txn: InTxn) = atomic { implicit txn =>
      val temp = a.take
      a.put(b.take())
      b.put(temp)
    }

    val m = new MVar[Int]
    Future {
      blocking {
        atomic { implicit txn =>
          println(m.take())
        }
      }
    }
    Future {
      blocking {
        Thread.sleep(1000)
        println("unlock")
        atomic { implicit txn =>
          m.put(1)
        }
      }
    }
    Thread.sleep(2000)

    val m2 = new MVar[Int]
    atomic { implicit txn =>
      m.put(1)
      m2.put(2)
      swap(m, m2)
      Txn.afterCommit(_ => {
        println(m.take() + "; " + m2.take())
      })
    }

  }

  //ex. 3
  def ex3() = {
    def atomicRollbackCount[T](block: InTxn => T): (T, Int) = {
      var count = 0
      atomic { implicit txn =>
        Txn.afterRollback(_ => {
          count += 1
        })
        (block(txn), count)
      }
    }

    val n = Ref(0)
    def block(txt: InTxn): Int = {
      val nextVal = Random.nextInt(100)
      n.set(nextVal)(txt)
      nextVal
    }

    (0 to 100).map { n =>
        Future {
          val result = atomicRollbackCount(block)
          println(result)
        }
    }

    Thread.sleep(2000)
  }

  //ex. 4
  def ex4() = {
    def atomicWithRetryMax[T](n: Int)(block: InTxn => T): T = {
      var count = 0
      atomic { implicit txn =>
        Txn.afterRollback(_ => {
          count += 1
        })
        if (count == n) throw new RuntimeException("Retry count exceeded - " + count)
        else block(txn)
      }
    }

    val n = Ref(0)
    def block(txt: InTxn): Int = {
      val nextVal = Random.nextInt(100)
      n.set(nextVal)(txt)
      nextVal
    }

    (0 to 100).map { n =>
      Future {
        try {
          val result = atomicWithRetryMax(2)(block)
          println(result)
        } catch {
          case e: RuntimeException => e.printStackTrace()
        }
      }
    }

    Thread.sleep(2000)
  }

  //ex. 5
  def ex5() = {
    class TQueue[T] {
      private val queue = Ref(Queue.empty[T])

      def enqueue(x: T)(implicit  txn: InTxn): Unit = queue.update(queue().enqueue(x))

      def dequeue()(implicit  txn: InTxn): T = {
        queue().dequeueOption match {
          case Some((x, rest)) => {
            queue.update(rest)
            x
          }
          case None => retry
        }
      }
    }

    //test
    val tQueue = new TQueue[Integer]

    val l = 1 to 20

    l.map { i =>
      Future {
        atomic {implicit txn =>
          val x = tQueue.dequeue

          Txn.afterCommit{_ =>
            println(s"dequeu: $x")
          }
        }
      }
    }

    l.map { i =>
      Future {
        atomic {implicit txn =>
          tQueue.enqueue(i)

          Txn.afterCommit { _ =>
            println(s"enque: $i")
          }
        }
      }
    }

    Thread.sleep(1000)
  }

}

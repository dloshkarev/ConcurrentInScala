package ru

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Random

object Chapter2 {

  def thread(block: => Unit): Thread = {
    val t = new Thread {
      override def run(): Unit = {
        block
      }
    }
    t.start()
    t
  }

  object SynchronizedProtectedUid {
    private var uuid = 0

    def getUniqueId: Int = this.synchronized {
      val newUuid = uuid + 1
      this.uuid = newUuid
      newUuid
    }
  }

  //ex. 1
  def parallel[A, B](a: => A, b: => B): (A, B) = {
    var x = null.asInstanceOf[A]
    var y = null.asInstanceOf[B]
    val xt = thread {
      x = a
    }
    val yt = thread {
      y = b
    }
    xt.join()
    yt.join()
    (x, y)
  }

  //ex. 2
  def periodically(duration: Long)(block: => Unit): Unit = {
    val t = thread(block)
    t.join()
    Thread.sleep(duration)
    periodically(duration)(block)
  }

  //ex. 3
  def ex3(): Unit = {
    class SyncVar[T] {
      var t: Option[T] = None

      def get(): T = this.synchronized {
        t match {
          case Some(v) => {
            this.t = None
            v
          }
          case None => throw new Exception("empty")
        }
      }

      def put(t: T) = this.synchronized {
        this.t match {
          case Some(v) => throw new Exception("non empty")
          case None => this.t = Some(t)
        }
      }
    }
  }

  //ex. 4
  def ex4(): Unit = {
    class SyncVar[T] {
      var empty = true
      var t: T = null.asInstanceOf[T]

      def isEmpty = synchronized {
        this.empty
      }

      def isNonEmpty = synchronized {
        !this.empty
      }

      def get(): T = this.synchronized {
        if (isEmpty) {
          throw new Exception("empty")
        } else {
          this.empty = true
          t
        }
      }

      def put(t: T) = this.synchronized {
        if (isNonEmpty) {
          throw new Exception("non empty")
        } else {
          this.empty = false
          this.t = t
        }
      }
    }

    val sync = new SyncVar[Int]
    val producer = thread {
      var n = 0
      while (n <= 15) {
        if (sync.isEmpty) {
          sync.put(n)
          n += 1
        }
      }
    }
    val consumer = thread {
      var n = 0
      while (n != 15) {
        if (sync.isNonEmpty) {
          n = sync.get()
          println(n)
        }
      }
    }

    producer.join()
    consumer.join()
  }

  //ex. 5
  def ex5(): Unit = {
    class SyncVar[T] {
      var empty = true
      var t: T = null.asInstanceOf[T]

      def getWait(): T = this.synchronized {
        while (empty) this.wait()
        this.empty = true
        this.notify()
        t
      }

      def putWait(t: T) = this.synchronized {
        while (!empty) this.wait()
        this.empty = false
        this.t = t
        this.notify()
      }
    }

    val sync = new SyncVar[Int]
    val producer = thread {
      var n = 0
      while (n <= 15) {
        sync.putWait(n)
        n += 1
      }
    }
    val consumer = thread {
      var n = 0
      while (n != 15) {
        n = sync.getWait()
        println(n)
      }
    }

    producer.join()
    consumer.join()
  }

  //ex. 6
  def ex6(): Unit = {
    class SyncQueue[T](n: Int) {
      val queue = new mutable.Queue[T]

      def getWait(): T = this.synchronized {
        while (queue.isEmpty) this.wait()
        val item = queue.dequeue()
        this.notify()
        item
      }

      def putWait(t: T) = this.synchronized {
        while (queue.length == n) this.wait()
        queue += t
        this.notify()
      }
    }

    val sync = new SyncQueue[Int](5)
    val producer = thread {
      var n = 0
      while (n <= 15) {
        sync.putWait(n)
        n += 1
      }
    }
    val consumer = thread {
      var n = 0
      while (n != 15) {
        n = sync.getWait()
        println(n)
      }
    }

    producer.join()
    consumer.join()
  }

  class Account(val name: String, var money: Int, val uid: Int = SynchronizedProtectedUid.getUniqueId)

  //ex. 7
  def sendAll(accounts: Set[Account], target: Account): Unit = {
    def adjust(): Unit = {
      target.money = accounts.foldLeft(0) { (sum, account) =>
        val money = account.money
        account.money = 0
        sum + money
      }
    }

    def syncAccounts(acs: List[Account]): Unit = if (acs.isEmpty) adjust() else acs.head.synchronized {
      syncAccounts(acs.tail)
    }

    syncAccounts((target :: accounts.toList).sortBy(_.uid))
  }

  //ex. 8
  class PriorityTaskPool {
    implicit val tasksOrder: Ordering[(() => Unit, Int)] = Ordering.by(_._2)
    private val tasks = mutable.PriorityQueue[(() => Unit, Int)]()

    def asynchronous(priority: Int)(task: => Unit): Unit = tasks.synchronized {
      tasks.enqueue((() => task, priority))
      tasks.notify()
    }

    object Worker extends Thread {
      setDaemon(true)

      def poll(): () => Unit = tasks.synchronized {
        while (tasks.isEmpty) tasks.wait()
        tasks.dequeue()._1
      }

      override def run(): Unit = while (true) {
        val task = poll()
        task()
      }
    }

    Worker.start()
  }

  //ex. 9
  class PriorityTaskPoolUnlimited(val p: Int) {
    implicit val tasksOrder: Ordering[(() => Unit, Int)] = Ordering.by(_._2)
    private val tasks = mutable.PriorityQueue[(() => Unit, Int)]()

    def asynchronous(priority: Int)(task: => Unit): Unit = tasks.synchronized {
      tasks.enqueue((() => task, priority))
      tasks.notify()
    }

    class Worker extends Thread {
      setDaemon(true)

      def poll(): () => Unit = tasks.synchronized {
        while (tasks.isEmpty) tasks.wait()
        tasks.dequeue()._1
      }

      override def run(): Unit = while (true) {
        val task = poll()
        task()
      }
    }

    (0 to p).foreach { n =>
      new Worker().start()
    }
  }

  //ex. 10
  class PriorityTaskPoolUnlimitedWithShutdown(val p: Int, val important: Int) {
    type Task = (() => Unit, Int)
    implicit val tasksOrder: Ordering[Task] = Ordering.by(_._2)
    private val tasks = mutable.PriorityQueue[Task]()
    private val workers = (0 to p).map(n => {
      val worker = new Worker()
      worker.start()
      worker
    })

    def asynchronous(priority: Int)(task: => Unit): Unit = tasks.synchronized {
      tasks.enqueue((() => task, priority))
      tasks.notify()
    }

    class Worker extends Thread {
      private var terminated = false

      def poll(): Option[Task] = tasks.synchronized {
        while (tasks.isEmpty && !terminated) tasks.wait()
        val task = tasks.dequeue()
        if (!terminated || task._2 > important) Some(task) else None
      }

      def shutdown() = tasks.synchronized {
        terminated = true
        tasks.notify()
      }

      @tailrec
      final override def run(): Unit = poll() match {
        case Some((task, priority)) =>
          println("executed: " + priority)
          task()
          run()
        case None =>
      }
    }

    def shutdown() = tasks.synchronized {
      workers.foreach { worker =>
        worker.shutdown()
      }
      tasks.foreach(println)
    }
  }

  //ex. 11-12
  class ConcurrentBiMap[K, V] {
    val lock = new AnyRef
    private val normalMap = mutable.Map[K, V]()
    private val reversedMap = mutable.Map[V, K]()

    def put(k: K, v: V): Option[(K, V)] = lock.synchronized {
      reversedMap.put(v, k)
      normalMap.put(k, v) match {
        case Some(value) => Some((k, value))
        case None => None
      }
    }

    def removeKey(k: K): Option[V] = lock.synchronized {
      normalMap.remove(k) match {
        case Some(value) => {
          reversedMap.remove(value)
          Some(value)
        }
        case None => None
      }
    }

    def removeValue(v: V): Option[K] = lock.synchronized {
      reversedMap.remove(v) match {
        case Some(key) => {
          normalMap.remove(key)
          Some(key)
        }
        case None => None
      }
    }

    def getValue(k: K): Option[V] = lock.synchronized {
      normalMap.get(k)
    }

    def getKey(v: V): Option[K] = lock.synchronized {
      reversedMap.get(v)
    }

    def size: Int = lock.synchronized {
      normalMap.size
    }

    def iterator: Iterator[(K, V)] = lock.synchronized {
      normalMap.iterator
    }

    def replace(k1: K, v1: V, k2: K, v2: V): Unit = lock.synchronized {
      // TODO: I didn't get how this method should work
      //removeKey(k1)
      //put(k2, v2)
    }
  }

  //ex. 13
  def testConcurrentBiMap() = {
    val m = new ConcurrentBiMap[String, String]()
    val insertThreads: Seq[Thread] = (0 to 2).map { n =>
      thread {
        (1 to 1000).foreach { n =>
          m.put(Random.nextString(10), Random.nextString(10))
        }
      }
    }
    insertThreads.foreach { t =>
      t.join()
    }
    println("Insert done: " + m.size)
    println("first: " + m.iterator.toList.head)

    val replaceThreads: Seq[Thread] = (0 to 5).map { n =>
      thread {
        m.iterator.foreach { case (k, v) =>
          m.replace(k, v, v, k)
        }
      }
    }
    replaceThreads.foreach { t =>
      t.join()
    }
    println("Replace done: " + m.size)
    println("first: " + m.iterator.toList.head)
  }

  //ex. 14
  def cache[K, V](f: K => V): K => V = k => {
    val c = mutable.Map[K, V]()
    c.synchronized {
      c.getOrElse(k, {
        val value = f(k)
        c.update(k, value)
        value
      })
    }
  }
}

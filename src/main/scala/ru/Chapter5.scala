package ru

object Chapter5 {
  @volatile var dummy: Any = _

  def timed[T](body: =>T): Double = {
    val start = System.nanoTime
    dummy = body
    val end = System.nanoTime
    ((end - start) / 1000) / 1000.0
  }

  def warmedTimed[T](times: Int = 200)(body: =>T): Double = {
    for (_ <- 0 until times) body
    timed(body)
  }

  //ex. 1
  def ex1() = {
    val time = warmedTimed(1000) {
      val o = new Object
    }
    println(time)
  }
}

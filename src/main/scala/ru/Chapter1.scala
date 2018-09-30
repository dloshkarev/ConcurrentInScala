package ru

object Chapter1 {

  // ex. 1
  def compose[A, B, C](g: B => C, f: A => B): A => C = a => g(f(a))

  // ex. 2
  def fuse[A, B](a: Option[A], b: Option[B]): Option[(A, B)] = (a, b) match {
    case (Some(x), Some(y)) => Some((x, y))
    case _ => None
  }

  //ex. 3
  def check[T](xs: Seq[T])(pred: T => Boolean): Boolean = try {
    xs.forall(pred)
  } catch {
    case _: Throwable => false
  }

  //ex. 4
  def ex4(): Unit = {
    case class Pair[P, Q](first: P, second: Q)
    val pair = Pair(1, "S")
    pair match {
      case Pair(x: Int, s: String) => println("ok")
      case _ => println("error")
    }
  }

  //ex. 5
  def permutations(s: String): Seq[String] = s.permutations.toList

  //ex. 6
  def combinations(n: Int, xs: Seq[Int]): Iterator[Seq[Int]] = xs.combinations(n)

  //ex. 7
  def matcher(regex: String): PartialFunction[String, List[String]] = {
    case s => regex.r.findAllIn(s).toList
  }
}

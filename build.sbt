name := "ConcurrentInScala"

version := "0.1"

scalaVersion := "2.12.7"

resolvers ++= Seq(
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
  "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases",
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"
)
libraryDependencies += "org.scala-lang.modules" %% "scala-async" % "0.9.7"
libraryDependencies += "com.netflix.rxjava" % "rxjava-scala" % "0.19.1"
libraryDependencies += "org.scala-stm" %% "scala-stm" % "0.8"


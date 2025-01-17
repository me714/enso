addSbtPlugin("com.eed3si9n"       % "sbt-assembly"             % "1.1.0")
addSbtPlugin("ch.epfl.scala"      % "sbt-bloop"                % "1.4.11")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.1.0")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.8.0")
addSbtPlugin("com.typesafe.sbt"   % "sbt-license-report"       % "1.2.0")

libraryDependencies += "io.circe" %% "circe-yaml" % "0.14.1"

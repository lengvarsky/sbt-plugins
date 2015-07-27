resolvers += "Sonatype-Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

libraryDependencies ++= Seq(
  "com.typesafe" % "config" % "1.2.0",
  "info.cukes" %% "cucumber" % "1.2.3" % "test",
  "org.scalatest" % "scalatest_2.10" % "2.1.6" % "test"
)

organization := "org.allenai.plugins"

name := "allenai-sbt-plugins"

lazy val ai2Plugins = project.in(file("."))

scalacOptions := Seq(
  "-encoding", "utf8",
  "-feature",
  "-unchecked",
  "-deprecation",
  "-language:_",

  "-Xlog-reflective-calls")

// SBT requires 2.10 for now (1/15/15).
scalaVersion := "2.10.4"

sbtPlugin := true

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.0.0")

addSbtPlugin("me.lessis" % "bintray-sbt" % "0.3.0")

addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.0")

addSbtPlugin(
  ("org.scalastyle" %% "scalastyle-sbt-plugin" % "0.6.0")
    // Exclude the old scalariform fork - we include a newer version with sbt-scalariform below.
    .exclude("com.danieltrinh", "scalariform_2.10"))

addSbtPlugin("com.github.jkinkead" % "sbt-scalariform" % "0.1.6")

// Dependency graph visualiztion in SBT console
addSbtPlugin("com.gilt" % "sbt-dependency-graph-sugar" % "0.7.4")

// Wrapped by WebServicePlugin and WebappPlugin
addSbtPlugin("io.spray" % "sbt-revolver" % "0.7.2")

// Allows us to test our plugins via the sbt-scripted plugin:
scriptedSettings

//Allows us to test our plugins via cucumber by running sbt cucumber:
// Cucumber is a test framework where you write your tests in an english-like DSL called Gherkin in .feature files,
// and then write the steps to execute those tests in whatever supported language you please (here, scala)
// www.cucumber.io
// plugin used is here: github.com/skipoleschris/xsbt-cucumber-plugin
cucumberSettings

cucumberFeaturesLocation := "./src/test/features"

// Publication settings.

publishMavenStyle := false

bintrayRepository := "sbt-plugins"

bintrayOrganization := Some("allenai")

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html"))

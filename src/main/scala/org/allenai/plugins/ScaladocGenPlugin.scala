package org.allenai.plugins

import com.typesafe.sbt.{ SbtGhPages, SbtGit, SbtSite }
import com.typesafe.sbt.git.DefaultReadableGit
import sbt._
import sbt.Keys._
import sbtunidoc.{ Plugin => UnidocPlugin }
import sbtunidoc.Plugin.ScalaUnidoc
import sbtunidoc.Plugin.UnidocKeys.unidoc

import java.io.{ File => JavaFile }
import java.net.URL

/** This wraps Unidoc and provides a few helper utilities for mapping dependency libraries to doc
  * URLs.
  *
  * This is meant to be included in an aggregate project, typically the root project of a
  * multi-project build.
  *
  * This plugin requires SbtGit.GitKeys.gitRemoteRepo to be set before its tasks can be used.
  */
object ScaladocGenPlugin extends AutoPlugin {
  /** Returns a filter that filters for transitive aggregate tasks. This lets you pull in settings
    * for all projects the current project aggregates.
    */
  lazy val aggregateFilter: Def.Initialize[Task[ScopeFilter]] = Def.task {
    val localDependencies = buildDependencies.value.aggregateTransitive(thisProjectRef.value)
    ScopeFilter(inProjects(localDependencies: _*))
  }

  /** The list of all classpaths for all aggregate projects. */
  lazy val aggregateFullClasspath: Def.Initialize[Task[Seq[Seq[Attributed[File]]]]] = Def.taskDyn {
    (fullClasspath in Compile).all(aggregateFilter.value)
  }

  // The following code is adapted from http://stackoverflow.com/q/16934488#31322970 .
  // This does two things: It registers a mapping to the Oracle javadocs for java classes being
  // linked to, and fixes up the generated HTML to correct scaladoc-style links to javadoc-style
  // links.

  /** The rt.jar file is located in the path stored in the sun.boot.class.path system property.  See
    * the Oracle documentation at
    * http://docs.oracle.com/javase/6/docs/technotes/tools/findingclasses.html.
    */
  lazy val rtJar: File = {
    System.getProperty("sun.boot.class.path").split(JavaFile.pathSeparator).collectFirst {
      case str: String if str.endsWith(JavaFile.separator + "rt.jar") => str
    } match {
      case Some(jar) => file(jar)
      // Fail hard. We can't reliably generate links.
      case None => throw new Exception("Error: rt.jar not found; scaladoc generation can't work!")
    }
  }

  lazy val scaladocGenJavadocUrl: SettingKey[URL] =
    settingKey[URL]("The base URL to use for generated Javadoc")

  lazy val scaladocGenExtraJavadocMap: SettingKey[Map[String, URL]] = settingKey[Map[String, URL]](
    "Mapping for any non-core Java libraries to link in, of library name to Javadoc URL"
  )

  // Default settings for javadoc generation.
  /** URL to the Java 8 javadoc. */
  lazy val javadocUrlSetting =
    scaladocGenJavadocUrl := url("http://docs.oracle.com/javase/8/docs/api/index.html")
  /** Extra libraries map, with CoreDependencies java libraries. */
  lazy val extraJavadocMapSetting = scaladocGenExtraJavadocMap := {
    Map("slf4j-api" -> url("http://www.slf4j.org/api/"))
  }

  /** Setting to add API mappings for our Java libraries. This allows the scaladoc generation task
    * to generate links for these libraries.
    */
  lazy val javaApiMappingsSetting = apiMappings ++= {
    val log = streams.value.log
    // From the full classpath, find jars with the names given in our doc map.
    val classpath: Iterable[File] = aggregateFullClasspath.value.flatten map { _.data }
    val externalJavadocMappings = scaladocGenExtraJavadocMap.value flatMap {
      case (libraryName, javadocUrl) => {
        classpath find { file => (file ** s"$libraryName*.jar").get.nonEmpty } map { jar =>
          jar -> javadocUrl
        }
      }
    }
    // Add the runtime mapping to the mappings we just generated & return.
    externalJavadocMappings + (rtJar -> scaladocGenJavadocUrl.value)
  }

  /** Setting that overrides the doc generation task. This finds all HTML files generated by the doc
    * task, then subs in the correctly-formatted Javadoc link in place of the badly-formatted
    * Scaladoc link.
    */
  lazy val fixJavadocSetting = doc in ScalaUnidoc := {
    // Build up a regular expression that matches any of the external javadoc links we have.
    val externalUrls =
      scaladocGenJavadocUrl.value +: scaladocGenExtraJavadocMap.value.values.toVector
    // Generate an alternation that will match any of the urls provided. Note that \Q and \E tell
    // the compiler to escape all regex characters in the URL, such as '.'.
    val anyUrl = externalUrls map { url => s"""\\Q$url\\E""" } mkString { "|" }
    // Final compiled regex capturing the URL matched as well as the fragment.
    // Looks like: (\Qhttp://url1.com\E|\Qhttp://url2.com\E)#([^"]*)
    val captureUrlAndFragment = s""""($anyUrl)#([^"]*)"""".r

    // Run the doc task, and get the folder it generated the HTML files into.
    val docFolder: File = (doc in ScalaUnidoc).value
    val htmlFiles: Iterable[File] = (docFolder ** "*.html").get
    // Replace all the Javadoc links in the generated files.
    htmlFiles foreach { file =>
      val oldContent: String = IO.read(file)
      val newContent: String = captureUrlAndFragment.replaceAllIn(oldContent, { regexMatch =>
        val urlBase = regexMatch.group(1)
        val scaladocFragment = regexMatch.group(2)
        val javadocPage = scaladocFragment.replace(".", "/")
        s"$urlBase?$javadocPage.html"
      })
      if (oldContent != newContent) {
        IO.write(file, newContent)
      }
    }
    // Return the same doc folder from the task.
    docFolder
  }

  // The following are helpers to make it easier to add manual mappings for Scala libraries.

  lazy val scaladocGenExtraScaladocMap: SettingKey[Map[String, URL]] = settingKey[Map[String, URL]](
    "Mappings for any Scala libraries to link in, of library name to Scaladoc URL"
  )
  /** The default setting includes libraries from CoreDependencies. */
  lazy val extraScaladocMapSetting = scaladocGenExtraScaladocMap := {
    val akkaUrl = url("http://doc.akka.io/api/akka/2.4.0")
    val sprayUrl = url("http://spray.io/documentation/1.1-SNAPSHOT/api/")
    Map(
      "akka-actor" -> akkaUrl,
      "akka-slf4j" -> akkaUrl,
      "akka-testkit" -> akkaUrl,
      "config" -> url("http://typesafehub.github.io/config/latest/api/"),
      "scopt" -> url("http://scopt.github.io/scopt/3.3.0/api/"),
      "spray-caching" -> sprayUrl,
      "spray-can" -> sprayUrl,
      "spray-client" -> sprayUrl,
      "spray-routing" -> sprayUrl,
      "spray-testkit" -> sprayUrl
    // TODO(jkinkead): spray-json doesn't publish their scaladoc - link to a fork + publish.
    )
  }
  /** Setting to add API mappings for our Scala libraries. This allows the scaladoc generation task
    * to generate links for these libraries.
    */
  lazy val scalaApiMappingsSetting = apiMappings ++= {
    val allDependencies: Seq[Attributed[File]] = aggregateFullClasspath.value.flatten
    // Generate a map of dependencies names to their jar files.
    // The filter removes local dependencies.
    val jarSuffix = s"_${scalaBinaryVersion.value}"
    val jarFiles: Map[String, Attributed[File]] = (
      allDependencies filter { _.data.isFile } flatMap { attributed =>
        attributed.metadata.get(AttributeKey[ModuleID]("moduleId")) map { id =>
          // Copy the module ID with all non-default fields taken out.
          id.name.stripSuffix(jarSuffix) -> attributed
        }
      }
    ).toMap
    val librariesToUrls = scaladocGenExtraScaladocMap.value
    jarFiles flatMap {
      case (name, jarFile) => {
        librariesToUrls.get(name) map { docUrl =>
          jarFile.put(entryApiURL, docUrl)
          jarFile.data -> docUrl
        }
      }
    }
  }

  // The final setting is to handle awkwardness with the Git plugin.

  /** This overrides the gitReader setting (what SbtGit uses to run `git`) with a fixed reader.
    * SbtGit doesn't handle projects rooted in a directory without a `.git` folder correctly - it
    * actually makes loading a project crash ''even if the project isn't using the plugin''.
    *
    * This task navigates up folders until it finds one that holds a `.git` directory, and
    * initializes the git reader to it.
    *
    * The name reflects the error you will see: A null `gitCurrentBranch` setting.
    */
  lazy val fixNullCurrentBranch = (SbtGit.GitKeys.gitReader in ThisBuild) := {
    var currentRoot = (baseDirectory in ThisBuild).value
    while (currentRoot.exists && !(currentRoot / ".git").exists) {
      currentRoot = currentRoot / ".."
    }
    if (!currentRoot.exists) {
      throw new Exception("project does not exist in a git repository, can't fix gitReader")
    }
    new DefaultReadableGit(currentRoot)
  }

  object autoImport {
    /** Convenience alias for required SbtGit setting. */
    lazy val scaladocGenGitRemoteRepo = SbtGit.GitKeys.gitRemoteRepo
    lazy val scaladocGenJavadocUrl = ScaladocGenPlugin.scaladocGenJavadocUrl
    lazy val scaladocGenExtraJavadocMap = ScaladocGenPlugin.scaladocGenExtraJavadocMap
    lazy val scaladocGenExtraScaladocMap = ScaladocGenPlugin.scaladocGenExtraScaladocMap
    lazy val fixNullCurrentBranch = ScaladocGenPlugin.fixNullCurrentBranch
  }

  override def projectSettings: Seq[Def.Setting[_]] = {
    // Dependency settings.
    UnidocPlugin.unidocSettings ++
      SbtSite.site.settings ++
      SbtGhPages.ghpages.settings ++
      Seq(
        // Local settings.
        javadocUrlSetting,
        extraJavadocMapSetting,
        javaApiMappingsSetting,
        fixJavadocSetting,
        extraScaladocMapSetting,
        scalaApiMappingsSetting,
        // This will pull in links from pom.xml files that sbt has generated, when the project sets
        // the apiURL key.
        autoAPIMappings := true,
        // This adds the output of Unidoc to the SbtSite plugin's mappings - meaning, this will end
        // up in the `latest/api` directory of the site synced to github pages.
        SbtSite.site.addMappingsToSiteDir(mappings in (ScalaUnidoc, packageDoc), "latest/api")
      )
  }
}

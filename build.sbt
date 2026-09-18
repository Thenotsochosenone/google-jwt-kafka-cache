ThisBuild / version      := "1.0.1"
ThisBuild / scalaVersion := "2.13.14"
ThisBuild / organization := "com.example"
ThisBuild / scalacOptions += "-deprecation"
resolvers in ThisBuild += "akka-secure-mvn" at "https://repo.akka.io/kKWl0LDp-uAo7VvnQfW8vXi2bxnlzs5cL4mUw8N4i5Wz8lng/secure"
resolvers in ThisBuild += Resolver.url("akka-secure-ivy", url("https://repo.akka.io/kKWl0LDp-uAo7VvnQfW8vXi2bxnlzs5cL4mUw8N4i5Wz8lng/secure"))(Resolver.ivyStylePatterns)

lazy val root = (project in file("."))
  .settings(
    name := "google-jwt-kafka-cache",
    libraryDependencies ++= Seq(
      // Akka
      "com.typesafe.akka" %% "akka-actor-typed"           % "2.9.3",
      "com.typesafe.akka" %% "akka-stream"                % "2.9.3",
      "com.typesafe.akka" %% "akka-stream-typed"          % "2.9.3",
      "com.typesafe.akka" %% "akka-slf4j"                 % "2.9.3",

      // Alpakka Kafka
      "com.typesafe.akka" %% "akka-stream-kafka"          % "4.0.2",

      // Google Auth
      "com.google.auth"    %  "google-auth-library-oauth2-http" % "1.23.0",

      // JWT
      "com.github.jwt-scala" %% "jwt-core"                % "10.0.1",
      "com.github.jwt-scala" %% "jwt-circe"               % "10.0.1",

      // Caching
      "com.github.ben-manes.caffeine" % "caffeine"        % "3.1.8",

      // Metrics / Observability
      "io.prometheus"      %  "simpleclient"              % "0.16.0",
      "io.prometheus"      %  "simpleclient_hotspot"      % "0.16.0",
      "io.prometheus"      %  "simpleclient_httpserver"   % "0.16.0",
      "io.prometheus"      %  "simpleclient_caffeine"     % "0.16.0",

      // Config & Logging
      "com.typesafe"       %  "config"                    % "1.4.3",
      "ch.qos.logback"     %  "logback-classic"           % "1.5.6",

      // HTTP for health & metrics
      "com.typesafe.akka" %% "akka-http"                  % "10.6.3",
      "com.typesafe.akka" %% "akka-http-spray-json"       % "10.6.3",

      // Tests
      "org.scalatest"     %% "scalatest"                  % "3.2.19"  % Test,
      "com.typesafe.akka" %% "akka-stream-testkit"        % "2.9.3"   % Test,
      "com.typesafe.akka" %% "akka-actor-testkit-typed"   % "2.9.3"   % Test,
      "com.typesafe.akka" %% "akka-http-testkit"          % "10.6.3"  % Test,
      "org.scalatestplus" %% "mockito-5-12"               % "3.2.19.0" % Test
    ),

    // Fat jar for the multi-stage Dockerfile
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) =>
        xs.map(_.toLowerCase) match {
          case "manifest.mf" :: Nil | "index.list" :: Nil | "dependencies" :: Nil =>
            MergeStrategy.discard
          case "services" :: _ => MergeStrategy.filterDistinctLines
          case _               => MergeStrategy.first
        }
      case "reference.conf" | "application.conf" => MergeStrategy.concat
      case "module-info.class"                   => MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
    assembly / mainClass := Some("com.example.jwtcache.Main")
  )

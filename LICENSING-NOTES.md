# Dependency licensing note: Akka

This project depends on Akka (`akka-actor-typed`, `akka-stream`,
`akka-http`, `akka-stream-kafka`) at version 2.9.x / 10.6.x / 4.0.x.

**As of Akka 2.7 (September 2022), Lightbend changed Akka's license from
Apache 2.0 to the Business Source License (BSL) 1.1.** Organizations
above a certain revenue threshold are required to purchase a commercial
license from Akka/Lightbend to use Akka 2.7+ in production. This applies
to `akka-http` and `akka-stream-kafka` as well, since they're part of the
same licensing family.

This is not a code bug and doesn't affect correctness — it's a legal/
compliance consideration worth knowing about before this project (or
anything forked from it) is deployed commercially at scale. See
Lightbend's own announcement and FAQ for current thresholds and terms,
since these can change: https://www.lightbend.com/akka/license-faq

## If this matters for your use case

[Apache Pekko](https://pekko.apache.org/) is a community-maintained fork
of Akka, frozen at the last Apache-2.0-licensed version and actively
developed since. It's close to a drop-in replacement:

- `com.typesafe.akka` → `org.apache.pekko`
- `akka-stream-kafka` → `pekko-connectors-kafka`
- Package imports change from `akka.*` to `org.apache.pekko.*`; most APIs
  used in this project (typed `ActorSystem`, `Source`/`Sink`/`Flow`,
  `akka-http` routing DSL, `ProducerSettings`/`ConsumerSettings`) carry
  over with the same shape.

Migrating is a real (if mostly mechanical) effort and out of scope for
this patch series — flagging it here so it's a deliberate decision
rather than something discovered later.

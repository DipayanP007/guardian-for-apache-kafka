package io.aiven.guardian.kafka.backup

import akka.actor.ActorSystem
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import io.aiven.guardian.akka.AkkaStreamTestKit
import io.aiven.guardian.akka.AnyPropTestKit
import io.aiven.guardian.kafka.Generators.KafkaDataWithTimePeriod
import io.aiven.guardian.kafka.Generators.kafkaDataWithTimePeriodsGen
import io.aiven.guardian.kafka.backup.configs.PeriodFromFirst
import io.aiven.guardian.kafka.codecs.Circe._
import io.aiven.guardian.kafka.models.ReducedConsumerRecord
import org.apache.kafka.common.record.TimestampType
import org.mdedetrich.akka.stream.support.CirceStreamSupport
import org.scalatest.Inspectors
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

import java.time.temporal.ChronoUnit

final case class Periods(periodsBefore: Long, periodsAfter: Long)

class BackupClientInterfaceSpec
    extends AnyPropTestKit(ActorSystem("BackupClientInterfaceSpec"))
    with AkkaStreamTestKit
    with Matchers
    with ScalaFutures
    with ScalaCheckPropertyChecks {

  implicit val ec: ExecutionContext            = system.dispatcher
  implicit val defaultPatience: PatienceConfig = PatienceConfig(90 seconds, 100 millis)

  property("Ordered Kafka events should produce at least one BackupStreamPosition.Boundary") {
    forAll(kafkaDataWithTimePeriodsGen()) { (kafkaDataWithTimePeriod: KafkaDataWithTimePeriod) =>
      val mock =
        new MockedBackupClientInterfaceWithMockedKafkaData(Source(kafkaDataWithTimePeriod.data),
                                                           PeriodFromFirst(kafkaDataWithTimePeriod.periodSlice)
        )

      val calculatedFuture = mock.materializeBackupStreamPositions()

      Inspectors.forAtLeast(1, calculatedFuture.futureValue)(
        _ must equal(mock.End: mock.RecordElement)
      )
    }
  }

  property(
    "Every ReducedConsumerRecord after a BackupStreamPosition.Boundary should be in the next consecutive time period"
  ) {
    forAll(kafkaDataWithTimePeriodsGen()) { (kafkaDataWithTimePeriod: KafkaDataWithTimePeriod) =>
      val mock =
        new MockedBackupClientInterfaceWithMockedKafkaData(Source(kafkaDataWithTimePeriod.data),
                                                           PeriodFromFirst(kafkaDataWithTimePeriod.periodSlice)
        )

      val result = mock.materializeBackupStreamPositions().futureValue.toList

      val allBoundariesWithoutMiddles = result
        .sliding(2)
        .collect { case Seq(mock.End, afterRecordRecordElement: mock.Element) =>
          afterRecordRecordElement
        }
        .toList

      if (allBoundariesWithoutMiddles.length > 1) {
        @nowarn("msg=not.*?exhaustive")
        val withBeforeAndAfter =
          allBoundariesWithoutMiddles.sliding(2).map { case Seq(before, after) => (before, after) }.toList

        val initialTime = kafkaDataWithTimePeriod.data.head.timestamp

        Inspectors.forEvery(withBeforeAndAfter) { case (before, after) =>
          val periodAsMillis = kafkaDataWithTimePeriod.periodSlice.toMillis
          ((before.reducedConsumerRecord.timestamp - initialTime) / periodAsMillis) mustNot equal(
            (after.reducedConsumerRecord.timestamp - initialTime) / periodAsMillis
          )
        }
      }
    }
  }

  property(
    "The time difference between two consecutive BackupStreamPosition.Middle's has to be less then the specified time period"
  ) {
    forAll(kafkaDataWithTimePeriodsGen()) { (kafkaDataWithTimePeriod: KafkaDataWithTimePeriod) =>
      val mock =
        new MockedBackupClientInterfaceWithMockedKafkaData(Source(kafkaDataWithTimePeriod.data),
                                                           PeriodFromFirst(kafkaDataWithTimePeriod.periodSlice)
        )

      val result = mock.materializeBackupStreamPositions().futureValue.toList

      val allCoupledMiddles = result
        .sliding(2)
        .collect { case Seq(before: mock.Element, after: mock.Element) =>
          (before, after)
        }
        .toList

      Inspectors.forEvery(allCoupledMiddles) { case (before, after) =>
        ChronoUnit.MICROS.between(before.reducedConsumerRecord.toOffsetDateTime,
                                  after.reducedConsumerRecord.toOffsetDateTime
        ) must be < kafkaDataWithTimePeriod.periodSlice.toMicros
      }
    }
  }

  property("the time difference between the first and last timestamp for a given key is less than time period") {
    forAll(kafkaDataWithTimePeriodsGen().filter(_.data.size > 2)) {
      (kafkaDataWithTimePeriod: KafkaDataWithTimePeriod) =>
        val mock =
          new MockedBackupClientInterfaceWithMockedKafkaData(Source(kafkaDataWithTimePeriod.data),
                                                             PeriodFromFirst(kafkaDataWithTimePeriod.periodSlice)
          )

        mock.clear()
        val calculatedFuture = for {
          _ <- mock.backup.run()
          _ <- akka.pattern.after(AkkaStreamInitializationConstant)(Future.successful(()))
          processedRecords = mock.mergeBackedUpData()
          asRecords <- Future.sequence(processedRecords.map { case (key, byteString) =>
                         Source
                           .single(byteString)
                           .via(CirceStreamSupport.decode[List[Option[ReducedConsumerRecord]]])
                           .toMat(Sink.collection)(Keep.right)
                           .run()
                           .map(records =>
                             (key,
                              records.flatten.collect { case Some(v) =>
                                v
                              }
                             )
                           )
                       })
        } yield asRecords

        val result = calculatedFuture.futureValue

        Inspectors.forEvery(result) { case (_, records) =>
          (records.headOption, records.lastOption) match {
            case (Some(first), Some(last)) if first != last =>
              ChronoUnit.MICROS.between(first.toOffsetDateTime,
                                        last.toOffsetDateTime
              ) must be < kafkaDataWithTimePeriod.periodSlice.toMicros
            case _ =>
          }
        }
    }
  }

  property("backup method completes flow correctly for all valid Kafka events") {
    forAll(kafkaDataWithTimePeriodsGen().filter(_.data.size > 2)) {
      (kafkaDataWithTimePeriod: KafkaDataWithTimePeriod) =>
        val mock =
          new MockedBackupClientInterfaceWithMockedKafkaData(Source(kafkaDataWithTimePeriod.data),
                                                             PeriodFromFirst(kafkaDataWithTimePeriod.periodSlice)
          )

        mock.clear()
        val calculatedFuture = for {
          _ <- mock.backup.run()
          _ <- akka.pattern.after(AkkaStreamInitializationConstant)(Future.successful(()))
          processedRecords = mock.mergeBackedUpData()
          asRecords <- Future.sequence(processedRecords.map { case (key, byteString) =>
                         Source
                           .single(byteString)
                           .via(CirceStreamSupport.decode[List[Option[ReducedConsumerRecord]]])
                           .toMat(Sink.collection)(Keep.right)
                           .run()
                           .map(records =>
                             (key,
                              records.flatten.collect { case Some(v) =>
                                v
                              }
                             )
                           )
                       })
        } yield asRecords

        val result = calculatedFuture.futureValue

        val observed = result.flatMap { case (_, values) => values }

        kafkaDataWithTimePeriod.data mustEqual observed
    }
  }

  property("backup method completes flow correctly for single element") {
    val reducedConsumerRecord = ReducedConsumerRecord("", 1, "key", "value", 1, TimestampType.CREATE_TIME)

    val mock = new MockedBackupClientInterfaceWithMockedKafkaData(Source.single(
                                                                    reducedConsumerRecord
                                                                  ),
                                                                  PeriodFromFirst(1 day)
    )
    mock.clear()
    val calculatedFuture = for {
      _ <- mock.backup.run()
      _ <- akka.pattern.after(AkkaStreamInitializationConstant)(Future.successful(()))
      processedRecords = mock.mergeBackedUpData()
      asRecords <- Future.sequence(processedRecords.map { case (key, byteString) =>
                     Source
                       .single(byteString)
                       .via(CirceStreamSupport.decode[List[Option[ReducedConsumerRecord]]])
                       .toMat(Sink.collection)(Keep.right)
                       .run()
                       .map(records =>
                         (key,
                          records.flatten.collect { case Some(v) =>
                            v
                          }
                         )
                       )
                   })
    } yield asRecords

    val result = calculatedFuture.futureValue

    val observed = result.flatMap { case (_, values) => values }

    List(reducedConsumerRecord) mustEqual observed
  }

  property("backup method completes flow correctly for two elements") {
    val reducedConsumerRecords = List(
      ReducedConsumerRecord("", 1, "key", "value1", 1, TimestampType.CREATE_TIME),
      ReducedConsumerRecord("", 2, "key", "value2", 2, TimestampType.CREATE_TIME)
    )

    val mock = new MockedBackupClientInterfaceWithMockedKafkaData(Source(
                                                                    reducedConsumerRecords
                                                                  ),
                                                                  PeriodFromFirst(1 millis)
    )
    mock.clear()
    val calculatedFuture = for {
      _ <- mock.backup.run()
      _ <- akka.pattern.after(AkkaStreamInitializationConstant)(Future.successful(()))
      processedRecords = mock.mergeBackedUpData()
      asRecords <- Future.sequence(processedRecords.map { case (key, byteString) =>
                     Source
                       .single(byteString)
                       .via(CirceStreamSupport.decode[List[Option[ReducedConsumerRecord]]])
                       .toMat(Sink.collection)(Keep.right)
                       .run()
                       .map(records =>
                         (key,
                          records.flatten.collect { case Some(v) =>
                            v
                          }
                         )
                       )
                   })
    } yield asRecords

    val result = calculatedFuture.futureValue

    val observed = result.flatMap { case (_, values) => values }

    reducedConsumerRecords mustEqual observed
  }

  property("backup method correctly terminates every key apart from last") {
    forAll(kafkaDataWithTimePeriodsGen().filter(_.data.size > 1)) {
      (kafkaDataWithTimePeriod: KafkaDataWithTimePeriod) =>
        val mock =
          new MockedBackupClientInterfaceWithMockedKafkaData(Source(kafkaDataWithTimePeriod.data),
                                                             PeriodFromFirst(kafkaDataWithTimePeriod.periodSlice)
          )

        mock.clear()
        val calculatedFuture = for {
          _ <- mock.backup.run()
          _ <- akka.pattern.after(AkkaStreamInitializationConstant)(Future.successful(()))
          processedRecords = mock.mergeBackedUpData(terminate = false)
        } yield processedRecords.splitAt(processedRecords.length - 1)

        val (terminated, nonTerminated) = calculatedFuture.futureValue

        if (nonTerminated.nonEmpty) {
          Inspectors.forEvery(terminated) { case (_, byteString) =>
            byteString.utf8String.takeRight(2) mustEqual "}]"
          }
        }

        Inspectors.forEvery(nonTerminated) { case (_, byteString) =>
          byteString.utf8String.takeRight(2) mustEqual "},"
        }
    }
  }
}

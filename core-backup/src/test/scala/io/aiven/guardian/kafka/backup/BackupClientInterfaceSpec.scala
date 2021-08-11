package io.aiven.guardian.kafka.backup

import akka.actor.ActorSystem
import io.aiven.guardian.kafka.models.ReducedConsumerRecord
import io.aiven.guardian.kafka.{Generators, ScalaTestConstants}
import org.scalacheck.Gen
import org.scalatest.Inspectors
import org.scalatest.matchers.must.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.time.temporal.ChronoUnit
import scala.annotation.nowarn
import scala.concurrent.Await
import scala.concurrent.duration.{FiniteDuration, _}

final case class Periods(periodsBefore: Long, periodsAfter: Long)

final case class KafkaDataWithTimePeriod(data: List[ReducedConsumerRecord], periodSlice: FiniteDuration)

class BackupClientInterfaceSpec
    extends AnyPropSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaTestConstants {

  implicit val system: ActorSystem = ActorSystem()

  val periodGen = for {
    before <- Gen.long
    after  <- Gen.long
  } yield Periods(before, after)

  def kafkaDataWithTimePeriodsGen: Gen[KafkaDataWithTimePeriod] = for {
    topic   <- Gen.alphaStr
    records <- Generators.kafkaReducedConsumerRecordsGen(topic, 2, 100, 10)
    head = records.head
    last = records.last

    duration <- Gen.choose[Long](head.timestamp, last.timestamp - 1).map(millis => FiniteDuration(millis, MILLISECONDS))
  } yield KafkaDataWithTimePeriod(records, duration)

  property("Ordered Kafka events should produce at least one BackupStreamPosition.Boundary") {
    forAll(kafkaDataWithTimePeriodsGen) { (kafkaDataWithTimePeriod: KafkaDataWithTimePeriod) =>
      val mock = new MockedBackupClientInterfaceWithMockedKafkaData(kafkaDataWithTimePeriod.data,
                                                                    kafkaDataWithTimePeriod.periodSlice
      )

      val calculatedFuture = mock.materializeBackupStreamPositions()

      val result = Await.result(calculatedFuture, AwaitTimeout).toList
      val backupStreamPositions = result.map { case (_, backupStreamPosition) =>
        backupStreamPosition
      }

      Inspectors.forAtLeast(1, backupStreamPositions)(_ mustEqual BackupStreamPosition.Boundary)
    }
  }

  property(
    "Every ReducedConsumerRecord after a BackupStreamPosition.Boundary should be in the next consecutive time period"
  ) {
    forAll(kafkaDataWithTimePeriodsGen) { (kafkaDataWithTimePeriod: KafkaDataWithTimePeriod) =>
      val mock = new MockedBackupClientInterfaceWithMockedKafkaData(kafkaDataWithTimePeriod.data,
                                                                    kafkaDataWithTimePeriod.periodSlice
      )

      val calculatedFuture = mock.materializeBackupStreamPositions()

      val result = Await.result(calculatedFuture, AwaitTimeout).toList

      val allBoundariesWithoutMiddles = result
        .sliding(2)
        .collect { case Seq((_, _: BackupStreamPosition.Boundary.type), (afterRecord, _)) =>
          afterRecord
        }
        .toList

      if (allBoundariesWithoutMiddles.length > 1) {
        @nowarn("msg=not.*?exhaustive")
        val withBeforeAndAfter =
          allBoundariesWithoutMiddles.sliding(2).map { case Seq(before, after) => (before, after) }.toList

        val initialTime = kafkaDataWithTimePeriod.data.head.timestamp

        Inspectors.forEvery(withBeforeAndAfter) { case (before, after) =>
          val periodAsMillis = kafkaDataWithTimePeriod.periodSlice.toMillis
          ((before.timestamp - initialTime) / periodAsMillis) mustNot equal(
            (after.timestamp - initialTime) / periodAsMillis
          )
        }
      }
    }
  }

  property(
    "The time difference between two consecutive BackupStreamPosition.Middle's has to be less then the specified time period"
  ) {
    forAll(kafkaDataWithTimePeriodsGen) { (kafkaDataWithTimePeriod: KafkaDataWithTimePeriod) =>
      val mock = new MockedBackupClientInterfaceWithMockedKafkaData(kafkaDataWithTimePeriod.data,
                                                                    kafkaDataWithTimePeriod.periodSlice
      )

      val calculatedFuture = mock.materializeBackupStreamPositions()

      val result = Await.result(calculatedFuture, AwaitTimeout).toList

      val allCoupledMiddles = result
        .sliding(2)
        .collect {
          case Seq((beforeRecord, _: BackupStreamPosition.Middle.type),
                   (afterRecord, _: BackupStreamPosition.Middle.type)
              ) =>
            (beforeRecord, afterRecord)
        }
        .toList

      Inspectors.forEvery(allCoupledMiddles) { case (before, after) =>
        ChronoUnit.MICROS.between(before.toOffsetDateTime,
                                  after.toOffsetDateTime
        ) must be < kafkaDataWithTimePeriod.periodSlice.toMicros
      }
    }
  }
}
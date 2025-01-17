package io.aiven.guardian.kafka

import io.aiven.guardian.kafka.models.ReducedConsumerRecord
import org.apache.kafka.common.record.TimestampType
import org.scalacheck.Gen

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.MILLISECONDS
import scala.jdk.CollectionConverters._

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object Generators {
  def baseReducedConsumerRecordGen(topic: String,
                                   offset: Long,
                                   key: String,
                                   timestamp: Long
  ): Gen[ReducedConsumerRecord] = for {
    t             <- Gen.const(topic)
    o             <- Gen.const(offset)
    k             <- Gen.const(key)
    value         <- Gen.alphaStr.map(string => Base64.getEncoder.encodeToString(string.getBytes))
    ts            <- Gen.const(timestamp)
    timestampType <- Gen.const(TimestampType.CREATE_TIME)
  } yield ReducedConsumerRecord(
    t,
    o,
    k,
    value,
    ts,
    timestampType
  )

  private val keyOffsetMap = new ConcurrentHashMap[String, AtomicLong]().asScala

  def createOffsetsByKey(key: String): Long = {
    val returned = keyOffsetMap.getOrElseUpdate(key, new AtomicLong())
    returned.incrementAndGet()
  }

  /** A generator that allows you to generator an arbitrary collection of Kafka `ReducedConsumerRecord` used for
    * mocking. The generator will create a random distribution of keys (with each key having its own specific record of
    * offsets) allowing you to mock multi partition Kafka scenarios.
    * @param topic
    *   The name of the kafka topic
    * @param min
    *   The minimum number of `ReducedConsumerRecord`'s to generate
    * @param max
    *   The maximum number of `ReducedConsumerRecord`'s to generate
    * @param padTimestampsMillis
    *   The amount of padding (in milliseconds) between consecutive timestamps. If set to 0 then all timestamps will
    *   differ by a single millisecond
    * @return
    *   A list of generated `ReducedConsumerRecord`
    */
  def kafkaReducedConsumerRecordsGen(topic: String,
                                     min: Int,
                                     max: Int,
                                     padTimestampsMillis: Int
  ): Gen[List[ReducedConsumerRecord]] = for {
    t                           <- Gen.const(topic)
    numberOfTotalReducedRecords <- Gen.chooseNum[Int](min, max)
    numberOfKeys                <- Gen.chooseNum[Int](1, numberOfTotalReducedRecords)
    keys <- Gen.listOfN(numberOfKeys, Gen.alphaStr.map(string => Base64.getEncoder.encodeToString(string.getBytes)))
    keyDistribution <- Gen.listOfN(numberOfTotalReducedRecords, Gen.oneOf(keys))
    keysWithOffSets = keyDistribution.groupMap(identity)(createOffsetsByKey)
    reducedConsumerRecordsWithoutTimestamp = keysWithOffSets
                                               .map { case (key, offsets) =>
                                                 offsets.map { offset =>
                                                   (t, offset, key)
                                                 }
                                               }
                                               .toList
                                               .flatten
    timestampsWithPadding <- Gen
                               .sequence((1 to reducedConsumerRecordsWithoutTimestamp.size).map { _ =>
                                 Gen.chooseNum[Long](1, padTimestampsMillis + 1)
                               })
                               .map(_.asScala.toList)
    timestamps = timestampsWithPadding.foldLeft(ListBuffer(1L)) { case (timestamps, padding) =>
                   val last = timestamps.last
                   timestamps.append(last + padding)
                 }

    reducedConsumerRecords <-
      Gen
        .sequence(reducedConsumerRecordsWithoutTimestamp.zip(timestamps).map { case ((topic, offset, key), timestamp) =>
          baseReducedConsumerRecordGen(topic, offset, key, timestamp)
        })
        .map(_.asScala.toList)

  } yield reducedConsumerRecords

  final case class KafkaDataWithTimePeriod(data: List[ReducedConsumerRecord], periodSlice: FiniteDuration)

  def randomPeriodSliceBetweenMinMax(reducedConsumerRecords: List[ReducedConsumerRecord]): Gen[FiniteDuration] = {
    val head = reducedConsumerRecords.head
    val last = reducedConsumerRecords.last
    Gen.choose[Long](head.timestamp, last.timestamp - 1).map(millis => FiniteDuration(millis, MILLISECONDS))
  }

  def kafkaDateGen(min: Int = 2,
                   max: Int = 100,
                   padTimestampsMillis: Int = 10,
                   condition: Option[List[ReducedConsumerRecord] => Boolean] = None
  ): Gen[List[ReducedConsumerRecord]] = for {
    topic <- kafkaTopic
    records <- {
      val base = Generators.kafkaReducedConsumerRecordsGen(topic, min, max, padTimestampsMillis)
      condition.fold(base)(c => Gen.listOfFillCond(c, base))
    }
  } yield records

  /** Creates a generated dataset of Kafka events along with a time slice period using sensible values
    * @param min
    *   The minimum number of `ReducedConsumerRecord`'s to generate. Defaults to 2.
    * @param max
    *   The maximum number of `ReducedConsumerRecord`'s to generate. Defaults to 100.
    * @param padTimestampsMillis
    *   The amount of padding (in milliseconds) between consecutive timestamps. If set to 0 then all timestamps will
    *   differ by a single millisecond. Defaults to 10 millis.
    */
  def kafkaDataWithTimePeriodsGen(min: Int = 2,
                                  max: Int = 100,
                                  padTimestampsMillis: Int = 10,
                                  periodSliceFunction: List[ReducedConsumerRecord] => Gen[FiniteDuration] =
                                    randomPeriodSliceBetweenMinMax,
                                  condition: Option[List[ReducedConsumerRecord] => Boolean] = None
  ): Gen[KafkaDataWithTimePeriod] = for {
    records  <- kafkaDateGen(min, max, padTimestampsMillis, condition)
    duration <- periodSliceFunction(records)
  } yield KafkaDataWithTimePeriod(records, duration)

  def reducedConsumerRecordsUntilSize(size: Long, toBytesFunc: List[ReducedConsumerRecord] => Array[Byte])(
      reducedConsumerRecords: List[ReducedConsumerRecord]
  ): Boolean =
    toBytesFunc(reducedConsumerRecords).length > size

  def timePeriodAlwaysGreaterThanAllMessages(reducedConsumerRecords: List[ReducedConsumerRecord]): Gen[FiniteDuration] =
    FiniteDuration(reducedConsumerRecords.last.timestamp + 1, MILLISECONDS)

  final case class KafkaDataInChunksWithTimePeriod(data: List[List[ReducedConsumerRecord]], periodSlice: FiniteDuration)

  /** @param size
    *   The minimum number of bytes
    * @return
    *   A list of [[ReducedConsumerRecord]] that is at least as big as `size`.
    */
  def kafkaDataWithMinSizeGen(size: Long,
                              amount: Int,
                              toBytesFunc: List[ReducedConsumerRecord] => Array[Byte]
  ): Gen[KafkaDataInChunksWithTimePeriod] = {
    val single = kafkaDateGen(1000, 10000, 10, Some(reducedConsumerRecordsUntilSize(size, toBytesFunc)))
    for {
      recordsSplitBySize <- Gen.sequence(List.fill(amount)(single)).map(_.asScala.toList)
      duration           <- timePeriodAlwaysGreaterThanAllMessages(recordsSplitBySize.flatten)
    } yield KafkaDataInChunksWithTimePeriod(recordsSplitBySize, duration)
  }

  /** Generator for a valid Kafka topic that can be used in actual Kafka clusters
    */
  lazy val kafkaTopic: Gen[String] = for {
    size  <- Gen.choose(1, 249)
    topic <- Gen.listOfN(size, Gen.oneOf(Gen.alphaChar, Gen.numChar, Gen.const('-'), Gen.const('.'), Gen.const('_')))
  } yield topic.mkString
}

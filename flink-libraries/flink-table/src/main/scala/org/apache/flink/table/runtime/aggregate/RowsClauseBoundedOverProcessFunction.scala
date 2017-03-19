/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.runtime.aggregate

import java.util.{ArrayList, List => JList}

import org.apache.flink.api.common.state._
import org.apache.flink.api.java.typeutils.RowTypeInfo
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.apache.flink.table.functions.{Accumulator, AggregateFunction}
import org.apache.flink.types.Row
import org.apache.flink.util.{Collector, Preconditions}

/**
  * Process Function for ROWS clause event-time bounded OVER window
  *
  * @param aggregates           the list of all [[org.apache.flink.table.functions.AggregateFunction]]
  *                             used for this aggregation
  * @param aggFields            the position (in the input Row) of the input value for each aggregate
  * @param forwardedFieldCount  the count of forwarded fields.
  * @param aggregationStateType the row type info of aggregation
  * @param precedingOffset      the preceding offset
  */
class RowsClauseBoundedOverProcessFunction(
    private val aggregates: Array[AggregateFunction[_]],
    private val aggFields: Array[Int],
    private val forwardedFieldCount: Int,
    private val aggregationStateType: RowTypeInfo,
    private val precedingOffset: Long)
  extends ProcessFunction[Row, Row] {

  Preconditions.checkNotNull(aggregates)
  Preconditions.checkNotNull(aggFields)
  Preconditions.checkArgument(aggregates.length == aggFields.length)
  Preconditions.checkNotNull(forwardedFieldCount)
  Preconditions.checkNotNull(aggregationStateType)
  Preconditions.checkNotNull(precedingOffset)

  private var output: Row = _

  // the state which keeps the last triggering timestamp
  private var lastTriggeringTsState: ValueState[Long] = _

  // the state which keeps the count of data
  private var dataCountState: ValueState[Long] = null

  // the state which used to materialize the accumulator for incremental calculation
  private var accumulatorState: ValueState[Row] = _

  // the state which keeps all the data that are not expired.
  // The first element (as the mapState key) of the tuple is the time stamp. Per each time stamp,
  // the second element of tuple is a list that contains the entire data of all the rows belonging
  // to this time stamp.
  private var dataState: MapState[Long, JList[Row]] = _

  override def open(config: Configuration) {

    output = new Row(forwardedFieldCount + aggregates.length)


    val lastTriggeringTsDescriptor: ValueStateDescriptor[Long] =
      new ValueStateDescriptor[Long]("lastTriggeringTsState", classOf[Long])
    lastTriggeringTsState = getRuntimeContext.getState(lastTriggeringTsDescriptor)

    val dataCountStateDescriptor =
      new ValueStateDescriptor[Long]("dataCountState", classOf[Long])
    dataCountState = getRuntimeContext.getState(dataCountStateDescriptor)

    val accumulatorStateDescriptor =
      new ValueStateDescriptor[Row]("accumulatorState", aggregationStateType)
    accumulatorState = getRuntimeContext.getState(accumulatorStateDescriptor)

    val mapStateDescriptor: MapStateDescriptor[Long, JList[Row]] =
      new MapStateDescriptor[Long, JList[Row]](
        "dataState",
        classOf[Long],
        classOf[JList[Row]])

    dataState = getRuntimeContext.getMapState(mapStateDescriptor)

  }

  override def processElement(
    input: Row,
    ctx: ProcessFunction[Row, Row]#Context,
    out: Collector[Row]): Unit = {

    // triggering timestamp for trigger calculation
    val triggeringTs = ctx.timestamp

    val lastTriggeringTs = lastTriggeringTsState.value
    // check if the data is expired, if not, save the data and register event time timer
    if (triggeringTs > lastTriggeringTs && triggeringTs > ctx.timerService.currentWatermark) {
      if (dataState.contains(triggeringTs)) {
        val data = dataState.get(triggeringTs)
        data.add(input)
        dataState.put(triggeringTs, data)
      } else {
        val data = new ArrayList[Row]
        data.add(input)
        dataState.put(triggeringTs, data)
        // register event time timer
        ctx.timerService.registerEventTimeTimer(triggeringTs)
      }
    }
  }

  override def onTimer(
    timestamp: Long,
    ctx: ProcessFunction[Row, Row]#OnTimerContext,
    out: Collector[Row]): Unit = {

    // gets all window data from state for the calculation
    val inputs: JList[Row] = dataState.get(timestamp)
    if (null != inputs) {
      var accumulators = accumulatorState.value
      var j: Int = 0
      while (j < inputs.size) {
        val input = inputs.get(j)

        // initialize when first run or failover recovery per key
        if (null == accumulators) {
          accumulators = new Row(aggregates.length)
          var i = 0
          while (i < aggregates.length) {
            accumulators.setField(i, aggregates(i).createAccumulator)
            i += 1
          }
        }

        var dataCount = dataCountState.value + 1
        dataCountState.update(dataCount)

        var lastExpiredRow: Row = null

        if (dataCount > precedingOffset) {
          val dataTimestampIt = dataState.keys.iterator
          var expiredDataTs: Long = Long.MaxValue
          while (dataTimestampIt.hasNext) {
            val dataTs = dataTimestampIt.next
            if (dataTs < expiredDataTs) {
              expiredDataTs = dataTs
            }
          }
          val windowDataList = dataState.get(expiredDataTs)

          lastExpiredRow = windowDataList.get(0)

          windowDataList.remove(0)

          if (windowDataList.size > 0) {
            dataState.put(expiredDataTs, windowDataList)
          } else {
            dataState.remove(expiredDataTs)
          }
        }

        var i = 0
        while (i < forwardedFieldCount) {
          output.setField(i, input.getField(i))
          i += 1
        }

        if (null != lastExpiredRow) {
          i = 0
          while (i < aggregates.length) {
            val accumulator = accumulators.getField(i).asInstanceOf[Accumulator]
            aggregates(i).retract(accumulator, lastExpiredRow.getField(aggFields(i)))
            i += 1
          }
        }

        i = 0
        while (i < aggregates.length) {
          val index = forwardedFieldCount + i
          val accumulator = accumulators.getField(i).asInstanceOf[Accumulator]
          aggregates(i).accumulate(accumulator, input.getField(aggFields(i)))
          output.setField(index, aggregates(i).getValue(accumulator))
          i += 1
        }

        out.collect(output)

        j += 1
      }
      accumulatorState.update(accumulators)
    }
    lastTriggeringTsState.update(timestamp)
  }
}

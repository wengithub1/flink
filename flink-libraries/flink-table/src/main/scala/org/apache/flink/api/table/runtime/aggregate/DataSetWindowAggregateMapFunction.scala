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
package org.apache.flink.api.table.runtime.aggregate

import org.apache.flink.api.common.functions.RichMapFunction
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.ResultTypeQueryable
import org.apache.flink.api.table.Row
import org.apache.flink.api.table.runtime.aggregate.AggregateUtil.getTimestamp
import org.apache.flink.configuration.Configuration
import org.apache.flink.util.Preconditions

class DataSetWindowAggregateMapFunction[IN, OUT](
    private val aggregates: Array[Aggregate[_]],
    private val aggFields: Array[Int],
    private val groupingKeys: Array[Int],
    private val rowTimeFieldPos: Option[Int] = None,
    @transient private val rowTimeFieldType: Option[TypeInformation[_]] = None,
    @transient private val returnType: TypeInformation[OUT])
  extends RichMapFunction[IN, OUT]
  with ResultTypeQueryable[OUT] {

  private var output: Row = _

  override def open(config: Configuration) {
    Preconditions.checkNotNull(aggregates)
    Preconditions.checkNotNull(aggFields)
    Preconditions.checkArgument(aggregates.size == aggFields.size)
    val intermediateRowArity = rowTimeFieldPos match {
      case Some(_) if rowTimeFieldPos.get >= 0 =>
        val groupKeyAndAggregateFiledCount = groupingKeys.length +
          aggregates.map(_.intermediateDataType.length).sum + 1
        groupKeyAndAggregateFiledCount
      case _ =>
        groupingKeys.length + aggregates.map(_.intermediateDataType.length).sum
    }
    output = new Row(intermediateRowArity)
  }

  override def map(value: IN): OUT = {

    val input = value.asInstanceOf[Row]
    for (i <- 0 until aggregates.length) {
      val fieldValue = input.productElement(aggFields(i))
      aggregates(i).prepare(fieldValue, output)
    }
    for (i <- 0 until groupingKeys.length) {
      output.setField(i, input.productElement(groupingKeys(i)))
    }

    rowTimeFieldPos match {
      case Some(_) if rowTimeFieldPos.get >= 0 =>
        val winKeyValue = input.productElement(rowTimeFieldPos.get)
        val rowtime = getTimestamp(winKeyValue, rowTimeFieldType.get)
        output.setField(output.productArity-1, rowtime)
    }
    output.asInstanceOf[OUT]
  }

  override def getProducedType: TypeInformation[OUT] = {
    returnType
  }
}
